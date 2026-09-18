pipeline {
    agent any

    environment {
        AWS_REGION         = 'us-east-1'
        AWS_ACCOUNT_ID     = credentials('AWS_ACCOUNT_ID')
        ECR_REPOSITORY     = 'coldchainos/backend'
        EKS_CLUSTER_NAME   = 'coldchainos-prod-cluster'
        KUBE_NAMESPACE     = 'coldchainos'
        IMAGE_TAG          = "${env.BUILD_NUMBER}-${env.GIT_COMMIT.take(7)}"
        ECR_REGISTRY       = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
    }

    tools {
        jdk 'JDK_17'
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 30, unit: 'MINUTES')
        timestamps()
        ansiColor('xterm')
    }

    stages {
        stage('1. Checkout Source') {
            steps {
                checkout scm
            }
        }

        stage('2. Build Production JAR') {
            steps {
                sh 'chmod +x ./gradlew'
                sh './gradlew bootJar -x test --no-daemon'
            }
        }

        stage('3. Docker Build & Tag') {
            steps {
                script {
                    sh """
                        docker build \
                            -t ${ECR_REGISTRY}/${ECR_REPOSITORY}:${IMAGE_TAG} \
                            -t ${ECR_REGISTRY}/${ECR_REPOSITORY}:latest \
                            .
                    """
                }
            }
        }

        stage('4. Security Vulnerability Scan') {
            steps {
                // Optional: scan image with Trivy container security scanner
                sh """
                    if command -v trivy >/dev/null 2>&1; then
                        trivy image --severity HIGH,CRITICAL --exit-code 0 ${ECR_REGISTRY}/${ECR_REPOSITORY}:${IMAGE_TAG}
                    else
                        echo "Trivy scanner not found on agent, skipping container vulnerability scan."
                    fi
                """
            }
        }

        stage('5. Push to Amazon ECR') {
            steps {
                withCredentials([[
                    $class: 'AmazonWebServicesCredentialsBinding',
                    credentialsId: 'AWS_JENKINS_CREDENTIALS',
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                ]]) {
                    sh """
                        aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${ECR_REGISTRY}
                        docker push ${ECR_REGISTRY}/${ECR_REPOSITORY}:${IMAGE_TAG}
                        docker push ${ECR_REGISTRY}/${ECR_REPOSITORY}:latest
                    """
                }
            }
        }

        stage('6. Deploy to Amazon EKS') {
            steps {
                withCredentials([[
                    $class: 'AmazonWebServicesCredentialsBinding',
                    credentialsId: 'AWS_JENKINS_CREDENTIALS',
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                ]]) {
                    sh """
                        aws eks update-kubeconfig --region ${AWS_REGION} --name ${EKS_CLUSTER_NAME}
                        
                        kubectl get namespace ${KUBE_NAMESPACE} || kubectl create namespace ${KUBE_NAMESPACE}
                        kubectl apply -f k8s/configmap.yaml
                        kubectl apply -f k8s/secret.yaml
                        kubectl apply -f k8s/service.yaml
                        kubectl apply -f k8s/hpa.yaml
                        
                        kubectl set image deployment/coldchainos-app \
                            coldchainos=${ECR_REGISTRY}/${ECR_REPOSITORY}:${IMAGE_TAG} \
                            -n ${KUBE_NAMESPACE}
                        
                        kubectl rollout status deployment/coldchainos-app -n ${KUBE_NAMESPACE} --timeout=300s
                    """
                }
            }
        }
    }

    post {
        always {
            cleanWs notFailBuild: true
        }
        success {
            echo "ColdChainOS successfully deployed to Amazon EKS (Tag: ${IMAGE_TAG})"
        }
        failure {
            echo "ColdChainOS pipeline failed. Check deployment logs or container readiness probes."
        }
    }
}
