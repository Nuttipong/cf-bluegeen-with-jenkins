

def dependenciesService = [
    'gbmf-config-server': [[runTest: false, componentGroup: 1]]
]
def userProvidedServices = [
    'gbmf-provider-portal-client': [[domain: '']],
    // 'gbmf-webapi-gateway': [[domain: 'temp-bg.allianzpartners-providerplatform.com']], // 'https://bg.allianzpartners-providerplatform.com'
    'gbmf-config-server': [[domain: '']],
    'gbmf-translation': [[domain: '']],
    'gbmf-user-management': [[domain: '']],
    'gbmf-guac': [[domain: '']],
    'gbmf-fleet-management': [[domain: '']],
    'gbmf-geo-tracking': [[domain: '']],
    'hg-file-transfer-service': [[domain: '']],
    'gbmf-notification': [[domain: '']],
    'gbmf-service-broker': [[domain: '']],
    'gbmf-job-monitoring': [[domain: '']],
    'gbmf-apigateway-mock': [[domain: '']]
]
def services = [
    'gbmf-config-server': [[runTest: false, componentGroup: 1]],
    'gbmf-translation': [[runTest: false, componentGroup: 1]],
    'gbmf-user-management': [[runTest: false, componentGroup: 1]],
    'gbmf-guac': [[runTest: false, componentGroup: 1]],
    'gbmf-fleet-management': [[runTest: false, componentGroup: 1]],
    'gbmf-geo-tracking': [[runTest: false, componentGroup: 2]],
    'hg-file-transfer-service': [[runTest: false, componentGroup: 2]],
    'gbmf-notification': [[runTest: false, componentGroup: 2]],
    'gbmf-service-broker': [[runTest: false, componentGroup: 2]],
    'gbmf-job-monitoring': [[runTest: false, componentGroup: 2]],
    'gbmf-apigateway-mock': [[runTest: false, componentGroup: 2]]
]
def tasks = [:]
def config

/*
    exit code 137 -> OOM
    exit code 143 -> Unexpected termination
*/

pipeline {
    agent { label 'maven-robot' }
    environment {
        TARGET_API = 'api.sys.adp.ec1.aws.aztec.cloud.allianz'
        TARGET_ORG = 'AzP-Hexalite-AWS-EU'
        TARGET_SPACE = sh ( script: "echo ${env.ENVIRONMENT} | cut -d@ -f1", returnStdout: true ).trim()
        TARGET_GROUP_SOLUTION_DIR = 'gs'
        TARGET_RELEASE_CONFIG_DIR = 'rc'
        TARGET_PORTAL_DIR = 'pt'
        DOMAIN = 'apps.adp.ec1.aws.aztec.cloud.allianz'
        WEB_DOMAIN = 'temp-bg.allianzpartners-providerplatform.com'
        _JAVA_OPTIONS = "-Xmx8g"
    }
    stages {
        stage('Checkout Repositories') {
            environment {
                RELEASE_CONFIG_URL = 'https://github.developer.allianz.io/hexalite/gs_release_control.git'
                GROUP_SOLUTION_URL = 'https://github.developer.allianz.io/hexalite/group_solution.git'
                PORTAL_URL = 'https://github.developer.allianz.io/hexalite/provider_portal.git'
                GIT_REF = '+refs/heads/*:refs/remotes/origin/*'
                ACCESS_TYPE = 'git-token-credentials'
                CHECKOUT_POINT = getCheckoutPoint()
            }
            failFast true
            parallel {
                stage('cloning release config..') {
                    steps {
                        script {
                            checkout([$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'WipeWorkspace'], [$class: 'CloneOption', depth: '2', noTags: true, reference: '', shallow: true, timeout: 15, honorRefspec: false], [$class: 'RelativeTargetDirectory', relativeTargetDir: TARGET_RELEASE_CONFIG_DIR]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: ACCESS_TYPE, url: RELEASE_CONFIG_URL, refspec: GIT_REF]]])
                            dir('./' + TARGET_RELEASE_CONFIG_DIR) {
                                config = parseConfigAsJSON('blue-green.json')
                            }
                        }
                    }
                }
                stage('cloning group solution..') {
                    steps {
                        checkout([$class: 'GitSCM', branches: [[name: CHECKOUT_POINT]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'WipeWorkspace'], [$class: 'CloneOption', depth: '2', noTags: true, reference: '', shallow: true, timeout: 15, honorRefspec: false], [$class: 'RelativeTargetDirectory', relativeTargetDir: TARGET_GROUP_SOLUTION_DIR]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: ACCESS_TYPE, url: GROUP_SOLUTION_URL, refspec: GIT_REF]]])
                        dir('./' + TARGET_GROUP_SOLUTION_DIR) {
                            stash name: 'gs-stash', excludes: '', includes: '**'
                        }
                    }
                }
                stage('cloning provider portal..') {
                    steps {
                        script {
                            checkout([$class: 'GitSCM', branches: [[name: CHECKOUT_POINT]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'WipeWorkspace'], [$class: 'CloneOption', depth: '2', noTags: true, reference: '', shallow: true, timeout: 15, honorRefspec: false], [$class: 'RelativeTargetDirectory', relativeTargetDir: TARGET_PORTAL_DIR]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: ACCESS_TYPE, url: PORTAL_URL, refspec: GIT_REF]]])
                            dir('./' + TARGET_PORTAL_DIR) {
                                stash name: 'portal-stash', excludes: '', includes: '**'
                            }
                        }
                    }
                }
            }
        }
        stage('Build Dependencies') {
            steps {
                script {
                    buildAndDownloadDependencies(TARGET_GROUP_SOLUTION_DIR)
                }
            }
        }
        stage('CI') {
            failFast true
            parallel {
                stage('java') {
                    steps {
                        script {
                            echo "java NODE_NAME = ${env.NODE_NAME}"
                            echo "_JAVA_OPTIONS = ${env._JAVA_OPTIONS}"

                            unstash name: 'repository'

                            withMaven(mavenLocalRepo: ".repository", options: [artifactsPublisher(disabled: true), jacocoPublisher(disabled: true)]) {
                                proceed(TARGET_GROUP_SOLUTION_DIR, 1, services)
                            }
                        }
                    }
                }
                stage('java:jojo-agent') {
                    when { equals expected: 2, actual: 2 }
                    agent { label 'maven-robot' }
                    steps {
                        script {
                            echo "java:new-agent NODE_NAME = ${env.NODE_NAME}"
                            echo "_JAVA_OPTIONS = ${env._JAVA_OPTIONS}"
                            
                            sh "rm -rf *"
                            unstash name: 'repository'

                            dir(TARGET_GROUP_SOLUTION_DIR) {
                                unstash name: 'gs-stash'
                            }

                            withMaven(mavenLocalRepo: ".repository", options: [artifactsPublisher(disabled: true), jacocoPublisher(disabled: true)]) {
                                proceed(TARGET_GROUP_SOLUTION_DIR, 2, services)
                            }
                        }
                    }
                }
                // stage('java:luffy-agent') {
                //     when { equals expected: 2, actual: 2 }
                //     agent { label 'maven-robot' }
                //     steps {
                //         script {
                //             echo "java:new-agent NODE_NAME = ${env.NODE_NAME}"
                            
                //             sh "rm -rf *"
                //             unstash name: 'repository'

                //             dir(TARGET_GROUP_SOLUTION_DIR) {
                //                 unstash name: 'gs-stash'
                //             }

                //             withMaven(mavenLocalRepo: ".repository") {
                //                 proceed(TARGET_GROUP_SOLUTION_DIR, 3, services)
                //             }
                //         }
                //     }
                // }
                stage('node') {
                    when { equals expected: 2, actual: 2 }
                    agent { label 'nodejs-robot' }
                    steps {
                        script {
                            echo "node NODE_NAME = ${env.NODE_NAME}"

                            sh 'rm -rf *'
                            unstash name: 'portal-stash'

                            println 'compiling, test, and packaging'
                            sh """
                                npm --version
                                npm config set strict-ssl=false
                                npm install --registry \$NEXUS_URL/repository/npm-public
                                npm run build -- --configuration=bluegreen --vendor-chunk=true 
                            """
                            // npm run test:single-run -- --browsers=PhantomJS
                            dir('dist') {
                                sh """
                                    mv ../Staticfile .
                                    cp ../manifest.yml .
                                    cp ../package.json .
                                """
                                stash name: 'portal-dist-stash', excludes: '', includes: '**'
                            }
                        }
                    }
                }
            }
        }
        stage('Deploy Microservices') {
            when { equals expected: 2, actual: 2 }
            environment {
                BUILD_PACK = "\$(cf buildpacks | grep java_buildpack | grep fs3 | sort -k5 -r | head -n 1 | cut -d' ' -f1)"
                ACTIVE_ZONE = "${getActiveZone(config)}"
                RELEASE_ZONE = "${getReleaseZone(config)}"
            }
            steps {
                script {                    
                    withCredentials([usernamePassword(credentialsId: "${env.OPENSHIFT_BUILD_NAMESPACE}-adp-hxl-cloudfoundry", passwordVariable: 'ADP_PASSWORD', usernameVariable: 'ADP_USERNAME')]) {
                        println "logged pcf ${TARGET_SPACE}"
                        println "current active zone is ${ACTIVE_ZONE}"
                        println "next release zone is ${RELEASE_ZONE}"
                        sh """
                            cf login -a ${TARGET_API} --skip-ssl-validation -u $ADP_USERNAME -p \"$ADP_PASSWORD\" -o ${TARGET_ORG} -s ${TARGET_SPACE}
                        """
                        dir(TARGET_GROUP_SOLUTION_DIR) {
                            println 'provisioning services..'
                            tasks = [:]
                            userProvidedServices.each { entry ->
                                createService(entry.key, RELEASE_ZONE, DOMAIN, entry.value[0].domain, tasks)
                            }
                            tasks.failFast = true
                            parallel tasks

                            println 'deploying dependencies..'
                            tasks = [:]
                            dependenciesService.keySet().each {
                                push(it, RELEASE_ZONE, DOMAIN, BUILD_PACK, tasks)
                            }
                            tasks.failFast = true
                            parallel tasks

                            println 'deploying services..'
                            tasks = [:]
                            services.keySet().each {
                                if (!dependenciesService.get(it)) {
                                push(it, RELEASE_ZONE, DOMAIN, BUILD_PACK, tasks)
                            }
                            }
                            tasks.failFast = true
                            parallel tasks
                        }
                    } // end credential block
                }
            }
        }
        stage('Deploy Portal') {
            when { equals expected: 2, actual: 2 }
            environment {
                BUILD_PACK = "\$(cf buildpacks | grep staticfile | grep fs3 | sort -k5 -r | head -n 1 | cut -d' ' -f1)"
                RELEASE_ZONE = "${getReleaseZone(config)}"
            }
            steps {
                script {                    
                    withCredentials([usernamePassword(credentialsId: "${env.OPENSHIFT_BUILD_NAMESPACE}-adp-hxl-cloudfoundry", passwordVariable: 'ADP_PASSWORD', usernameVariable: 'ADP_USERNAME')]) {
                        println 'deploying web portal..'
                        def packageJSON
                        dir("dist") {
                            sh "rm -rf *"
                            unstash name: "portal-dist-stash"
                            packageJSON = parseConfigAsJSON('package.json')
                        }

                        println "launching web app.."
                        def hostname = "temp-gbmf-provider-portal-client"
                        def instanceName = "${RELEASE_ZONE}-gbmf-provider-portal-client"
                        sh """
                            cf push ${instanceName} -f ./dist/manifest.yml -p ./dist -n ${hostname} -d ${DOMAIN} -b ${BUILD_PACK}
                            cf set-env ${instanceName} WEB_PORTAL_VERSION ${packageJSON.version}
                            cf app ${instanceName}
                            cf env ${instanceName}
                        """
                        def tempUri = "https://${getHost(instanceName)}.${getDomain(instanceName)}"
                        def publicUri = "https://${WEB_DOMAIN}"
                        println "Temporary private uri to running test process -> ${tempUri}"
                        println "Temporary public uri to running test process -> ${publicUri}"
                    } // end credential block
                }
            }
        }
    }
}

def parseConfigAsJSON(String filename) {
    def configJson = readJSON file: filename, text: ''
    if (!configJson) {
        error('Stopping early because not found config or invalid file format!')
    }
    return configJson
}

def getHost(String instanceName) {
    def hostname = "\$(cf app ${instanceName} | grep routes | awk {'print" + ' $2' + "'} | cut -d'.' -f 1)"
    return hostname
}

def getDomain(String instanceName) {
    def domain = "\$(cf app ${instanceName} | grep routes | awk {'print" + ' $2' + "'} | cut -d'.' -f 2,3,4,5,6)"
    return domain
}

def String getActiveZone(def config) {
    return config.activeZone
}

def String getReleaseZone(def config) {
    return (config.activeZone == "green") ? "blue" : "green"
}

def proceed(String workSpace, int group, def services) {
    def sv = services.findAll { entry -> entry.value[0].componentGroup == group }
    dir(workSpace) {
        tasks = [:]
        tasks.failFast = true
        sv.each { entry ->
            def stepName = "proceed ${entry.key}"
            tasks[stepName] = {
                ci(entry.key, entry.value[0].runTest)
            }
        }
        parallel tasks
    }
}

def ci(String component, boolean runTest) {
    def projectDir = './' + component
    dir(projectDir) {
        def pomfile = readMavenPom file: "./pom.xml"
        def artifactId = "${pomfile.artifactId}"
        if (runTest == true) {
            sh "mvn package -e -Dmaven.wagon.http.ssl.insecure=true -s ../settings.xml -B -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn"
        } else {
            sh "mvn package -e -DskipTests -s ../settings.xml -B -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn" 
        }
        dir('./target') {
            stash name: "gs-${component}-pkg-stash", includes: '*.jar'
        }
    }
}

def buildAndDownloadDependencies(String workSpace ) {
    println 'build and download dependencies to local repository'
    def ts = [:]
    ts.failFast = true
    def dependencies = [
        'gbmf-commons-exception', 
        'proxy-util',
        'database-manager'
    ]
    dir(workSpace) {
        withMaven(mavenLocalRepo: ".repository", options: [artifactsPublisher(disabled: true), jacocoPublisher(disabled: true)]) {
            dependencies.each {
                def stepName = "build and install ${it} dependency"
                ts[stepName] = { ->
                    sh """
                        mvn clean install -DskipTests -s settings.xml -am -pl ${it} -B -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn
                    """
                }
            }
            parallel ts
        }
        stash includes: ".repository/", name: "repository"
    }
}

def push(String component, String releaseZone, String domain, String buildPack, def tasks) {
    def stepName = "deploy ${component} instance"
    tasks[stepName] = { ->
        def hostname = "temp-${component}"
        def instanceName = "${releaseZone}-${component}"
        dir(component) {
            dir('./target') {
                unstash name: "gs-${component}-pkg-stash"
            }
            sh """
                cf push ${instanceName} -f ./manifest.yml -p ./target/*.jar -n ${hostname} -d ${domain} -b ${buildPack} --no-start
                cf set-env ${instanceName} RELEASE_ZONE ${releaseZone}
                cf start ${instanceName}
                cf app ${instanceName}
                cf env ${instanceName}
            """
        }
    }
}

def createService(String component, String releaseZone, String defaultDomain, String configDomain, def tasks) {
    def instance = "${releaseZone}-${component}"
    def serviceName = "${instance}-service"
    def domain = configDomain != "" ? configDomain : "temp-${component}.${defaultDomain}"
    def exist = sh (
                    script: "cf services | grep ${serviceName}",
                    returnStatus: true
                ) == 0
    def stepName = "create ${component} service"
    if (!exist) {
        tasks[stepName] = { ->
            sh """
                cf create-user-provided-service ${serviceName} -p '{"uri":"https://${domain}"}'
            """
        }
    } else {
        stepName = "update ${component} service"
        tasks[stepName] = { ->
            sh """
                cf update-user-provided-service ${serviceName} -p '{"uri":"https://${domain}"}'
            """
        }
    }
}

def getCheckoutPoint() {
    def checkoutPoint = ("${env.BUILD_SPECIFIER}"=="")? "release":(("${env.COMMIT_ID}"=="")? "${env.BUILD_SPECIFIER}":"${env.COMMIT_ID}")
    println ">>>>> checkout group_solution and provider_portal at " + checkoutPoint
    return checkoutPoint
}
