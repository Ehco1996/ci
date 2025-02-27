def TIDB_BRANCH = ghprbTargetBranch
def PD_BRANCH = ghprbTargetBranch
def TIDB_TEST_BRANCH = ghprbTargetBranch

if (ghprbPullTitle.find("Bump version") != null) {
    currentBuild.result = 'SUCCESS'
    return
}

// parse tidb branch
def m1 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    TIDB_BRANCH = "${m1[0][1]}"
}
m1 = null
println "TIDB_BRANCH=${TIDB_BRANCH}"

// parse pd branch
def m2 = ghprbCommentBody =~ /pd\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m2) {
    PD_BRANCH = "${m2[0][1]}"
}
m2 = null
println "PD_BRANCH=${PD_BRANCH}"

// parse tidb_test branch
def m3 = ghprbCommentBody =~ /tidb[_\-]test\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    TIDB_TEST_BRANCH = "${m3[0][1]}"
}

m3 = null
println "TIDB_TEST_BRANCH=${TIDB_TEST_BRANCH}"

def tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/tikv-server.tar.gz"

def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}

def isNeedGo1160 = isBranchMatched(["master"], ghprbTargetBranch)
if (isNeedGo1160) {
    println "This build use go1.16 because ghprTargetBranch=master"
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = "test_heavy_go1160_memvolume"
} else {
    println "This build use go1.13"
    GO_TEST_SLAVE = "test_tikv_go1130_memvolume"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"

try {
    stage('Integration DLL Test') {
        def tests = [:]

        def run = { test_dir, mytest, ddltest ->
            node("${GO_TEST_SLAVE}") {
                def ws = pwd()
                deleteDir()

                container("golang") {
                    dir("go/src/github.com/pingcap/tidb-test") {
                        def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                        def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                        def tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"

                        def tidb_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1"
                        def tidb_sha1 = sh(returnStdout: true, script: "curl ${tidb_refs}").trim()
                        tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"

                        def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                        def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                        pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"

                        timeout(30) {
                            sh """
                            while ! curl --output /dev/null --silent --head --fail ${tikv_url}; do sleep 15; done

                            while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 15; done
                            curl ${tidb_test_url} | tar xz

                            cd ${test_dir}

                            (curl ${tikv_url} | tar xz) || (sleep 15 && curl ${tikv_url} | tar xz)

                            while ! curl --output /dev/null --silent --head --fail ${pd_url}; do sleep 15; done
                            curl ${pd_url} | tar xz bin

                            mkdir -p ./tidb-src
                            while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 15; done
                            curl ${tidb_url} | tar xz -C ./tidb-src

                            mv tidb-src/bin/tidb-server ./bin/ddltest_tidb-server
                            mv tidb-src ${ws}/go/src/github.com/pingcap/tidb
                            """
                        }
                    }

                    dir("go/src/github.com/pingcap/tidb") {
                        sh """
                        GO111MODULE=on go mod vendor -v || true
                        """
                    }

                    dir("go/src/github.com/pingcap/tidb_gopath") {
                        sh """
                        mkdir -p ./src
                        cp -rf ../tidb/vendor/* ./src
                        """
                        if (fileExists("../tidb/go.mod")) {
                            sh """
                            mv ../tidb/vendor ../tidb/_vendor
                            """
                        }
                    }

                    dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                        try {
                            timeout(20) {
                                sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                rm -rf ./tikv ./pd
                                set -e

                                bin/pd-server --name=pd --data-dir=pd &>pd_${mytest}.log &
                                sleep 10
                                echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                bin/tikv-server  -C tikv_config.toml --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_${mytest}.log &
                                sleep 10

                                mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                                export PATH=`pwd`/bin:\$PATH
                                export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                export log_level=debug
                                
                                if [ -f ${ws}/go/src/github.com/pingcap/tidb/bin/ddltest ]; then
                                    export DDLTEST_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/ddltest
                                fi
                                
                                TIDB_SERVER_PATH=`pwd`/bin/ddltest_tidb-server \
                                GO111MODULE=off GOPATH=${ws}/go/src/github.com/pingcap/tidb-test/_vendor:${ws}/go/src/github.com/pingcap/tidb_gopath:${ws}/go ./test.sh -check.f='${ddltest}' 2>&1
                                """
                            }
                        } catch (err) {
                            sh """
                            cat pd_${mytest}.log
                            cat tikv_${mytest}.log
                            """
                            throw err
                        } finally {
                            sh """
                            set +e
                            killall -9 -r tidb-server
                            killall -9 -r tikv-server
                            killall -9 -r pd-server
                            set -e
                            """
                        }
                    }
                }
                deleteDir()
            }
        }

        tests["Integration DDL Insert Test"] = {
            run("ddl_test", "ddl_insert_test", "TestDDLSuite.TestSimple.*Insert")
        }

        tests["Integration DDL Update Test"] = {
            run("ddl_test", "ddl_update_test", "TestDDLSuite.TestSimple.*Update")
        }

        tests["Integration DDL Delete Test"] = {
            run("ddl_test", "ddl_delete_test", "TestDDLSuite.TestSimple.*Delete")
        }

        tests["Integration DDL Other Test"] = {
            run("ddl_test", "ddl_other_test", "TestDDLSuite.TestSimp(le\$|leMixed|leInc)")
        }

        tests["Integration DDL Column Test"] = {
            run("ddl_test", "ddl_column_index_test", "TestDDLSuite.TestColumn")
        }

        tests["Integration DDL Index Test"] = {
            run("ddl_test", "ddl_column_index_test", "TestDDLSuite.TestIndex")
        }

        parallel tests
    }

    currentBuild.result = "SUCCESS"
}
catch(Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}

finally {
    echo "Send slack here ..."
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
    "${ghprbPullLink}" + "\n" +
    "${ghprbPullDescription}" + "\n" +
    "Integration DDL Test Result: `${currentBuild.result}`" + "\n" +
    "Elapsed Time: `${duration} mins` " + "\n" +
    "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}

stage("upload status"){
    node("master"){
        sh """curl --connect-timeout 2 --max-time 4 -d '{"job":"$JOB_NAME","id":$BUILD_NUMBER}' http://172.16.5.13:36000/api/v1/ci/job/sync || true"""
    }
}