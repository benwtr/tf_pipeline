FROM jenkins

ARG user=jenkins

ENV JAVA_OPTS -Djenkins.install.runSetupWizard=false -Dhudson.TreeView=true -Dhudson.model.ParametersAction.keepUndefinedParameters=true

RUN /usr/local/bin/install-plugins.sh workflow-aggregator job-dsl github-pullrequest ssh-agent ansicolor cloudbees-folder slack rebuild readonly-parameters

RUN mkdir -p /usr/share/jenkins/ref/jobs/seed
COPY seed.xml /usr/share/jenkins/ref/jobs/seed/config.xml

USER root
RUN chown -R ${user} /usr/share/jenkins/ref
USER ${user}
