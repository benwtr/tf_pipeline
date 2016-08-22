# tf_pipeline Docker Demo/Quickstart

### It's assumed that you already have:
   * Basic knowledge of Terraform
   * Terraform code in a repo on GitHub
   * (Optional) You have Terraform modules in a GitHub repo or repos
   * An S3 bucket for
     [terraform remote state](https://www.terraform.io/docs/state/remote/index.html)
   * An AWS/IAM account with credentials (access_key_id, secret_access_key) that
     have read/write access for the remote state S3 bucket
   * The AWS account has rights for provisioning resources on AWS with Terraform
     _OR_ appropriate credentials and rights for using Terraform with some
     service other than AWS
   * Docker installed - either CLI or Docker Toolbox w/ Kitematic


### Steps

1. Pull and run the
   [benwtr/tf_pipeline](https://hub.docker.com/r/benwtr/tf_pipeline/) Docker
   image from Docker hub.

   In the Kitematic GUI, click **+New** and search for `benwtr/tf_pipeline` then
   click **create**
   
   _OR_ on the CLI:
   
        $ docker pull benwtr/tf_pipeline
        $ docker run -p8080:8080 benwtr/tf_pipeline
   
   This image is based on the _Official Jenkins Docker Image_, for details see
   https://github.com/jenkinsci/docker/ **Please note the path in this image
   where jenkins stores it's configuration and data is marked as a volume and
   will NOT persist across container restarts by default**


2. Open the containerized Jenkins instance in a browser. If you used Kitematic,
   click _Web Preview_. If the container was started by hand open
   http://localhost:8080

   
3. Get to the _Manage Jenkins -> Configure System_ page. eg,
   `http://<jenkins_url>/configure` or http://localhost:8080/configure
   
   Scroll to the _GitHub Plugin Configuration_ section, add a GitHub Server
   Config. (This is required for Pull Request polling to work).
   
   You'll need to add a new **Secret text** type credential, generated on
   https://github.com/settings/tokens with the _repo_ and _admin:repo_hook_
   scopes enabled. If your GitHub account does not have MFA enabled, the button
   under advanced can create the access token for you.
   
   Click the **Verify credentials** button. If it works, **Save** the Jenkins
   configuration.

   
4. Generate an SSH keypair and install the _public_ key in your GitHub account
   on the https://github.com/settings/keys page. You'll need the private key on
   the next step.
   
   Generally this is done with a command like `ssh-keygen -f jenkins_ssh_key` or
   you can take a look at GitHub's
   [guide on generating SSH keys](https://help.github.com/articles/generating-an-ssh-key/).


5. Return to the main Jenkins page, build **create_terraform_jobs** (with
   parameters). 
   
   Most of the parameters have detailed descriptions under them or are
   self-explanatory. If you enable Slack integration, you'll need to configure
   your team and key on Jenkins' _Configure System_ page.

   Add the _private_ ssh key created on the previous step under
   `gh_credentials_id`, of _Kind_ **SSH Username with private key**, _Username_
   should be **git**, select **Enter directly** and paste the key.
   
   In the `env_vars` field, enter your AWS credentials and any other environment
   variables you might need. This is **not** an ideal place for credentials,
   never do this in a production deployment. Anyone with access to the job can
   read this.
   
        AWS_ACCESS_KEY_ID=AKIA....
        AWS_SECRET_ACCESS_KEY=...
        AWS_REGION=us-east-1
        TF_VAR_ami_id=ami-84b92a92
   
   Click **Build**, Job-DSL should generate a series of nested folders and a set
   of Terraform jobs inside them. If you have Terraform code in multiple paths
   inside the repo (for example, `dev/`, `staging/`, `production/`) repeat this
   step for each path using the **Rebuild** button.


6. You're done. 

   You should be able to trigger a **PLAN** job by opening a pull request in
   your Terraform repo. Since this isn't an production Jenkins instance that you
   can reach from the internet it won't be able to receive webhooks from GitHub
   so you'll need to wait up to a full minute for Jenkins to poll and see the
   PR. If you're impatient, you can click the **GitHub PR** link in the sidebar
   on the **PLAN** job and use the options there.
   
   **APPLY** jobs will run automatically when code on the _master_ branch
   changes. Again, this only polls GitHub once per minute.

   For more usage info, see the README https://github.com/benwtr/tf_pipeline

