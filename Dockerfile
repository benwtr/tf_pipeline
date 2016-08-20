FROM jenkins

ARG user=jenkins

ENV JAVA_OPTS -Djenkins.install.runSetupWizard=false -Dhudson.TreeView=true -Dhudson.model.ParametersAction.keepUndefinedParameters=true

RUN /usr/local/bin/install-plugins.sh workflow-aggregator job-dsl github-pullrequest ssh-agent ansicolor cloudbees-folder slack rebuild readonly-parameters

COPY docker_init/create-seed-job.groovy /usr/share/jenkins/ref/init.groovy.d/create-seed-job.groovy

USER root
RUN chown -R ${user} /usr/share/jenkins/ref
USER ${user}
