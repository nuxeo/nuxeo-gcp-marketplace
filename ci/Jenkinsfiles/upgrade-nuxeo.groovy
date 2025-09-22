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
library identifier: "platform-ci-shared-library@v0.0.75"

boolean isMinorUpgrade(nuxeoCurrentVersion, nuxeoReleaseVersion) {
  return nxUtils.getMajorVersion(version: nuxeoReleaseVersion) == nxUtils.getMajorVersion(version: nuxeoCurrentVersion)
}

String getNextReleaseTrack(nuxeoCurrentVersion, nuxeoReleaseVersion, currentReleaseTrack) {
  def isMinorUpgrade = isMinorUpgrade(nuxeoCurrentVersion, nuxeoReleaseVersion)
  echo isMinorUpgrade ? "Minor upgrade" : "Major upgrade"
  return isMinorUpgrade
    ? nxUtils.getNextMajorDotMinorVersion(version: currentReleaseTrack) // e.g. 1.2
    : "${nxUtils.getMajorVersion(version: currentReleaseTrack).toInteger() + 1}.0" // e.g. 2.0
}

String getYamlValue(file, path) {
  container('base') {
    return sh(returnStdout: true, script: "yq read ${file} ${path}").trim()
  }
}

void setYamlValue(file, path, version) {
  container('base') {
    sh """
      yq write -i ${file} ${path} "${version}"
    """
  }
}

void addDockerTag(image, tag) {
  sh "gcloud artifacts docker tags add ${image} ${tag}"
}

def createJiraIssue(nuxeoVersion, fixVersion) {
  def issue = [fields: [
    project: ['key': 'NXP'],
    issuetype: ['name': 'Task'],
    summary: "Upgrade Nuxeo to ${nuxeoVersion} in K8s application for Google Cloud Marketplace",
    description: """
Following the Nuxeo LTS release ${nuxeoVersion}, let's upgrade the Nuxeo Docker image to this version in the
K8s application for Google Cloud Marketplace.
""".trim(),
    components: [['name': 'Cloud']],
    customfield_13956: ['nxplatform'], // Tags
    customfield_10104: 16458, // Sprint: nxplatform next
    customfield_10106: 2, // Story Points
    fixVersions: [['name': fixVersion]],
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
  environment {
    DEPLOYER_SCHEMA = 'deployer/schema.yaml'
    DEPLOYER_CHART_DIR = 'deployer/chart/nuxeo-mp'
    DEPLOYER_CHART = "${DEPLOYER_CHART_DIR}/Chart.yaml"
    DEPLOYER_VALUES = "${DEPLOYER_CHART_DIR}/values.yaml"
    TESTER_CHART_DIR = 'deployer/tester/chart/nuxeo-mp-test'
    TESTER_CHART = "${TESTER_CHART_DIR}/Chart.yaml"
    TESTER_VALUES = "${TESTER_CHART_DIR}/values.yaml"
    ARTIFACT_REGISTRY = 'us-docker.pkg.dev'
    REPOSITORY_STAGING = "${ARTIFACT_REGISTRY}/hyl-is-marketplace/nuxeo"
    IMAGE_NUXEO = 'nuxeo'
    IMAGE_UBBAGENT = 'ubbagent'
    GCP_MARKETPLACE_ANNOTATION_KEY = 'com.googleapis.cloudmarketplace.product.service.name'
    GCP_MARKETPLACE_ANNOTATION_VALUE = 'services/nuxeo.endpoints.hyl-is-marketplace.cloud.goog'
    NUXEO_CURRENT_VERSION = getYamlValue(DEPLOYER_CHART, 'appVersion') // e.g. 2025.5
    NUXEO_RELEASE_VERSION = "${params.NUXEO_RELEASE_VERSION}" // e.g. 2025.6
    DEPLOYER_CURRENT_VERSION = getYamlValue(DEPLOYER_CHART, 'version') // e.g. 1.1-SNAPSHOT
    CURRENT_RELEASE_TRACK = DEPLOYER_CURRENT_VERSION.replace('-SNAPSHOT', '') // e.g. 1.1
    NEXT_RELEASE_TRACK = getNextReleaseTrack(NUXEO_CURRENT_VERSION, NUXEO_RELEASE_VERSION, CURRENT_RELEASE_TRACK)
    DEPLOYER_NEXT_VERSION = "${NEXT_RELEASE_TRACK}-SNAPSHOT" // e.g. 1.2-SNAPSHOT or 2.0-SNAPSHOT
    JIRA_BROWSE_URL = nxJira.getServerBrowseURL()
    JIRA_MOVING_VERSION = 'gcp-marketplace-next'
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
    stage('Set staging images') {
      when {
        expression { !nxUtils.isDryRun() }
      }
      steps {
        container('base') {
          script {
            echo """
            ----------------------------------------
            Set nuxeo and ubbagent images in staging repository
            - Current release track: ${CURRENT_RELEASE_TRACK}
            - Next release track: ${NEXT_RELEASE_TRACK}
            - Nuxeo release version: ${NUXEO_RELEASE_VERSION}
            ----------------------------------------""".stripIndent()
            // Copy Nuxeo release image from private to staging repository, tagging it with next release track
            def nuxeoImage = "${REPOSITORY_STAGING}/${IMAGE_NUXEO}:${NEXT_RELEASE_TRACK}"
            nxDocker.copy(
              from: "${PRIVATE_DOCKER_REGISTRY}/nuxeo/${IMAGE_NUXEO}:${NUXEO_RELEASE_VERSION}",
              to: "${nuxeoImage}"
            )

            // Add annotation required by GCP Marketplace to Nuxeo image
            sh """
              crane mutate \
                --annotation ${GCP_MARKETPLACE_ANNOTATION_KEY}=${GCP_MARKETPLACE_ANNOTATION_VALUE} \
                ${nuxeoImage}
            """

            // Delete stale image without annotation nor tag
            def imageDigest = sh(
              returnStdout: true,
              script: """
                gcloud artifacts docker images list ${REPOSITORY_STAGING}/${IMAGE_NUXEO} \
                  --include-tags \
                  --filter='-tags:*' \
                  --format 'value(version)'
              """
            )
            sh "gcloud -q artifacts docker images delete ${REPOSITORY_STAGING}/${IMAGE_NUXEO}@${imageDigest}"

            // Check annotation
            def annotation = sh(
              returnStdout: true,
              script: """
                docker buildx imagetools inspect ${nuxeoImage} --raw \
                  | jq -r '.annotations."${GCP_MARKETPLACE_ANNOTATION_KEY}"'
              """
            ).trim()
            if (annotation != GCP_MARKETPLACE_ANNOTATION_VALUE) {
              error """
                Incorrect annotation ${GCP_MARKETPLACE_ANNOTATION_KEY}=${GCP_MARKETPLACE_ANNOTATION_VALUE} on Nuxeo image: ${nuxeoImage}
              """
            }

            // Tag ubbagent image on staging repository with next release track
            addDockerTag(
              "${REPOSITORY_STAGING}/${IMAGE_UBBAGENT}:${CURRENT_RELEASE_TRACK}",
              "${REPOSITORY_STAGING}/${IMAGE_UBBAGENT}:${NEXT_RELEASE_TRACK}"
            )
          }
        }
      }
    }
    stage('Bump versions') {
      steps {
        container('base') {
          script {
            echo """
            ----------------------------------------
            Bump Nuxeo version
            - Current version: ${NUXEO_CURRENT_VERSION}
            - New version: ${NUXEO_RELEASE_VERSION}

            Bump deployer version
            - Current version: ${DEPLOYER_CURRENT_VERSION}
            - New version: ${DEPLOYER_NEXT_VERSION}
            ----------------------------------------
            """.stripIndent()
            setYamlValue(DEPLOYER_SCHEMA, 'x-google-marketplace.publishedVersion', DEPLOYER_NEXT_VERSION)
            setYamlValue(DEPLOYER_CHART, 'version', DEPLOYER_NEXT_VERSION)
            setYamlValue(DEPLOYER_VALUES, 'nuxeo.image.tag', NEXT_RELEASE_TRACK)
            setYamlValue(TESTER_CHART, 'version', DEPLOYER_NEXT_VERSION)
            setYamlValue(TESTER_VALUES, 'tester.image.tag', NEXT_RELEASE_TRACK)
            setYamlValue(DEPLOYER_CHART, 'appVersion', NUXEO_RELEASE_VERSION)
            setYamlValue(
              DEPLOYER_SCHEMA,
              'x-google-marketplace.publishedVersionMetadata.releaseNote',
              "Upgrade Nuxeo Docker image to ${NUXEO_RELEASE_VERSION}."
            )

            def issueKey = createJiraIssue(NUXEO_RELEASE_VERSION, JIRA_MOVING_VERSION)

            def currentBranch = GIT_BRANCH.split('/')[1]
            sh "git checkout ${currentBranch}"
            nxGit.commitPush(
              branch: currentBranch,
              message: "${issueKey}: Upgrade Nuxeo from ${NUXEO_CURRENT_VERSION} to ${NUXEO_RELEASE_VERSION}, update ${DEPLOYER_CURRENT_VERSION} to ${DEPLOYER_NEXT_VERSION}"
            )

            // Set Jira issue Release Notes Summary
            def issue = [fields: [
              customfield_13954: "Nuxeo was upgraded to ${NUXEO_RELEASE_VERSION} in the K8s application for Google Cloud Marketplace",
            ]]
            nxJira.editIssue(idOrKey: issueKey, issue: issue)

            // Mark Jira issue as Resolved
            nxJira.followIssueTransition(
              idOrKey: issueKey,
              input: [
                transition: [id: '5']
              ]
            )
          }
        }
      }
    }
  }

  post {
    always {
      script {
        nxUtils.setDescription(
          version: NUXEO_RELEASE_VERSION,
          default: "Upgrade to ${NUXEO_RELEASE_VERSION}",
        )
      }
    }
    unsuccessful {
      script {
        nxTeams.error(
          subtitle: null,
          message: "Failed to upgrade Nuxeo to ${NUXEO_RELEASE_VERSION} in the K8s application for Google Cloud Marketplace",
          changes: true,
          culprits: true,
        )
      }
    }
  }
}
