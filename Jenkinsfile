pipeline {
    agent any

    tools {
        jdk 'jdk_25'
    }

    // No pipeline-level credentials, see withGithubCredential and withDockerHubLogin.

    options {
        timeout(time: 1, unit: 'HOURS')
        disableConcurrentBuilds()
    }

    stages {
        stage('Authorize fork') {
            // A fork pull request runs no code of its own before an org member vouches for its head
            // commit, see authorizeFork
            when {
                changeRequest()
                expression { env.CHANGE_FORK != null }
            }
            steps {
                script {
                    authorizeFork()
                }
            }
        }
        stage('Build tmail backend first') {
            steps {
                sh 'mkdir .build'
                dir(".build") {
                    withCredentials([gitUsernamePassword(credentialsId: 'github')]) {
                        sh 'git clone https://github.com/linagora/tmail-backend.git'
                    }
                    dir("tmail-backend") {
                        sh 'git submodule init'
                        sh 'git submodule update'
                        sh 'mvn clean install -Dmaven.javadoc.skip=true -DskipTests -T1C'
                    }
                }
            }
        }
        stage('Compile') {
            steps {
                sh 'mvn clean install -Dmaven.javadoc.skip=true -DskipTests -T1C'
            }
        }
        stage('Test') {
            steps {
                sh 'mvn -B surefire:test'
            }
            post {
                always {
                    junit(testResults: '**/surefire-reports/*.xml', allowEmptyResults: false)
                }
                failure {
                    archiveArtifacts artifacts: '**/surefire-reports/*', fingerprint: true
                }
            }
        }
        stage('Deliver Docker image') {
            when {
                anyOf {
                    branch 'master'
                    buildingTag()
                }
            }
            steps {
                script {
                    env.DOCKER_TAG = 'branch-master'
                    if (env.TAG_NAME) {
                        env.DOCKER_TAG = env.TAG_NAME
                    }

                    echo "Docker tag: ${env.DOCKER_TAG}"

                    // Build the image from the Dockerfile using the artifacts produced
                    // by the Compile stage (multi-stage build copies them into the image;
                    // no Maven rebuild inside Docker). Replaces the former jib buildTar.
                    sh 'mvn -B dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dependency'
                    sh 'docker build --pull -t linagora/webadmin-proxy:$DOCKER_TAG -t linagora/webadmin-proxy:latest .'
                    withDockerHubLogin {
                        sh 'docker push linagora/webadmin-proxy:$DOCKER_TAG'
                    }
                }
            }
        }
    }
    post {
        always {
            deleteDir()
        }
    }
}

def githubRepository() {
  return 'linagora/webadmin-proxy'
}

// ---- Fork authorization and credentials. The same block is in the Jenkinsfiles of
// twake-contacts-frontend, twake-calendar-frontend, twake-calendar-side-service, webadmin-proxy,
// tmail-flutter and esn-sabre: change it in all of them. Only githubRepository() differs.
//
// No credential is bound at pipeline level: every stage runs code of the checked out sources (build
// plugins, package scripts, tests, scripts/), so secrets are bound only around the steps that need
// them, see withGithubCredential and withDockerHubLogin.

// A fork pull request runs no code of its own before an org member vouches for its head commit: the
// owner of the fork is a linagora member, or a member submitted a review on that very commit (an
// approval, or a review comment 'Build this please'). GitHub ties the review to that commit
// (commit_id), so a commit pushed after the approval is not built until a member reviews it again.
def authorizeFork() {
  withGithubCredential {
    // CHANGE_FORK is 'owner', or 'owner/repository' for a fork renamed on GitHub
    def forkOwner = env.CHANGE_FORK.tokenize('/')[0]
    if (isOrgMember(forkOwner)) {
      echo "Fork owner '${forkOwner}' is a linagora org member, proceeding."
      env.FORK_AUTHORIZED = 'true'
      return
    }
    def headSha = checkedOutPullRequestHead()
    def approver = memberReviewerOf(headSha)
    if (approver) {
      echo "Commit ${headSha} approved by linagora member '${approver}', proceeding."
      env.FORK_AUTHORIZED = 'true'
      return
    }
    error("Fork owner '${forkOwner}' is not a linagora org member and no member reviewed commit ${headSha}. " +
      "A linagora member must approve the pull request, or submit a review 'Build this please', " +
      "on this commit, then re-run the build.")
  }
}

// Whether the build may run code of the checked out sources, for the post actions that run even
// when the fork authorization failed
def mayRunChangeCode() {
  return env.CHANGE_FORK == null || env.FORK_AUTHORIZED == 'true'
}

// The head commit of the pull request, checked against the sources Jenkins checked out: HEAD itself,
// or the second parent of the merge commit Jenkins builds with the target branch
def checkedOutPullRequestHead() {
  def headSha = githubApi("pulls/${env.CHANGE_ID}").head.sha
  def checkedOut = sh(script: 'git rev-parse HEAD; git rev-parse -q --verify "HEAD^2" || true', returnStdout: true)
    .trim().tokenize('\n')
  if (!checkedOut.contains(headSha)) {
    error("The pull request head moved to ${headSha} since the checkout of ${checkedOut}: re-run the build.")
  }
  return headSha
}

// The login of a linagora member who approved the given commit, or reviewed it with 'Build this please'
def memberReviewerOf(String sha) {
  def reviews = githubApi("pulls/${env.CHANGE_ID}/reviews?per_page=100")
  for (review in reviews) {
    def approves = review.state == 'APPROVED' ||
      (review.state != 'DISMISSED' && "${review.body}".trim() == 'Build this please')
    if (review.commit_id == sha && approves && isOrgMember(review.user.login)) {
      return review.user.login
    }
  }
  return null
}

def isOrgMember(String login) {
  def status
  withEnv(["GITHUB_LOGIN=${login}"]) {
    status = sh(
      script: 'curl -s -o /dev/null -w "%{http_code}" -H "Authorization: token $GITHUB_CREDENTIAL_PSW" ' +
        '"https://api.github.com/orgs/linagora/members/$GITHUB_LOGIN"',
      returnStdout: true
    ).trim()
  }
  echo "GitHub org membership check returned HTTP ${status} for '${login}'"
  if (status == '204') {
    return true
  }
  if (status == '404') {
    return false
  }
  error("GitHub API error ${status} while checking the membership of '${login}'")
}

def githubApi(String path) {
  def json
  withEnv(["GITHUB_API_PATH=${path}", "GITHUB_API_REPOSITORY=${githubRepository()}"]) {
    json = sh(
      script: 'curl -sf -H "Authorization: token $GITHUB_CREDENTIAL_PSW" ' +
        '"https://api.github.com/repos/$GITHUB_API_REPOSITORY/$GITHUB_API_PATH"',
      returnStdout: true
    )
  }
  return readJSON(text: json)
}

// Binds the GitHub token as GITHUB_CREDENTIAL_USR / GITHUB_CREDENTIAL_PSW around the given steps only
def withGithubCredential(Closure body) {
  withCredentials([usernamePassword(credentialsId: 'github',
      usernameVariable: 'GITHUB_CREDENTIAL_USR', passwordVariable: 'GITHUB_CREDENTIAL_PSW')]) {
    body()
  }
}

// Logs in Docker Hub in a throwaway Docker config deleted afterwards, so that the credential never
// stays on the agent where a later build could read it
def withDockerHubLogin(Closure body) {
  withEnv(["DOCKER_CONFIG=${pwd(tmp: true)}/docker-config"]) {
    try {
      withCredentials([usernamePassword(credentialsId: 'dockerHub',
          usernameVariable: 'DOCKER_HUB_CREDENTIAL_USR', passwordVariable: 'DOCKER_HUB_CREDENTIAL_PSW')]) {
        sh 'mkdir -p "$DOCKER_CONFIG" && printf \'%s\' "$DOCKER_HUB_CREDENTIAL_PSW" | docker login -u "$DOCKER_HUB_CREDENTIAL_USR" --password-stdin'
      }
      body()
    } finally {
      sh 'docker logout || true; rm -rf "$DOCKER_CONFIG"'
    }
  }
}
