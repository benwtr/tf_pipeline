#!groovy

pull_request = false

if (gh_path != '') {
  gh_path = "${gh_path}/"
}

def _env_vars = env_vars.tokenize()

slack_notifications = slack_notifications.toBoolean()

def _sh(script) {
  _catch_errors {
    wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'XTerm']) {
      withEnv(["gh_path=$gh_path"]) {
        sh '''\
          #!/bin/bash
          set -x
          set -e
          cd ./${gh_path}
          set +e
        '''.stripIndent() + script
      }
    }
  }
}

def _catch_errors(cb) {
  try {
    cb()
    currentBuild.result = 'SUCCESS'
  } catch(err) {
    currentBuild.result = 'FAILURE'
    if (pull_request) {
      set_pr_status 'terraform run failed'
    }
    error()
  }
}

def _strip_ansi_color(input_file,output_file) {
  withEnv(["input_file=$input_file","output_file=$output_file"]) {
    _sh '''\
      sed -r "s/\\x1B\\[([0-9]{1,2}(;[0-9]{1,2})?)?[m|K]//g"  \
        < "${input_file}" > "${output_file}"
    '''.stripIndent()
  }
}

def prepare_workspace() {
  stage 'prepare workspace'
  _sh '''\
    rm -rf .terraform
    rm -f exitcode.txt terraform.plan terraform.plan.txt terraform.plan.ansi terraform.plan.summary terraform.apply.ansi terraform.apply.txt
  '''.stripIndent()
}

def setup_tf() {
  stage 'setup terraform'
  withEnv(["tf_version=$tf_version","tf_arch=$tf_arch","tf_sha256=$tf_sha256"]) {
    _sh '''\
      tf_zipfile="terraform_${tf_version}_${tf_arch}.zip"
      tf_url="https://releases.hashicorp.com/terraform/${tf_version}/${tf_zipfile}"

      if [ ! -f "${tf_zipfile}" ]
      then
        wget -q "${tf_url}"
      fi

      if [ "$(sha256sum ${tf_zipfile} | awk '{print $1}')" = "${tf_sha256}" ]
      then
        if [ ! -x "./bin/terraform" ]
        then
          unzip -o ${tf_zipfile} -d bin
        fi
      else
        echo "Checksum on downloaded terraform archive was incorrect, please check"
        exit 1
      fi
    '''.stripIndent()
  }
}

def fetch_modules() {
  stage 'fetch modules'
  sshagent([gh_credentials_id]) {
    _sh './bin/terraform get -update'
  }
}

def tf_validate() {
  stage 'validate syntax'
  _sh './bin/terraform validate'
}

def tf_taint(name, module) {
  stage 'taint'
  _sh "./bin/terraform taint -module=${module} ${name}"
}

def tf_untaint(name, module) {
  stage 'untaint'
  _sh "./bin/terraform untaint -module=${module} ${name}"
}

def git_checkout() {
  stage 'git checkout'
  if (pull_request) {
    checkout([
      $class: 'GitSCM',
      branches: [[name: "origin-pull/pull/${GITHUB_PR_NUMBER}/merge"]],
      doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [],
      userRemoteConfigs: [[
        credentialsId: gh_credentials_id,
        name: 'origin-pull',
        url: "git@github.com:${gh_owner}/${gh_repo}",
        refspec: "+refs/pull/${GITHUB_PR_NUMBER}/merge:refs/remotes/origin-pull/pull/${GITHUB_PR_NUMBER}/merge"
      ]]
    ])
  } else {
    checkout([
      $class: 'GitSCM',
      branches: [[name: "*/master"]],
      doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [],
      userRemoteConfigs: [[
        credentialsId: gh_credentials_id,
        url: "git@github.com:${gh_owner}/${gh_repo}"
      ]]
    ])
  }
}

def initialize_remote_state() {
  stage 'initialize remote state'
  withEnv(["s3_bucket=$s3_bucket","s3_key=$s3_key"]) {
    _sh '''\
      ./bin/terraform remote config \
        -backend=s3 \
        -backend-config="bucket=${s3_bucket}" \
        -backend-config="key=${s3_key}/terraform.tfstate" \
        -backend-config="region=us-east-1" \
        -backend-config="acl=bucket-owner-full-control"
    '''.stripIndent()
  }
}

def tf_plan(extra_flags='') {
  if (pull_request) {
    set_pr_pending()
  }
  
  stage 'run plan'
  withEnv(["extra_flags=$extra_flags"]) {
    _sh '''\
      set -o pipefail

      ./bin/terraform plan \
        -refresh=true \
        -input=false \
        -out=terraform.plan \
        -detailed-exitcode \
        ${extra_flags} \
        2>&1 | tee "terraform.plan.ansi"

      echo "exit code: $?"
      echo $? > exitcode.txt
    '''.stripIndent()
  }

  _strip_ansi_color "terraform.plan.ansi", "terraform.plan.txt"

  _sh '''\
    exitcode=$(cat exitcode.txt)
    
    if [ $exitcode -eq 2 ]
    then
      grep "^Plan:" "terraform.plan.txt" > "terraform.plan.summary"
    elif [ $exitcode -eq 1 ]
    then
      echo "Errors and/or warnings" > "terraform.plan.summary"
    elif [ $exitcode -eq 0 ]
    then
      echo "No changes" > "terraform.plan.summary"
    else
      echo "Plan status unknown" > "terraform.plan.summary"
    fi

    echo -n "Summary: " ; cat "terraform.plan.summary"
  '''.stripIndent()
  
  def exitcode = readFile "${gh_path}exitcode.txt"
  def plan_txt = readFile "${gh_path}terraform.plan.txt"
  def plan_summary = readFile "${gh_path}terraform.plan.summary"
  
  if (pull_request) {
    if (gh_path == '') {
      display_gh_path = '/'
    } else {
      display_gh_path = gh_path
    }
    def pr_comment = "### Here is the output of `terraform plan` for branch: _${GITHUB_PR_SOURCE_BRANCH}_, using path: _${display_gh_path}_:\n\n```\n${plan_txt}\n```\n**Merging this to ${GITHUB_PR_TARGET_BRANCH} will trigger an APPLY run** \n\nYou can trigger the build again by entering \"replan\" as the body of a comment here, or pushing more changes to this PR."
    add_pr_comment(pr_comment)
  }

  if (exitcode == "1") {
    if (pull_request) {
      set_pr_status(plan_summary, true)
    }
    error plan_summary
  } else {
    if (pull_request) {
      set_pr_status(plan_summary)
    }
  }
  
  archive_artifacts "${gh_path}terraform.plan, ${gh_path}terraform.plan.txt, ${gh_path}terraform.plan.ansi, ${gh_path}terraform.plan.summary"
}

def tf_apply() {
  stage 'run apply'

  _sh '''\
    set -o pipefail

    ./bin/terraform apply \
      -input=false \
      terraform.plan \
      2>&1 | tee "terraform.apply.ansi"

  '''.stripIndent()

  _strip_ansi_color "terraform.apply.ansi", "terraform.apply.txt"

  archive_artifacts "${gh_path}terraform.apply.ansi, ${gh_path}terraform.apply.txt"
}

def set_pr_pending() {
  stage 'set PR pending status'
  step([
    $class: 'GitHubPRStatusBuilder',
    statusMessage: [
      content: "Build #${env.BUILD_NUMBER} (terraform plan) started"
    ]
  ])
}

def add_pr_comment(pr_comment) {
  stage 'add PR comment'
  step([
    $class: 'GitHubPRCommentPublisher',
    comment: [content: pr_comment]
  ])
}

def set_pr_status(status_message, failure=false) {
  stage 'set PR status'
  if (failure) {
    currentBuild.result = FAILURE
  }
  step([
    $class: 'GitHubPRBuildStatusPublisher',
    buildMessage: [
      failureMsg: [content: 'Can\'t set status; build failed.'],
      successMsg: [content: 'Can\'t set status; build succeeded.']
    ],
    statusMsg: [content: status_message],
    unstableAs: 'FAILURE'
  ])
}

def archive_artifacts(artifacts) {
  stage 'archive artifacts'
  step([
    $class: 'ArtifactArchiver',
    artifacts: artifacts,
    excludes: null,
    fingerprint: true,
    onlyIfSuccessful: true
  ])
}

def wait_for_user_to_apply() {
  stage 'prompt user to abort or confirm'
  input message: 'Run Terraform apply?', ok: 'Confirm/Apply'
}

def notify_about_pending_changes() {
  def plan_summary = readFile "${gh_path}terraform.plan.summary"
  slackSend channel: "${slack_channel}", color: 'warning', message: "[Terraform] ${env.JOB_NAME} Pending changes: ${plan_summary}\nPlease Confirm/Apply or Abort. <${env.BUILD_URL}/console|Link>"
}

