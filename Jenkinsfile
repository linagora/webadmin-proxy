pipeline {
    agent any

    tools {
        jdk 'jdk_25'
    }

    environment {
        DOCKER_HUB_CREDENTIAL = credentials('dockerHub')
    }

    options {
        timeout(time: 1, unit: 'HOURS')
        disableConcurrentBuilds()
    }

    stages {
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
                    sh 'docker build -t linagora/webadmin-proxy:latest .'
                    sh 'docker tag linagora/webadmin-proxy:latest linagora/webadmin-proxy:$DOCKER_TAG'
                    sh 'echo $DOCKER_HUB_CREDENTIAL_PSW | docker login -u $DOCKER_HUB_CREDENTIAL_USR --password-stdin'
                    sh 'docker push linagora/webadmin-proxy:$DOCKER_TAG'
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
