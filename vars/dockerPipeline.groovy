import com.i27academy.builds.Calculator
import com.i27academy.builds.Docker

def call(Map pipelineParams){
    // An instance of the class called calculator is created
    Calculator calculator = new Calculator(this)
    Docker docker = new Docker(this)    

// This Jenkinsfile is for Eureka Deployment 

    pipeline {
        agent {
            label 'k8s-slave'
        }
        parameters {
            choice(name: 'scanOnly',
                choices: 'no\nyes',
                description: 'This will scan your application'
            )
            choice(name: 'buildOnly',
                choices: 'no\nyes',
                description: 'This will Only Build your application'
            )
            choice(name: 'dockerPush',
                choices: 'no\nyes',
                description: 'This Will build dockerImage and Push'
            )
            choice(name: 'deployToDev',
                choices: 'no\nyes',
                description: 'This will Deploy the app to Dev env'
            )
            choice(name: 'deployToTest',
                choices: 'no\nyes',
                description: 'This will Deploy the app to Test env'
            )
            choice(name: 'deployToStage',
                choices: 'no\nyes',
                description: 'This will Deploy the app to Stage env'
            )
            choice(name: 'deployToProd',
                choices: 'no\nyes',
                description: 'This will Deploy the app to Prod env'
            )
        }
        tools {
            maven 'Maven-3.8.8'
            jdk 'JDK-17'
        }
        environment {
            APPLICATION_NAME = "${pipelineParams.appName}"
            DEV_HOST_PORT = "${pipelineParams.devHostPort}"
            TST_HOST_PORT = "${pipelineParams.tstHostPort}"
            STG_HOST_PORT = "${pipelineParams.stgHostPort}"
            PRD_HOST_PORT = "${pipelineParams.prdHostPort}"
            HOST_PORT = "${pipelineParams.hostPort}"
            CONT_PORT = "${pipelineParams.contPort}"
            SONAR_TOKEN = credentials('sonar_creds')
            SONAR_URL = "http://34.68.98.190:9000"
            // https://www.jenkins.io/doc/pipeline/steps/pipeline-utility-steps/#readmavenpom-read-a-maven-project-file
            // If any errors with readMavenPom, make sure pipeline-utility-steps plugin is installed in your jenkins, if not do install it
            // http://34.139.130.208:8080/scriptApproval/
            POM_VERSION = readMavenPom().getVersion()
            POM_PACKAGING = readMavenPom().getPackaging()
            DOCKER_HUB = "docker.io/venky2222"
            DOCKER_CREDS = credentials('dockerhub_creds') //username and password
            dev_ip = "10.2.0.4"
            GIT_BRANCH = sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()
        }
        stages {
            stage ('Build') {
                when {
                    anyOf {
                        expression {
                            params.dockerPush == 'yes'
                            params.buildOnly == 'yes'
                        }
                    }
                }
                steps {
                    script {
                        docker.buildApp("${env.APPLICATION_NAME}") //appName
                    }
                }
            }
            stage ('Sonar') {
                when {
                    expression {
                        params.scanOnly == 'yes'
                    }
                    // anyOf {
                    //     expression {
                    //         params.scanOnly == 'yes'
                    //         params.buildOnly == 'yes'
                    //         params.dockerPush == 'yes'
                    //     }
                    // }
                }
                steps {
                    echo "Starting Sonar Scans"
                    withSonarQubeEnv('SonarQube'){ // The name u saved in system under manage jenkins
                        sh """
                        mvn  sonar:sonar \
                            -Dsonar.projectKey=i27-eureka \
                            -Dsonar.host.url=${env.SONAR_URL} \
                            -Dsonar.login=${SONAR_TOKEN}
                        """
                    }
                    timeout (time: 2, unit: 'MINUTES'){
                        waitForQualityGate abortPipeline: true
                    }

                }
            }
            stage ('Docker Build and Push') {
                when {
                    anyOf {
                        expression {
                            params.dockerPush == 'yes'
                        }
                    }
                }
                steps { 
                    script {
                        dockerBuildAndPush().call()
                    }
                } 
            }
            stage ('Deploy to Dev') {
                when {
                    expression {
                        params.deployToDev == 'yes'
                    }
                }
                steps {
                    script {
                        //envDeploy, hostPort, contPort)
                        imageValidation().call()
                        dockerDeploy('dev', "${env.DEV_HOST_PORT}", "${env.CONT_PORT}").call()
                    }
                }
            }
            stage ('Deploy to Test') {
                when {
                    expression {
                        params.deployToTest == 'yes'
                    }
                }
                steps {
                    script {
                        //envDeploy, hostPort, contPort)
                        imageValidation().call()
                        dockerDeploy('tst', "${env.TST_HOST_PORT}", "${env.CONT_PORT}").call()
                    }
                }
            }
            stage ('Deploy to Stage') {
                when {
                    allOf {
                        expression { params.deployToStage == 'yes' }
                        expression { env.GIT_BRANCH ==~ /release\/*/ }
                    }
                }
                steps {
                    script {
                        imageValidation().call()
                        dockerDeploy('stg', "${env.STG_HOST_PORT}", "${env.CONT_PORT}").call()
                    }
                }
            }
            stage ('Deploy to Prod') {
                when {
                    allOf {
                        expression { params.deployToProd == 'yes' }
                        expression { env.GIT_BRANCH ==~ /v\d{1,2}\.\d{1,2}\.\d{1,2}/ }
                    }
                }
                steps {
                    timeout(time: 300, unit: 'SECONDS' ) { // SECONDS, MINUTES,HOURS{
                        input message: "Deploying to ${env.APPLICATION_NAME} to production ??", ok: 'yes', submitter: 'hemasre'
                    }
                    script {
                        //envDeploy, hostPort, contPort)
                        dockerDeploy('prd', "${env.PRD_HOST_PORT}", "${env.CONT_PORT}").call()
                    }
                }
            }
        }
    }
}

// Method for Maven Build
def buildApp() {
    return {
        echo "********************* Building the ${env.APPLICATION_NAME} Application *********************"
        sh 'mvn clean package -DskipTests=true'
    }
}

// Method for Docker build and Push
def dockerBuildAndPush(){
    return {
        echo "******************************** Building Docker image ********************************"
        sh "cp ${WORKSPACE}/target/i27-${env.APPLICATION_NAME}-${env.POM_VERSION}.${env.POM_PACKAGING} ./.cicd"
        sh "docker build --no-cache --build-arg JAR_SOURCE=i27-${env.APPLICATION_NAME}-${env.POM_VERSION}.${env.POM_PACKAGING} -t ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT} ./.cicd"
        echo "******************************* Login to Docker Registry *******************************"
        sh "docker login -u ${DOCKER_CREDS_USR} -p ${DOCKER_CREDS_PSW}"
        echo "******************************* Pushing Docker image *********************************"
        sh "docker push ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT}"
    }
}

def imageValidation() {
    return {
        println("******************************** Attempting to Pull the Docker Image ********************************")
        try {
            sh "docker pull ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT}"
            println("******************************** Image is Pulled Successfully! ********************************")
        }
        catch(Exception e) {
            println("******************************** OOPS! Docker image not found, Creating new Image ********************************")
            buildApp().call()
            dockerBuildAndPush().call()
        }
    }
}


// Method for deploying containers in diff env
def dockerDeploy(envDeploy, hostPort, contPort){
    return {
        echo "******************************** Deploying to $envDeploy Environment ********************************"
        script {
            echo "******************************** Pulling latest image ********************************"
            sh "docker pull ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT}"
            
            try {
                echo "******************************** Stopping existing container ********************************"
                sh "docker stop ${env.APPLICATION_NAME}-$envDeploy"
                echo "******************************** Removing existing container ********************************"
                sh "docker rm ${env.APPLICATION_NAME}-$envDeploy"
            }
            catch(err) {
                echo "******************************** Error Caught: $err ********************************"
            }

            echo "******************************** Creating new container ********************************"
            sh "docker run -dit --name ${env.APPLICATION_NAME}-$envDeploy -p $hostPort:$contPort ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT}"
        }   
    }
}

// Eureka 
// continer port" 8761

// dev hp: 5761
// tst hp: 6761
// stg hp: 7761
// prod hp: 8761

// prod hp: 876177














//withCredentials([usernameColonPassword(credentialsId: 'mylogin', variable: 'USERPASS')])

// https://docs.sonarsource.com/sonarqube/9.9/analyzing-source-code/scanners/jenkins-extension-sonarqube/#jenkins-pipeline

// sshpass -p password ssh -o StrictHostKeyChecking=no username@dockerserverip
 

 //usernameVariable : String
// Name of an environment variable to be set to the username during the build.
// passwordVariable : String
// Name of an environment variable to be set to the password during the build.
// credentialsId : String
// Credentials of an appropriate type to be set to the variable.
