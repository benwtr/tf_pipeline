#!groovy

job('create_terraform_jobs') {
  parameters {
    stringParam 'gh_owner', '', 'User or organization for the github repo, eg: https://github.com/OWNER/repo'
    stringParam 'gh_repo', '', 'Repo where TF code is stored, eg: https://github.com/owner/REPO'
    stringParam 'gh_path', '', 'Root of terraform code inside the repo. Dont use slashes here, trailing or otherwise, and it can be only one level deep. Empty string if tf code is in /, use this for dev/staging/prod envs'
    stringParam 's3_bucket', '', 'Name of S3 bucket for terraform state files'
    stringParam 's3_key', '', 'Key/path to store this enviroment\'s state file in s3; the "name" of the environment'
    credentialsParam('gh_credentials_id') {
      description 'SSH private key for github. (Use \'git\' as the username)'
      type 'com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey'
      required(true)
    }
    stringParam 'tf_version', '0.7.0', 'Terraform version to install and use'
    stringParam 'tf_arch', 'linux_amd64', 'Terraform arch to install and use'
    stringParam 'tf_sha256', 'a196c63b967967343f3ae9bb18ce324a18b27690e2d105e1f38c5a2d7c02038d', 'Terraform .zip sha256 sum'
    booleanParam 'create_destroy_job', false, 'Create a DESTROY job for this environment'
    booleanParam 'slack_notifications', false, 'Send notifications to a slack channel'
    stringParam 'slack_channel', '', 'Slack channel to send notifications to'
    textParam 'env_vars', '', 'Multi-line text input containing NAME=VALUE pairs. While not recommend, this is one way to pass auth data eg: `AWS_ACCESS_KEY_ID` & `AWS_SECRET_ACCESS_KEY`'
  }
  scm {
    github 'benwtr/tf_pipeline'
  }
  steps {
    dsl {
      text '''\
        folder 'Terraform'
        folder "Terraform/${gh_owner}"
        folder "Terraform/${gh_owner}/${gh_repo}"

        def tf_job(job_name, workflow) {
          if (gh_path == '') {
            name = "Terraform/${gh_owner}/${gh_repo}/${job_name}"
          } else {
            name = "Terraform/${gh_owner}/${gh_repo}/${job_name}_${gh_path}"
          }
          pipelineJob(name) {
            properties {
              githubProjectUrl("http://github.com/${gh_owner}/${gh_repo}")
            }
            parameters {
              wReadonlyStringParameterDefinition {
                name 's3_bucket'
                defaultValue s3_bucket
                description ''
              }
              wReadonlyStringParameterDefinition {
                name 's3_key'
                defaultValue s3_key
                description ''
              }
              wReadonlyStringParameterDefinition {
                name 'gh_credentials_id'
                defaultValue gh_credentials_id
                description ''
              }
              wReadonlyStringParameterDefinition {
                name 'tf_version'
                defaultValue tf_version
                description ''
              }
              wReadonlyStringParameterDefinition {
                name 'tf_arch'
                defaultValue tf_arch
                description ''
              }
              wReadonlyStringParameterDefinition {
                name 'tf_sha256'
                defaultValue tf_sha256
                description ''
              }
              wReadonlyStringParameterDefinition {
                name 'gh_owner'
                defaultValue gh_owner
                description ''
              }
              wReadonlyStringParameterDefinition {
                name 'gh_repo'
                defaultValue gh_repo
                description ''
              }
              wReadonlyStringParameterDefinition {
                name 'gh_path'
                defaultValue gh_path
                description ''
              }
              booleanParameterDefinition {
                name 'slack_notifications'
                defaultValue slack_notifications.toBoolean()
                description ''
              }
              wReadonlyStringParameterDefinition {
                name 'slack_channel'
                defaultValue slack_channel
                description ''
              }
              wReadonlyTextParameterDefinition {
                name 'env_vars'
                defaultValue env_vars
                description ''
              }
            }
            definition {
              cps {
                // sandbox() // uncomment then whitelist methods under _In-process Script Approval_ if using the groovy sandbox
                script readFileFromWorkspace('pipeline.groovy') + workflow
              }
            }
          }
        }

        def tf_plan_job = tf_job 'PLAN', """
          withEnv(_env_vars) {
            node {
              pull_request=true
              git_checkout()
              prepare_workspace()
              setup_tf()
              tf_validate()
              fetch_modules()
              initialize_remote_state()
              tf_plan()
            }
          }
        """.stripIndent()
        
        tf_plan_job.with {
          triggers {
            gitHubPRTrigger {
              spec '* * * * *'
              triggerMode 'HEAVY_HOOKS_CRON'
              branchRestriction {
                targetBranch 'master'
              }
              events {
                gitHubPROpenEvent()
                gitHubPRCommitEvent()
                gitHubPRCommentEvent {
                  comment 'replan'
                }
              }
              skipFirstRun(true)
            }
          }
        }

        def tf_apply_job = tf_job 'APPLY', """
          withEnv(_env_vars) {
            node {
              git_checkout()
              prepare_workspace()
              setup_tf()
              tf_validate()
              fetch_modules()
              initialize_remote_state()
              tf_plan()
              if (slack_notifications) {
                notify_about_pending_changes()
              }
              wait_for_user_to_apply()
              tf_apply()
            }
          }
        """.stripIndent()
        
        tf_apply_job.with {
          triggers {
            scm('* * * * *')
          }
        }

        if (create_destroy_job.toBoolean()) {
          def tf_destroy_job = tf_job 'DESTROY', """
            withEnv(_env_vars) {
              node {
                git_checkout()
                prepare_workspace()
                setup_tf()
                tf_validate()
                fetch_modules()
                initialize_remote_state()
                tf_plan('-destroy')
                if (slack_notifications) {
                  notify_about_pending_changes()
                }
                wait_for_user_to_apply()
                tf_apply()
              }
            }
          """.stripIndent()
        }

        def tf_taint_job = tf_job 'TAINT', """
          withEnv(_env_vars) {
            node {
              git_checkout()
              prepare_workspace()
              setup_tf()
              tf_validate()
              fetch_modules()
              initialize_remote_state()
              tf_taint(taint_resource, taint_module)
              tf_plan()
              if (slack_notifications) {
                notify_about_pending_changes()
              }
              wait_for_user_to_apply()
              tf_apply()
            }
          }
        """.stripIndent()

        tf_taint_job.with {
          parameters {
            stringParam 'taint_resource', '', 'Resource to taint such as aws_instance.foo, see https://www.terraform.io/docs/commands/taint.html'
            stringParam 'taint_module', '', 'Module to taint'
          }
        }

        def tf_untaint_job = tf_job 'UNTAINT', """
          withEnv(_env_vars) {
            node {
              git_checkout()
              prepare_workspace()
              setup_tf()
              tf_validate()
              fetch_modules()
              initialize_remote_state()
              tf_untaint(taint_resource, taint_module)
              tf_plan()
              if (slack_notifications) {
                notify_about_pending_changes()
              }
              wait_for_user_to_apply()
              tf_apply()
            }
          }
        """.stripIndent()

        tf_untaint_job.with {
          parameters {
            stringParam 'taint_resource', '', 'Resource to untaint such as aws_instance.foo, see https://www.terraform.io/docs/commands/taint.html'
            stringParam 'taint_module', '', 'Module to untaint'
          }
        }

      '''.stripIndent()
    }
  }
}