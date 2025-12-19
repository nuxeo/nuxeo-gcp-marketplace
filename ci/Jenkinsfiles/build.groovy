/*
* (C) Copyright 2025 Nuxeo (http://nuxeo.com/) and others.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
* Contributors:
*     Antoine Taillefer <antoine.taillefer@hyland.com>
*/
import java.time.LocalDate
import java.time.format.DateTimeFormatter

library identifier: "platform-ci-shared-library@v0.0.75"

REPOSITORY = 'nuxeo-gcp-marketplace'

String getYamlVersion(file, path) {
  container('base') {
    return sh(returnStdout: true, script: "yq read ${file} ${path}").trim()
  }
}

void setYamlVersion(file, path, version) {
  container('base') {
    sh "yq write -i ${file} ${path} ${version}"
  }
}

String getMpdevExtraDockerParams() {
  container('base') {
    script {
      def kubernetesEnv = sh(returnStdout: true, script: "env | grep KUBERNETES | sed 's/.*/-e &/'")
      def kubernetesSAMount = '''
        --mount type=bind,source=/var/run/secrets/kubernetes.io/serviceaccount,target=/var/run/secrets/kubernetes.io/serviceaccount,readonly
      '''.stripIndent()
      return "${kubernetesEnv} ${kubernetesSAMount}"
    }
  }
}

void addDockerTag(image, tag) {
  sh "gcloud artifacts docker tags add ${image} ${tag}"
}

def createJiraIssue(nuxeoVersion, newRelease) {
  def issue = [fields: [
    project: ['key': 'SUPINT'],
    issuetype: ['name': 'Task'],
    summary: "Upgrade Nuxeo to LTS ${nuxeoVersion} in the Google Cloud Marketplace",
    description: """
Please follow the documentation about [Updating an existing version|${GCP_MARKETPLACE_UPDATE_DOC_URL}].

Direct link to the *Container images* section: https://console.cloud.google.com/producer-portal/listing-edit/nuxeo.endpoints.hyl-is-marketplace.cloud.goog;stepId=kubernetesImages

Update the Display Tag of the current release to the ${newRelease} release.
""".trim(),
    components: [['name': 'Cloud']],
    customfield_13956: ['nxplatform'], // Tags
    customfield_10104: 16458, // Sprint: nxplatform next
    customfield_10106: 2, // Story Points
  ]]
  def response = nxJira.newIssue(issue: issue)
  def key = response.data.key
  echo "JIRA issue created: ${JIRA_BROWSE_URL}${key}"
  return key
}

pipeline {
  agent {
    label 'jenkins-nuxeo-gcp'
  }
  options {
    buildDiscarder(logRotator(daysToKeepStr: '60', numToKeepStr: '60', artifactNumToKeepStr: '5'))
    disableConcurrentBuilds(abortPrevious: true)
    githubProjectProperty(projectUrlStr: "https://github.com/nuxeo/${REPOSITORY}")
  }
  environment {
    NUXEO_CHART = 'nuxeo'
    NUXEO_CHART_REPOSITORY = 'https://packages.nuxeo.com/repository/helm-releases-public/'
    LOG_DIR_MPDEV_DOCTOR = '.mpdev_doctor_logs'
    LOG_DIR_MPDEV_VERIFY = '.mpdev_verify_logs'
    DEPLOYER_SCHEMA = 'deployer/schema.yaml'
    DEPLOYER_CHART_DIR = 'deployer/chart/nuxeo-mp'
    DEPLOYER_CHART = "${DEPLOYER_CHART_DIR}/Chart.yaml"
    DEPLOYER_VALUES = "${DEPLOYER_CHART_DIR}/values.yaml"
    TESTER_CHART_DIR = 'deployer/tester/chart/nuxeo-mp-test'
    TESTER_CHART = "${TESTER_CHART_DIR}/Chart.yaml"
    TESTER_VALUES = "${TESTER_CHART_DIR}/values.yaml"
    ARTIFACT_REGISTRY = 'us-docker.pkg.dev'
    REPOSITORY_TEST = "${ARTIFACT_REGISTRY}/jx-preprod/nuxeo"
    REPOSITORY_STAGING = "${ARTIFACT_REGISTRY}/hyl-is-marketplace/nuxeo"
    IMAGE_DEPLOYER = 'deployer'
    IMAGE_TESTER = 'tester'
    IMAGE_NUXEO = 'nuxeo'
    IMAGE_UBBAGENT = 'ubbagent'
    GCP_MARKETPLACE_ANNOTATION_KEY = 'com.googleapis.cloudmarketplace.product.service.name'
    GCP_MARKETPLACE_ANNOTATION_VALUE = 'services/nuxeo.endpoints.hyl-is-marketplace.cloud.goog'
    GCP_MARKETPLACE_URL = 'https://console.cloud.google.com/marketplace/product/hyl-is-marketplace/nuxeo'
    GCP_MARKETPLACE_UPDATE_DOC_URL = 'https://cloud.google.com/marketplace/docs/partners/kubernetes/maintaining-product#updating_an_existing_version'
    CURRENT_VERSION = getYamlVersion(DEPLOYER_CHART, 'version') // e.g. 1.1-SNAPSHOT
    VERSION = nxUtils.getVersion(baseVersion: CURRENT_VERSION) // e.g. 1.1.1
    MINOR_VERSION = nxUtils.getMajorDotMinorVersion(version: VERSION) // e.g. 1.1
    NUXEO_VERSION = getYamlVersion(DEPLOYER_CHART, 'appVersion') // e.g. 2025.5
    // To allow running kubectl inside the mpdev container, we need to pass the Kubernetes environment variables
    // and mount its service account secret
    EXTRA_DOCKER_PARAMS = getMpdevExtraDockerParams()
    JIRA_BROWSE_URL = nxJira.getServerBrowseURL()
    JIRA_PROJECT = 'NXP'
    JIRA_MOVING_VERSION = 'gcp-marketplace-next'
    JIRA_RELEASED_VERSION = "gcp-marketplace-${VERSION}"
  }
  stages {
    stage('Set labels') {
      steps {
        container('base') {
          script {
            nxK8s.setPodLabels()
          }
        }
      }
    }
    stage('Bump version') {
      steps {
        container('base') {
          nxWithGitHubStatus(context: 'bump', message: 'Bump version') {
            echo """
            ----------------------------------------
            Bump version
            - Current version: ${CURRENT_VERSION}
            - New version: ${VERSION}

            Other versions set in environment
            - Minor version: ${MINOR_VERSION}
            - Nuxeo version: ${NUXEO_VERSION}
            ----------------------------------------
            """.stripIndent()
            setYamlVersion(DEPLOYER_SCHEMA, 'x-google-marketplace.publishedVersion', VERSION)
            setYamlVersion(DEPLOYER_CHART, 'version', VERSION)
            setYamlVersion(DEPLOYER_VALUES, 'nuxeo.image.tag', VERSION)
            setYamlVersion(DEPLOYER_VALUES, 'nuxeo.ubbagent.image.tag', VERSION)
            setYamlVersion(TESTER_CHART, 'version', VERSION)
            setYamlVersion(TESTER_VALUES, 'tester.image.tag', VERSION)
          }
        }
      }
    }
    stage('Build') {
      steps {
        container('base') {
          nxWithGitHubStatus(context: 'build', message: 'Build Docker images') {
            echo """
            ----------------------------------------
            Build
            ----------------------------------------
            """.stripIndent()
            sh "helm repo add ${NUXEO_CHART} ${NUXEO_CHART_REPOSITORY}"
            sh "helm dependency build ${DEPLOYER_CHART_DIR}"
            sh "skaffold build --default-repo=${REPOSITORY_TEST}"
          }
        }
      }
    }
    stage('Check mpdev') {
      environment {
        // Required for the mpdev bind mounts to work
        HOME = '/home/jenkins'
        VERIFICATION_LOGS_PATH = "${HOME}/${LOG_DIR_MPDEV_DOCTOR}"
      }
      steps {
        container('base') {
          nxWithGitHubStatus(context: 'check-mpdev', message: 'Run mpdev doctor') {
            echo """
            ----------------------------------------
            Check mpdev
            ----------------------------------------
            """.stripIndent()
            sh 'mpdev doctor'
          }
        }
      }
      post {
        always {
          dir(HOME) {
            archiveArtifacts artifacts: "${LOG_DIR_MPDEV_DOCTOR}/**"
          }
        }
      }
    }
    stage('Test') {
      environment {
        // Required for the mpdev bind mounts to work
        HOME = '/home/jenkins'
        VERIFICATION_LOGS_PATH = "${HOME}/${LOG_DIR_MPDEV_VERIFY}"
      }
      steps {
        container('base') {
          nxWithGitHubStatus(context: 'test', message: 'Run verification tests') {
            script {
              echo """
              ----------------------------------------
              Test
              ----------------------------------------
              """.stripIndent()
              // Copy latest release track version of nuxeo and ubbagent images from staging to test registry
              nxDocker.copy(
                from: "${REPOSITORY_STAGING}/${IMAGE_NUXEO}:${MINOR_VERSION}",
                to: "${REPOSITORY_TEST}/${IMAGE_NUXEO}:${VERSION}"
              )
              nxDocker.copy(
                from: "${REPOSITORY_STAGING}/${IMAGE_UBBAGENT}:${MINOR_VERSION}",
                to: "${REPOSITORY_TEST}/${IMAGE_UBBAGENT}:${VERSION}"
              )

              // To allow mpdev to pull images from the "us-docker.pkg.dev" artifact registry, we need to configure
              // gcloud as a Docker credential helper for this registry
              sh """
                mpdev /bin/bash -c "\
                  gcloud -q auth configure-docker ${ARTIFACT_REGISTRY}; \
                  verify --deployer=${REPOSITORY_TEST}/${IMAGE_DEPLOYER}:${VERSION} \
                "
              """
            }
          }
        }
      }
      post {
        always {
          dir(HOME) {
            archiveArtifacts artifacts: "${LOG_DIR_MPDEV_VERIFY}/**"
          }
        }
      }
    }
    stage('Git tag') {
      when {
        expression { !nxUtils.isPullRequest() }
      }
      steps {
        container('base') {
          script {
            echo """
            ----------------------------------------
            Git commit, tag and push
            ----------------------------------------
            """.stripIndent()
            nxGit.commitTagPush()
          }
        }
      }
    }
    stage('Deploy/promote') {
      when {
        expression { nxUtils.isNotPullRequestAndNotDryRun() }
      }
      steps {
        container('base') {
          nxWithGitHubStatus(context: 'deploy', message: 'Deploy/promote Docker images') {
            script {
              echo """
              ----------------------------------------
              Deploy/promote Docker images
              ----------------------------------------
              """.stripIndent()
              // Copy build version of deployer and tester images from test to staging registry
              nxDocker.copy(
                from: "${REPOSITORY_TEST}/${IMAGE_DEPLOYER}:${VERSION}",
                to: "${REPOSITORY_STAGING}/${IMAGE_DEPLOYER}:${VERSION}"
              )
              nxDocker.copy(
                from: "${REPOSITORY_TEST}/${IMAGE_TESTER}:${VERSION}",
                to: "${REPOSITORY_STAGING}/${IMAGE_TESTER}:${VERSION}"
              )

              // Promote build version to release track
              // e.g. add 1.1 to deployer:1.1.1
              addDockerTag(
                "${REPOSITORY_STAGING}/${IMAGE_DEPLOYER}:${VERSION}",
                "${REPOSITORY_STAGING}/${IMAGE_DEPLOYER}:${MINOR_VERSION}"
              )
              // e.g. add 1.1 to tester:1.1.1
              addDockerTag(
                "${REPOSITORY_STAGING}/${IMAGE_TESTER}:${VERSION}",
                "${REPOSITORY_STAGING}/${IMAGE_TESTER}:${MINOR_VERSION}"
              )
              // e.g. add 1.1.1 to nuxeo:1.1
              addDockerTag(
                "${REPOSITORY_STAGING}/${IMAGE_NUXEO}:${MINOR_VERSION}",
                "${REPOSITORY_STAGING}/${IMAGE_NUXEO}:${VERSION}"
              )
              // e.g. add 1.1.1 to ubbagent:1.1
              addDockerTag(
                "${REPOSITORY_STAGING}/${IMAGE_UBBAGENT}:${MINOR_VERSION}",
                "${REPOSITORY_STAGING}/${IMAGE_UBBAGENT}:${VERSION}"
              )
            }
          }
        }
      }
    }
    stage('Marketplace update') {
      when {
        expression { !nxUtils.isPullRequest() }
      }
      steps {
        container('base') {
          nxWithGitHubStatus(context: 'update-marketplace', message: 'Update Marketplace') {
            script {
              echo """
              ----------------------------------------
              Marketplace update
              ----------------------------------------
              """.stripIndent()
              def patchVersion = sh(returnStdout: true, script: "semver get patch ${VERSION}").trim()
              echo "Patch version number: ${patchVersion}"
              if (patchVersion == '0') { // e.g. 1.2.0 or 2.0.0
                echo """
                  Minor or major version, create task to upgrade Nuxeo to LTS ${NUXEO_VERSION} in the Google Cloud
                  Marketplace
                """.stripIndent()
                def issueKey = createJiraIssue(NUXEO_VERSION, MINOR_VERSION)
                def issueUrl = "${JIRA_BROWSE_URL}${issueKey}"
                env.NOTIFICATION_MESSAGE = """
Make sure to address [${issueKey}](${issueUrl}) to upgrade Nuxeo to LTS
${NUXEO_VERSION} in the [Google Cloud Marketplace](${GCP_MARKETPLACE_URL}).
""".trim()
                env.NOTIFICATION_ACTION_NAME = 'View Jira issue'
                env.NOTIFICATION_ACTION_URL = issueUrl
              } else { // e.g. 1.1.1
                echo 'Patch version'
                env.NOTIFICATION_MESSAGE = """
To upgrade the Nuxeo software to this patch version in the [Google Cloud Marketplace](${GCP_MARKETPLACE_URL}),
please follow the documentation about
[Updating an existing version](${GCP_MARKETPLACE_UPDATE_DOC_URL}).

Update the Display Tag of the current release to the new Deployer digest for the same release.
""".trim()
                env.NOTIFICATION_ACTION_NAME = 'View documentation'
                env.NOTIFICATION_ACTION_URL = GCP_MARKETPLACE_UPDATE_DOC_URL
              }
            }
          }
        }
      }
    }
    stage('Release') {
      when {
        expression { !nxUtils.isPullRequest() }
      }
      steps {
        container('base') {
          nxWithGitHubStatus(context: 'release', message: 'Release project') {
            script {
              echo """
              ----------------------------------------
              Release
              ----------------------------------------
              """.stripIndent()
              def issueFetchers = [
                [
                  type: 'github_dependabot',
                ],
                [
                  type          : 'jira',
                  jql           : "project = ${JIRA_PROJECT} and fixVersion = ${JIRA_MOVING_VERSION}",
                  computeCommits: true,
                ],
              ]
              nxProject.release(
                issuesFetchers: issueFetchers,
                newJiraVersion: [
                  project    : env.JIRA_PROJECT,
                  name       : env.JIRA_RELEASED_VERSION,
                  description: "Nuxeo GCP Marketplace ${VERSION}",
                  releaseDate: LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
                  released   : true,
                ],
                jiraMovingVersionName: env.JIRA_MOVING_VERSION,
              )
            }
          }
        }
      }
    }
  }
  post {
    always {
      script {
        if (nxUtils.isPullRequest()) {
          nxUtils.setBuildDescription()
        } else {
          nxUtils.setReleaseDescription()
          nxJira.updateIssues()
        }
      }
    }
    success {
      script {
        if (!nxUtils.isPullRequest()) {
          def message = "Successfully released ${REPOSITORY} ${VERSION}"
          if (env.NOTIFICATION_MESSAGE) {
            message += """

${NOTIFICATION_MESSAGE}"""
          }
          nxTeams.success(
            subtitle: null,
            message: message,
            changes: true,
            actions: [[
              name: 'View build',
              url: RUN_DISPLAY_URL
            ], [
              name: NOTIFICATION_ACTION_NAME,
              url: NOTIFICATION_ACTION_URL
            ]],
          )
        }
      }
    }
    unsuccessful {
      script {
        if (!nxUtils.isPullRequest()) {
          nxTeams.error(
            subtitle: null,
            message: "Failed to release ${REPOSITORY} ${VERSION}",
            changes: true,
            culprits: true,
          )
        }
      }
    }
  }
}
