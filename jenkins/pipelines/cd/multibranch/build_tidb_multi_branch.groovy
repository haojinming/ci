// choose which go version to use. 
def String selectGoVersion(String branchORTag) {
    def goVersion="go1.18"
    if (branchORTag.startsWith("v") && branchORTag <= "v5.1") {
        return "go1.13"
    }
    if (branchORTag.startsWith("v") && branchORTag > "v5.1" && branchORTag < "v6.0") {
        return "go1.16"
    }
    if (branchORTag.startsWith("release-") && branchORTag < "release-5.1"){
        return "go1.13"
    }
    if (branchORTag.startsWith("release-") && branchORTag >= "release-5.1" && branchORTag < "release-6.0"){
        return "go1.16"
    }
    if (branchORTag.startsWith("hz-poc") || branchORTag.startsWith("arm-dup") ) {
        return "go1.16"
    }
    return "go1.18"
}


def GO_BUILD_SLAVE = GO1180_BUILD_SLAVE
def goVersion = selectGoVersion(env.BRANCH_NAME)
if ( goVersion == "go1.16" ) {
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
}
if ( goVersion == "go1.13" ) {
    GO_BUILD_SLAVE = GO_BUILD_SLAVE
}

println "This build use ${goVersion}"


def isNeedBuildBr = false
def isNeedBuildDumpling = false
releaseBranchBuildBr = "release-5.2"
releaseBranchBuildDumpling = "release-5.3"
if (!isNeedBuildBr) {
    isNeedBuildBr = isBranchMatched(["master"], env.BRANCH_NAME)
}
if (!isNeedBuildDumpling) {
    isNeedBuildDumpling = isBranchMatched(["master"], env.BRANCH_NAME)
}
if (!isNeedBuildBr && env.BRANCH_NAME.startsWith("v") && env.BRANCH_NAME > "v5.2") {
    isNeedBuildBr = true
}
if (!isNeedBuildDumpling && env.BRANCH_NAME.startsWith("v") && env.BRANCH_NAME > "v5.3") {
    isNeedBuildDumpling = true
}

if (!isNeedBuildBr && env.BRANCH_NAME.startsWith("release-")) {
    isNeedBuildBr = isMoreRecentOrEqual(trimPrefix(env.BRANCH_NAME), trimPrefix(releaseBranchBuildBr))
    if (isNeedGo1160) {
        println "targetBranch=${env.BRANCH_NAME}  >= ${releaseBranchBuildBr}"
    }
}

if (!isNeedBuildDumpling && env.BRANCH_NAME.startsWith("release-")) {
    isNeedBuildDumpling = isMoreRecentOrEqual(trimPrefix(env.BRANCH_NAME), trimPrefix(releaseBranchBuildDumpling))
    if (isNeedGo1160) {
        println "targetBranch=${env.BRANCH_NAME}  >= ${releaseBranchBuildDumpling}"
    }
}

def isHotfix = false
if ( env.BRANCH_NAME.startsWith("v") &&  env.BRANCH_NAME =~ ".*-202.*") {
    isHotfix = true
}


def BUILD_URL = 'git@github.com:pingcap/tidb.git'

def build_path = 'go/src/github.com/pingcap/tidb'
def slackcolor = 'good'
def githash
def branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"
def plugin_branch = branch

def release_one(repo,product,hash,arch,binary) {
    echo "release binary: ${FILE_SERVER_URL}/download/${binary}"
    def paramsBuild = [
        string(name: "ARCH", value: arch),
        string(name: "OS", value: "linux"),
        string(name: "EDITION", value: "community"),
        string(name: "OUTPUT_BINARY", value: binary),
        string(name: "REPO", value: repo),
        string(name: "PRODUCT", value: product),
        string(name: "GIT_HASH", value: hash),
        string(name: "TARGET_BRANCH", value: env.BRANCH_NAME),
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
    ]
    if (env.TAG_NAME != null) {
        paramsBuild.push(string(name: "RELEASE_TAG", value: env.TAG_NAME))
    }
    build job: "build-common",
            wait: true,
            parameters: paramsBuild
}

def release_tiup_patch(filepath, binary, patch_path) {
    echo "binary ${FILE_SERVER_URL}/download/${filepath}"
    echo "tiup patch ${FILE_SERVER_URL}/download/${patch_path}"
    def paramsBuild = [
        string(name: "INPUT_BINARYS", value: filepath),
        string(name: "BINARY_NAME", value: binary),
        string(name: "PRODUCT", value: "tidb"),
        string(name: "PATCH_PATH", value: patch_path),
    ]
    build job: "patch-common",
            wait: true,
            parameters: paramsBuild
}

def release_docker_image(product, filepath, tag) {
    def image = "pingcap/${product}:$tag"
    echo "docker image ${image}"

    def dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-amd64/${product}"
    def paramsDocker = [
        string(name: "ARCH", value: "amd64"),
        string(name: "OS", value: "linux"),
        string(name: "INPUT_BINARYS", value: filepath),
        string(name: "REPO", value: product),
        string(name: "PRODUCT", value: product),
        string(name: "RELEASE_TAG", value: tag),
        string(name: "DOCKERFILE", value: dockerfile),
        string(name: "RELEASE_DOCKER_IMAGES", value: image),
    ]
    build job: "docker-common",
            wait: true,
            parameters: paramsDocker
}

try {
    node("${GO_BUILD_SLAVE}") {
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
                                                            url: 'git@github.com:pingcap/tidb.git']]
                                ]
                    } else {
                        checkout scm: [$class: 'GitSCM', 
                            branches: [[name: branch]],  
                            extensions: [[$class: 'LocalBranch']],
                            userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tidb.git']]]
                    }
                }
                

                githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
            }
        }

        stage("Build") {
            dir(build_path) {
                container("golang") {
                    timeout(20) {
                        sh """
                        mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                        GOPATH=${ws}/go WITH_RACE=1 make && mv bin/tidb-server bin/tidb-server-race
                        git checkout .
                        GOPATH=${ws}/go WITH_CHECK=1 make && mv bin/tidb-server bin/tidb-server-check
                        git checkout .
                        GOPATH=${ws}/go make failpoint-enable && make server && mv bin/tidb-server{,-failpoint} && make failpoint-disable
                        git checkout .
                        GOPATH=${ws}/go make server_coverage || true
                        git checkout .
                        GOPATH=${ws}/go make
                        git checkout .

                        if [ \$(grep -E "^ddltest:" Makefile) ]; then
                            GOPATH=${ws}/go make ddltest
                        fi
                        
                        if [ \$(grep -E "^importer:" Makefile) ]; then
                            GOPATH=${ws}/go make importer
                        fi
                        """
                    }
                }
            }
        }

        stage("Upload") {
            dir(build_path) {
                def refspath = "refs/pingcap/tidb/${env.BRANCH_NAME}/sha1"
                def filepath = "builds/pingcap/tidb/${env.BRANCH_NAME}/${githash}/centos7/tidb-server.tar.gz"
                def filepath2 = "builds/pingcap/tidb/${githash}/centos7/tidb-server.tar.gz"
                def patch_path = "builds/pingcap/tidb/patch/${env.BRANCH_NAME}/${githash}/centos7/tidb-server.tar.gz"
                container("golang") {
                    timeout(10) {
                        sh """
                        tar --exclude=tidb-server.tar.gz -czvf tidb-server.tar.gz *
                        bin/tidb-server -V
                        curl --fail -F ${filepath}=@tidb-server.tar.gz ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'
                        curl --fail -F ${filepath2}=@tidb-server.tar.gz ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'
                        echo "${githash}" > sha1
                        curl --fail -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'
                        """
                    }
                    tidbArmBinary = "builds/pingcap/test/tidb/${githash}/centos7/tidb-linux-arm64.tar.gz"
                    tidbArmPatch = "builds/pingcap/tidb/patch/${env.BRANCH_NAME}/${githash}/centos7/tidb-server-arm64.tar.gz"
                    release_one("tidb","tidb","${githash}","arm64",tidbArmBinary)
                    
                    if (isHotfix) {
                        release_tiup_patch(filepath, "tidb-server", patch_path)
                        release_tiup_patch(tidbArmBinary, "tidb-server", tidbArmPatch)
                        release_docker_image("tidb", filepath,env.BRANCH_NAME)
                    }
                    
                    if (isNeedBuildBr) {
                        brAmdBinary = "builds/pingcap/br/${env.BRANCH_NAME}/${githash}/centos7/br.tar.gz"
                        release_one("tidb","br","${githash}","amd64",brAmdBinary)
                        // brArmBinary = "builds/pingcap/test/br/${githash}/centos7/br-linux-arm64.tar.gz"
                        // release_one("tidb","br","${githash}","arm64",brArmBinary)
                    }
                    if (isHotfix) {
                        release_docker_image("tidb-lightning",brAmdBinary,env.BRANCH_NAME)
                    }
                    if (isNeedBuildDumpling) {
                        DumplingAmdBinary = "builds/pingcap/dumpling/${env.BRANCH_NAME}/${githash}/centos7/dumpling.tar.gz"
                        release_one("tidb","dumpling","${githash}","amd64",DumplingAmdBinary)
                        DumplingAmdBinary = "builds/pingcap/dumpling/${githash}/centos7/dumpling.tar.gz"
                        release_one("tidb","dumpling","${githash}","amd64",DumplingAmdBinary)
                        // DumplingArmBinary = "builds/pingcap/test/dumpling/${githash}/centos7/dumpling-linux-arm64.tar.gz"
                        // release_one("tidb","dumpling","${githash}","arm64",DumplingArmBinary)
                    }
                    
                }
            }
        }
        
        stage ("Build plugins") {
            if (branch != "release-2.0" && branch != "release-2.1" && !branch.startsWith("refs/tags/v2")) {
                dir("go/src/github.com/pingcap/tidb-build-plugin") {
                    deleteDir()
                    container("golang") {
                        timeout(20) {
                            // checkout scm: [$class: 'GitSCM', 
                            // branches: [[name: branch]],  
                            // extensions: [[$class: 'LocalBranch']],
                            // userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tidb.git']]]
                            sh """
                            cp -R ${ws}/${build_path}/. ./
                            mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                            # GOPATH=${ws}/go  make
                            cd cmd/pluginpkg
                            go build
                            """
                        }
                    }
                }

                def filepath_whitelist = "builds/pingcap/tidb-plugins/${env.BRANCH_NAME}/centos7/whitelist-1.so"
                def filepath_bytidb_whitelist = "builds/pingcap/tidb-plugins/bytidb/${githash}/centos7/whitelist-1.so"
                def md5path_whitelist = "builds/pingcap/tidb-plugins/${env.BRANCH_NAME}/centos7/whitelist-1.so.md5"
                def filepath_audit = "builds/pingcap/tidb-plugins/${env.BRANCH_NAME}/centos7/audit-1.so"
                def filepath_bytidb_audit = "builds/pingcap/tidb-plugins/bytidb/${githash}/centos7/audit-1.so"
                def md5path_audit = "builds/pingcap/tidb-plugins/${env.BRANCH_NAME}/centos7/audit-1.so.md5"

                container("golang") {
                    dir("go/src/github.com/pingcap/enterprise-plugin") {

                        if (plugin_branch.startsWith("refs/tags/v5.0")){
                            plugin_branch = "release-5.0"
                        }

                        if (plugin_branch.startsWith("refs/tags/v5.1")){
                            plugin_branch = "release-5.1"
                        }

                        if (plugin_branch.startsWith("refs/tags/v5.2")){
                            plugin_branch = "release-5.2"
                        }

                        if (plugin_branch.startsWith("refs/tags/v5.3")){
                            plugin_branch = "release-5.3"
                        }


                        if (plugin_branch.startsWith("refs/tags/v4.0.14-202")){
                            plugin_branch = "release-4.0-20220301"
                        }

                        if (plugin_branch.startsWith("refs/tags/v4.0")){
                            plugin_branch = "release-4.0"
                        }

                        if (plugin_branch.startsWith("release-3.0")){
                            plugin_branch = "release-3.0"
                        }
                        println plugin_branch
                         git credentialsId: 'github-sre-bot-ssh', url: "git@github.com:pingcap/enterprise-plugin.git", branch: plugin_branch
                    }
                    dir("go/src/github.com/pingcap/enterprise-plugin/whitelist") {
                        
                            sh """
                            go mod tidy
                            GOPATH=${ws}/go ${ws}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg  -pkg-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist -out-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist
                            md5sum whitelist-1.so > whitelist-1.so.md5
                            curl --fail -F ${md5path_whitelist}=@whitelist-1.so.md5 ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'
                            curl --fail -F ${filepath_whitelist}=@whitelist-1.so ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'
                            curl --fail -F ${filepath_bytidb_whitelist}=@whitelist-1.so ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'
                            """
                    }

                    dir("go/src/github.com/pingcap/enterprise-plugin/audit") {
                        sh """
                        go mod tidy
                        GOPATH=${ws}/go ${ws}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg  -pkg-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit -out-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit
                        md5sum audit-1.so > audit-1.so.md5
                        curl --fail -F ${md5path_audit}=@audit-1.so.md5 ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'
                        curl --fail -F ${filepath_audit}=@audit-1.so ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'
                        curl --fail -F ${filepath_bytidb_audit}=@audit-1.so ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'
                        """
                    }
                }
            }else{
                println "skipped plugin"
            }
        }



    }

    currentBuild.result = "SUCCESS"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}