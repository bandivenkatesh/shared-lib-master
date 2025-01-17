import com.i27academy.builds.Calculator
import com.i27academy.builds.Docker

def call(Map pipelineParams){
    Calculator calculator = new Calculator(this)
    Docker docker = new Docker(this)    

    pipeline {
        agent {
            label 'k8s-slave'
        }
        parameters {
            choice(name: 'dockerPush',
                choices: 'no\nyes',
                description: 'This Will build dockerImage and Push'
            )
            choice(name: 'deployToDev',
                choices: 'no\nyes',
                description: 'This Will deploy to Dev'
            )
            choice(name: 'deployToTest',
                choices: 'no\nyes',
                description: 'This Will deploy to Test'
            )
            choice(name: 'deployToStage',
                choices: 'no\nyes',
                description: 'This Will deploy to Stage'
            )
            choice(name: 'deployToProd',
                choices: 'no\nyes',
                description: 'This Will deploy to Prod'
            )
        }

        environment {
            APPLICATION_NAME = "${pipelineParams.appName}"
            DOCKER_HUB = "docker.io/venky2222"
            DOCKER_CREDS = credentials('dockerhub_creds')
            DEV_HOST_PORT = "30002"
            TST_HOST_PORT = "30003"
            STG_HOST_PORT = "30004"
            PRD_HOST_PORT = "30005"
            CONT_PORT = "3000"
        }
        stages {
            stage ('Authentication'){
                steps {
                    echo "Executing in GCP project"
                    script {
                        k8s.auth_login()
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
                        imageValidation().call()
                        dockerDeploy('dev', "${env.DEV_HOST_PORT}", "${env.CONT_PORT}").call()
                        echo "Deployed to Dev Successfully"
                    }
                }
            }
            stage ('Deployed to Test') {
                when {
                    expression {
                        params.deployToTest == 'yes'
                    }
                }
                steps {
                    script {
                        imageValidation().call()
                        dockerDeploy('tst', "${env.TST_HOST_PORT}", "${env.CONT_PORT}").call()
                        echo "Deployed to Test Successfully"
                    }
                }
            }
            stage ('Deploy to Stage') {
                when {
                    allOf {
                        anyOf {
                            expression {
                                params.deployToStage == 'yes'
                                // other condition
                            }
                        }
                        anyOf{
                            branch 'release/*'
                        }
                    }
                }
                steps {
                    script {
                        imageValidation().call()
                        dockerDeploy('stg', "${env.STG_HOST_PORT}", "${env.CONT_PORT}").call()
                        echo "Deployed to Stage Successfully"
                    }
                }
            }
            stage('Deploy to Prod') {
                when {
                    allOf {
                        anyOf{
                            expression {
                                params.deployToProd == 'yes'
                            }
                        }
                        anyOf{
                            tag pattern: "v\\d{1,2}\\.\\d{1,2}\\.\\d{1,2}",  comparator: "REGEXP" //v1.2.3
                        }
                    }
                }
                steps {
                    script {
                        imageValidation().call()
                        dockerDeploy('prd', "${env.PRD_HOST_PORT}", "${env.CONT_PORT}").call()
                        echo "Deployed to Prod Successfully"
                    }
                }
            }
        }
    }
}


// Method for Docker build and Push
def dockerBuildAndPush(){
    return {
        echo "************************* Building Docker image*************************"
        sh "ls -la"
        sh "cp -r ${WORKSPACE}/* ./.cicd"
        sh "ls -la ./.cicd"
        sh "docker build --no-cache -t ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT} ./.cicd"
        echo "************************ Login to Docker Registry ************************"
        sh "docker login -u ${DOCKER_CREDS_USR} -p ${DOCKER_CREDS_PSW}"
        sh "docker push ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT}"
    }
}

def imageValidation() {
    return {
        println("Attemting to Pull the Docker Image")
        try {
            sh "docker pull ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT}"
            println("Image is Pulled Succesfully!!!!")
        }
        catch(Exception e) {
            println("OOPS!, the docker image with this tag is not available,So Creating the Image")
            buildApp().call()
            dockerBuildAndPush().call()
        }
    }
}

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
            sh """
                docker run -dit --name ${env.APPLICATION_NAME}-$envDeploy \
                -p $hostPort:$contPort \
                -e ENVIRONMENT=$envDeploy \
                ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT} \
                /entrypoint.sh $envDeploy
            """
        }   
    }
}



















