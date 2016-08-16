# tf_pipeline

## Usage

### PLAN

PLAN can only run from a pull request against the target branch (currently
hard-coded to `master`). It's triggered by opening pull requests, pushing
changes to pull requests, or adding a comment with the body 'replan' on a pull
request. If it runs successfully, it will add the PLAN output as a comment on
the PR.

You do **not** need to set up terraform locally with this workflow, and in fact
you should not use remote state from your local workstation. However, you may
want to run `terraform validate` for a syntax check before pushing code.

The **GitHub PR** option in the sidebar on the PLAN job page has options for
re-triggering jobs if you need to re-kick them.

### APPLY

APPLY is configured to watch for changes merged or pushed to the `master` branch
and then wait indefinitely for a user to confirm or abort the change before
actually applying it. Since this job is not permitted to run in parallel, this
implements locking for your team!

APPLY can be invoked manually and also polls github every minute.

### Other jobs: TAINT, UNTAINT, DESTROY

Mostly self-explanatory, see the terraform documentation and read the
operational notes below


## Installation

1. Ensure all the requirements are fulfilled and plugins are installed. Update
   all plugins including ones not mentioned here to their latest versions.

2. Create a new freestyle job, give it a name such as **tf_pipeline_seed**, add
   a build step: _Process job DSLs_, select _Use the provided DSL script_ and
   paste in the content of `seed.groovy` OR set the job up to clone **this**
   repo and select _Look on Filesystem_ and input `seed.groovy` in the _DSL
   Scripts_ field.

3. Build the job. It should create a new job called _create_terraform_jobs_. You
   can now delete the seed job if you want.

4. Build _create_terraform_jobs_ (with parameters), the parameters have detailed
   descriptions that should serve as a guide.
   
5. A series of nested folders will be created for the owner and repo, containing
   jobs for running terraform actions.


## Requirements

  * A github account for a jenkins/bot user
  * An ssh key, in the github account and jenkins credential store, and the
    **ID** that jenkins stored it under
  * An access token for the github user, also in the jenkins credential store as
    a **Secret text** type credential, it should have permission to manage
    webhooks, set up the GitHub section of the _Configure System_ page with this
    credential
  * Terraform code for your infrastructure in a github repo, subdirs or root of
    the repo are both supported
  * If you intend to use the slack notification feature, the team and
    integration token must be configured on the jenkins _Configure System_ page

### Required Jenkins Plugins

  * Pipeline
  * Job-DSL
  * Github Pull Request (*not* github pull request builder)
  * SSH Agent
  * AnsiColor
  * Folders
  * Slack Notification
  * Rebuilder
  * Readonly Parameter


## Operational Notes

  * For AWS auth, use environment variables, IAM instance role,
    ~/.aws/credentials, or env_vars parameter
  * S3 is used for remote state but it should be straightforward to swap out for
    consul etc
  * Be extremely careful not to clobber your remote state on S3
  * Enable bucket versioning in case you do
  * If you use multiple environments under different paths in the github repo,
    make sure they each have their own state
  * The ACL used for S3 is fairly open so that it's possible to read state
    across environments
  * See https://github.com/KostyaSha/github-integration-plugin/wiki/FAQ if you
    experience issues triggering _PLAN_ jobs via pull request due to
    `$GITHUB_PR_NUMBER` not being set


## Known Issues

  * It's quite noisy (todo: don't prompt if there are no changes to apply, and
    maybe just output "No changes" instead of the full plan output on PRs)
  * While the APPLY job is not permitted to run in parallel, effectively
    implementing a lock for multiple users working on an infrastructure, the
    TAINT and UNTAINT jobs are not. Be careful. (todo: prevent these jobs from
    running in parallel)
  
