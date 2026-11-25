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
// tmail-flutter, esn-sabre, tmail-backend and twake-mail-admin: change it in all of them. Only
// githubRepository() differs.
//
// No credential is bound at pipeline level: every stage runs code of the checked out sources (build
// plugins, package scripts, tests, scripts/), so secrets are bound only around the steps that need
// them, see withGithubCredential and withDockerHubLogin.

// A fork pull request runs no code of its own before an org member vouches for its head commit: the
// owner of the fork is a linagora member, or a member approved that very commit (see
// memberReviewerOf). An approval names one commit, so a commit pushed after it is not built until a
// member approves it again.
def authorizeFork() {
  withGithubCredential {
    // CHANGE_FORK is 'owner', or 'owner/repository' for a fork renamed on GitHub
    def forkOwner = env.CHANGE_FORK.tokenize('/')[0]
    // Jenkins may set CHANGE_FORK to 'linagora' for a branch of the repository itself: only someone
    // with write access pushes there, and an organization is not a member of itself.
    if (forkOwner == 'linagora' || isOrgMember(forkOwner)) {
      echo "Fork owner '${forkOwner}' is a linagora org member, proceeding."
      env.FORK_AUTHORIZED = 'true'
      return
    }
    def headSha = pullRequestHead()
    def approver = memberReviewerOf(headSha)
    if (approver) {
      echo "Commit ${headSha} approved by linagora member '${approver}', proceeding."
      checkOutApproved(headSha)
      env.FORK_AUTHORIZED = 'true'
      return
    }
    error("Fork owner '${forkOwner}' is not a linagora org member and no member reviewed commit ${headSha}. " +
      "A linagora member must approve the pull request on this commit, or comment 'Build this please ${headSha}', " +
      "then re-run the build.")
  }
}

// Whether the build may run code of the checked out sources, for the post actions that run even
// when the fork authorization failed
def mayRunChangeCode() {
  return env.CHANGE_FORK == null || env.FORK_AUTHORIZED == 'true'
}

// The head commit of the pull request, as GitHub reports it now. It need not be the commit Jenkins
// checked out, and nothing in the workspace proves which one it is: the contributor can push between
// the checkout and this call, and chooses the parents of the commits they push. The build runs this
// commit instead, see checkOutApproved.
def pullRequestHead() {
  return githubApi("pulls/${env.CHANGE_ID}").head.sha
}

// Replaces the checked out sources with the approved commit, before any code of the pull request runs,
// so that what is built is what a member vouched for, however the pull request head moved. The commit is
// merged with the target branch when Jenkins fetched it from this repository, as a merge build does. The
// commit status Jenkins reports still names the revision it checked out.
def checkOutApproved(String sha) {
  withEnv(["APPROVED_SHA=${sha}"]) {
    sh '''
      head=$(git rev-parse HEAD)
      if [ "$head" = "$APPROVED_SHA" ]; then
        exit 0
      fi
      if ! git cat-file -e "$APPROVED_SHA^{commit}" 2>/dev/null; then
        echo "The pull request head moved to $APPROVED_SHA since the checkout of $head: re-run the build." >&2
        exit 1
      fi
      target=$(git rev-parse -q --verify "refs/remotes/origin/$CHANGE_TARGET^{commit}" || true)
      echo "Building the approved commit $APPROVED_SHA instead of the checked out $head"
      git checkout -q --force --detach "$APPROVED_SHA"
      git clean -q -ffdx
      if [ -n "$target" ]; then
        git -c user.name=Jenkins -c user.email=jenkins@localhost merge -q --no-edit "$target"
      fi
    '''
  }
}

// The login of a linagora member who vouched for the given commit: an approving review of it, a review
// 'Build this please' of it, or a comment 'Build this please <sha>' naming its full id. A prefix is not
// accepted: the contributor writes the commits, and can craft one sharing a short prefix with the
// reviewed one. GitHub lists reviews and comments oldest first, 100 per page at most: every page is read.
def memberReviewerOf(String sha) {
  def page = 1
  while (true) {
    def reviews = githubApi("pulls/${env.CHANGE_ID}/reviews?per_page=100&page=${page}")
    for (review in reviews) {
      def approves = review.state == 'APPROVED' ||
        (review.state != 'DISMISSED' && "${review.body}".trim() == 'Build this please')
      if (review.commit_id == sha && approves && isOrgMember(review.user.login)) {
        return review.user.login
      }
    }
    if (reviews.size() < 100) {
      break
    }
    page++
  }
  def approval = "build this please ${sha}".toString()
  page = 1
  while (true) {
    def comments = githubApi("issues/${env.CHANGE_ID}/comments?per_page=100&page=${page}")
    for (comment in comments) {
      def body = "${comment.body}".trim().toLowerCase().replaceAll('\\s+', ' ')
      if (body == approval && isOrgMember(comment.user.login)) {
        return comment.user.login
      }
    }
    if (comments.size() < 100) {
      return null
    }
    page++
  }
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
