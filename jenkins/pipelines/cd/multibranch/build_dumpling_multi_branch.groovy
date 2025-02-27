def BUILD_URL = 'git@github.com:pingcap/dumpling.git'

def build_path = 'go/src/github.com/pingcap/dumpling'
def slackcolor = 'good'
def githash
def branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"
def plugin_branch = branch

try {
    node("${GO_BUILD_SLAVE}") {
        container("golang") {
            def ws = pwd()

            stage("Debug Info"){
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            }

            stage("Checkout") {
                dir(build_path) {
                    deleteDir()
                    // 如果不是 TAG，直接传 branch 给下面的 checkout 语句； 否则就应该 checkout 到 refs/tags 下 .
                    // 值得注意的是，即使传入的是 TAG，环境变量里的 BRANCH_NAME 和 TAG_NAME 同时会是 TAG 名，如 v3.0.0
                    println branch
                    retry(3) {
                        if(branch.startsWith("refs/tags")) {
                            checkout changelog: false,
                                    poll: true,
                                    scm: [$class: 'GitSCM',
                                            branches: [[name: branch]],
                                            doGenerateSubmoduleConfigurations: false,
                                            extensions: [[$class: 'CheckoutOption', timeout: 30],
                                                        [$class: 'LocalBranch'],
                                                        [$class: 'CloneOption', noTags: true, timeout: 60]],
                                            submoduleCfg: [],
                                            userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                                                                refspec: "+${branch}:${branch}",
                                                                url: 'git@github.com:pingcap/dumpling.git']]
                                    ]
                        } else {
                            checkout scm: [$class: 'GitSCM', 
                                branches: [[name: branch]],  
                                extensions: [[$class: 'LocalBranch']],
                                userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/dumpling.git']]]
                        }
                    }
                    

                    githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                }
            }

            stage("Build") {
                dir(build_path) {
                    sh """
                        make build
                    """
                }
            }

            stage("Unit tests") {
                dir(build_path) {
                    sh """
                        make test
                    """
                }
            }

            // stage("Integration tests") {
            //     dir(build_path) {
            //         sh """
            //             make integration_test
            //         """
            //     }
            // }

            stage("Upload") {
                dir(build_path) {
                    def refspath = "refs/pingcap/dumpling/${env.BRANCH_NAME}/sha1"
                    def filepath = "builds/pingcap/dumpling/${githash}/centos7/dumpling.tar.gz"
                    timeout(10) {
                        sh """
                        tar --exclude=dumpling.tar.gz -czvf dumpling.tar.gz *
                        curl -F ${filepath}=@dumpling.tar.gz ${FILE_SERVER_URL}/upload
                        echo "${githash}" > sha1
                        curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }

        }

    }

    currentBuild.result = "SUCCESS"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}

stage('Summary') {
    echo "Send slack here ..."
    def slackmsg = "${currentBuild.result}: `${env.JOB_NAME}` #${env.BUILD_NUMBER}:\n${env.RUN_DISPLAY_URL}\n @here"
    if (currentBuild.result != "SUCCESS" && (branch == "master" || branch.startsWith("release") || branch.startsWith("refs/tags/v"))) {
        slackSend channel: '#jenkins-ci-build-critical', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}