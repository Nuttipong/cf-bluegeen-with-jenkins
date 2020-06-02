
def instances = [
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
    'gbmf-apigateway-mock',
    'gbmf-provider-portal-client'
]
def tasks = [:]

def config
def changeRecordSetJSON

pipeline {
    agent { label 'maven-robot' }
    environment {
        TARGET_API = 'api.sys.adp.ec1.aws.aztec.cloud.allianz'
        TARGET_ORG = 'AzP-Hexalite-AWS-EU'
        TARGET_SPACE = sh ( script: "echo ${env.ENVIRONMENT} | cut -d@ -f1", returnStdout: true ).trim()
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

                    config = parseConfigAsJSON('blue-green.json')
                    changeRecordSetJSON = parseConfigAsJSON('./templates/change-resource-record-sets.json')
                }
            }
        }
        stage('Switch traffic') {
            environment {
                ACTIVE_ZONE = "${getActiveZone(config)}"
                RELEASE_ZONE = "${getReleaseZone(config)}"
                WEB_PORTAL_INSTANCE_TEMPLATE = "gbmf-provider-portal-client"
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

                        println "validating zone.."
                        if (hasActiveInstance && hasReleaseInstance) {

                            // def activeAppInstance = "${ACTIVE_ZONE}-${WEB_PORTAL_INSTANCE_TEMPLATE}"
                            // def activeDomain = getDomain(activeAppInstance)
                            // def activeHost = getHost(activeAppInstance)
                            // def releaseAppInstance = "${RELEASE_ZONE}-${WEB_PORTAL_INSTANCE_TEMPLATE}"

                            // println "active domain ${activeDomain}"
                            // println "active host ${activeHost}"

                            // println "map/ummap route orinal URL to new release version"
                            // sh """
                            //     cf map-route ${releaseAppInstance} ${activeDomain} -n ${activeHost}
                            //     cf scale ${activeAppInstance} -i 0
                            //     cf scale ${releaseAppInstance} -i 2
                            //     cf unmap-route ${activeAppInstance} ${activeDomain} -n ${activeHost}
                            // """

                            /*
                                switch route 53 here
                            */
                            println "set new record set data"
                            setRecordSetData(RELEASE_ZONE, config.aws.routeDNS.zoneID, changeRecordSetJSON)

                            println "calling upsert record set"
                            def proceed =  false
                            def resp = ''
                            timeout(1) {
                                waitUntil {
                                    script {
                                        resp = changeRecordSet(config.aws.routeDNS.zoneID, changeRecordSetJSON)
                                        return (resp != "")
                                    }
                                }
                            }
                            def props = readJSON text: resp
                            println "response data ${props}"
                            if (!props || !props.ChangeInfo) {
                                error("Unexpected error from http response!")
                            }

                            if (props.ChangeInfo.Status == "PENDING") {
                                timeout(5) {
                                    waitUntil {
                                        script {
                                            def props = readJSON text: getChangeRecordSet(props.ChangeInfo.Id)
                                            proceed = (props.ChangeInfo.Status == "INSYNC") ? true : false
                                            return (props.ChangeInfo.Status == "INSYNC");
                                        }
                                    }
                                }
                            }

                            if (proceed == true) {
                                println "scale no longer use instances"
                                tasks.failFast = true
                                instances.each {
                                    def activeAppInstance = "${ACTIVE_ZONE}-${it}"
                                    def name = "scale ${activeAppInstance} instace to zero"
                                    tasks[name] = { ->
                                        sh """
                                            cf scale ${activeAppInstance} -i 0
                                        """
                                    }
                                }
                                parallel tasks

                                println "All proceed were done!"
                            } else {
                                error("Something went wrong. Please retry again.")
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

def setRecordSetData(String releaseZone, def config, def json) {
    def recordSetName = config.aws.routeDNS.recordSets[0].hostName
    def proxy = ''
    def hostedZoneId = ''
    if (releaseZone == "blue") {
        proxy = config.aws.elasticBeansTalk.blueProxy
        hostedZoneId = config.aws.routeDNS.recordSets[1].hostZoneID
    } else {
        proxy = config.aws.elasticBeansTalk.greenProxy
        hostedZoneId = config.aws.routeDNS.recordSets[0].hostZoneID
    }
    json.Changes[0].ResourceRecordSet.Name = recordSetName
    json.Changes[0].ResourceRecordSet.AliasTarget.HostedZoneId = hostedZoneId
    json.Changes[0].ResourceRecordSet.AliasTarget.DNSName = proxy
}

def String changeRecordSet(String zoneID, def json) {
    def script = "aws route53 change-resource-record-sets --hosted-zone-id ${zoneID} --cli-input-json ${json}"
    def result = sh (
        script: script,
        returnStdout: true
    ).trim()
    return result
}

def String getChangeRecordSet(String id) {
    def script = "aws route53 get-change --id ${id}"
    def result = sh (
        script: script,
        returnStdout: true
    ).trim()
    return result
}

