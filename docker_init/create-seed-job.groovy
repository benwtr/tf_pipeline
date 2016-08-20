#!groovy

// Based on https://github.com/thomasleveil/docker-jenkins-dsl-ready/blob/master/build/create-seed-job.groovy

import jenkins.model.*

def jobName = 'seed'

def configXml = '''\
  <?xml version='1.0' encoding='UTF-8'?>
  <project>
    <actions/>
    <description></description>
    <keepDependencies>false</keepDependencies>
    <scm class="hudson.plugins.git.GitSCM" plugin="git@2.5.3">
      <configVersion>2</configVersion>
      <userRemoteConfigs>
        <hudson.plugins.git.UserRemoteConfig>
          <url>https://github.com/benwtr/tf_pipeline.git</url>
        </hudson.plugins.git.UserRemoteConfig>
      </userRemoteConfigs>
      <branches>
        <hudson.plugins.git.BranchSpec>
          <name>*/master</name>
        </hudson.plugins.git.BranchSpec>
      </branches>
      <doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations>
      <submoduleCfg class="list"/>
      <extensions/>
    </scm>
    <canRoam>true</canRoam>
    <disabled>false</disabled>
    <blockBuildWhenDownstreamBuilding>false</blockBuildWhenDownstreamBuilding>
    <blockBuildWhenUpstreamBuilding>false</blockBuildWhenUpstreamBuilding>
    <triggers/>
    <concurrentBuild>false</concurrentBuild>
    <builders>
      <javaposse.jobdsl.plugin.ExecuteDslScripts plugin="job-dsl@1.48">
        <targets>seed.groovy</targets>
        <usingScriptText>false</usingScriptText>
        <ignoreExisting>false</ignoreExisting>
        <ignoreMissingFiles>false</ignoreMissingFiles>
        <removedJobAction>IGNORE</removedJobAction>
        <removedViewAction>IGNORE</removedViewAction>
        <lookupStrategy>JENKINS_ROOT</lookupStrategy>
        <additionalClasspath></additionalClasspath>
      </javaposse.jobdsl.plugin.ExecuteDslScripts>
    </builders>
    <publishers/>
    <buildWrappers/>
  </project>
'''.stripIndent()

if (!Jenkins.instance.getItem(jobName)) {
  def xmlStream = new ByteArrayInputStream( configXml.getBytes() )
  try {
    def seedJob = Jenkins.instance.createProjectFromXML(jobName, xmlStream)
    seedJob.scheduleBuild(0, null)
  } catch (ex) {
    println "ERROR: ${ex}"
    println configXml.stripIndent()
  }
}
