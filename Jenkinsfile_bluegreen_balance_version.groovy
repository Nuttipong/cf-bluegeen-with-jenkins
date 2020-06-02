def micorServices = [
    'gbmf-config-server',
    'gbmf-translation',
    'gbmf-user-management',
    'gbmf-guac',
    'gbmf-fleet-management',
    'gbmf-geo-tracking',
    'hg-file-transfer-service',
    'gbmf-notification',
    'gbmf-service-broker',
    'gbmf-job-monitoring',
    'gbmf-apigateway-mock'
]

def config

pipeline {
    agent { label 'maven-robot' }
    environment {
        TARGET_API = 'api.sys.adp.ec1.aws.aztec.cloud.allianz'
        TARGET_ORG = 'AzP-Hexalite-AWS-EU'
        TARGET_SPACE = sh ( script: "echo ${env.ENVIRONMENT} | cut -d@ -f1", returnStdout: true ).trim()
        CONFIG_FILE = 'blue-green.json'
    }
    stages {
        stage('Checkout Repositories') {
            environment {
                RELEASE_CONFIG_URL = 'https://github.developer.allianz.io/hexalite/gs_release_control.git'
                GIT_REF = '+refs/heads/*:refs/remotes/origin/*'
                ACCESS_TYPE = 'git-token-credentials'
            }
            steps {
                script {
                    checkout([$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'WipeWorkspace'], [$class: 'CloneOption', depth: '2', noTags: true, reference: '', shallow: true, timeout: 15, honorRefspec: false], [$class: 'RelativeTargetDirectory']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: ACCESS_TYPE, url: RELEASE_CONFIG_URL, refspec: GIT_REF]]])

                    config = parseConfigAsJSON(CONFIG_FILE)
                }
            }
        }
        stage('Switch traffic') {
            environment {
                ACTIVE_ZONE = "${getActiveZone(config)}"
                RELEASE_ZONE = "${getReleaseZone(config)}"
            }
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: "${env.OPENSHIFT_BUILD_NAMESPACE}-adp-hxl-cloudfoundry", passwordVariable: 'ADP_PASSWORD', usernameVariable: 'ADP_USERNAME')]) {
                        sh """
                            cf login -a ${TARGET_API} --skip-ssl-validation -u $ADP_USERNAME -p \"$ADP_PASSWORD\" -o ${TARGET_ORG} -s ${TARGET_SPACE}
                        """
                        println "logged on ${TARGET_SPACE} space"

                        def hasActiveInstance = sh (
                                        script: "cf app ${ACTIVE_ZONE}-${WEB_PORTAL_INSTANCE_TEMPLATE} | grep name",
                                        returnStatus: true
                                    ) == 0
                        def hasReleaseInstance = sh (
                                        script: "cf app ${RELEASE_ZONE}-${WEB_PORTAL_INSTANCE_TEMPLATE} | grep name",
                                        returnStatus: true
                                    ) == 0

                        println "checking zone.."
                        if (hasActiveInstance && hasReleaseInstance) {

                            println "scale microservices which no longer use to 0"
                            def tasks = [:]
                            tasks.failFast = true
                            micorServices.each {
                                def stepName = "scale ${it} service"
                                def activeComponentInstance = "${ACTIVE_ZONE}-${it}"
                                tasks[stepName] = { ->
                                    sh """
                                        cf scale ${activeComponentInstance} -i 0
                                    """
                                }
                            }
                            parallel tasks
                            
                            println "update release config repo"
                            if (config) {
                                config.activeZone = RELEASE_ZONE
                                config.version = getLastestTag()
                                writeJSON file: CONFIG_FILE, json: config, pretty: 4
                            }
                        } else {
                            error("Unexpected error!")
                        }
                    }
                }
            }
        }
    }
}

def String getActiveZone(def config) {
    return config.activeZone
}

def String getReleaseZone(def config) {
    return (config.activeZone == "green") ? "blue" : "green"
}

def String getLastestTag() {
    return ''
}

def getHost(String instanceName) {
    def script = "cf app ${instanceName} | grep routes | awk {'print \$2'} | cut -d '.' -f 1"
    def result = sh (
        script: script,
        returnStdout: true
    ).trim()
    println "host >>> ${result}"
    return result
}

def getDomain(String instanceName) {
    def result = sh (script: "cf app ${instanceName} | grep routes | awk {'print \$2'} | cut -d '.' -f 2,3,4,5,6,7,8", 
        returnStdout: true).trim()
    println "domain >>> ${result}"
    return result
}

def parseConfigAsJSON(String filename) {
    def configJson = readJSON file: filename, text: ''
    if (!configJson) {
        error('Stopping early because not found config or invalid file format!')
    }
    return configJson
}