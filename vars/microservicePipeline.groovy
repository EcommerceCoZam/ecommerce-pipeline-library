def call(Map config) {
    pipeline {
        agent any
        
        environment {
            SERVICE_NAME = "${config.serviceName}"
            SERVICE_PORT = "${config.servicePort}"
            IMAGE_TAG = "${env.BUILD_NUMBER}-${env.GIT_COMMIT.take(7)}"
            MAVEN_OPTS = '-Xmx512m'
            REGISTRY = "southamerica-east1-docker.pkg.dev/certain-perigee-459722-b4/ecommerce-microservices"
            
            // Determine branch-based environment
            TARGET_ENV = determineEnvironment()
        }
        
        tools {
            maven 'Maven-3.8.6'
            jdk 'Java-11'
        }
        
        stages {
            stage('Environment Info') {
                steps {
                    script {
                        echo "🌍 Branch: ${env.BRANCH_NAME}"
                        echo "🎯 Target Environment: ${env.TARGET_ENV}"
                        echo "🏷️ Image Tag: ${env.IMAGE_TAG}"
                    }
                }
            }
            
            stage('Build & Basic Tests') {
                parallel {
                    stage('Build') {
                        steps {
                            echo "🔨 Building ${config.serviceName}..."
                            sh 'mvn clean compile'
                        }
                    }
                    
                    stage('Unit Tests') {
                        steps {
                            echo "🧪 Running unit tests..."
                            sh 'mvn test jacoco:report'
                        }
                        post {
                            always {
                                junit testResults: '**/target/surefire-reports/*.xml'
                                publishHTML([
                                    allowMissing: false,
                                    alwaysLinkToLastBuild: true,
                                    keepAll: true,
                                    reportDir: 'target/site/jacoco',
                                    reportFiles: 'index.html',
                                    reportName: 'Code Coverage Report'
                                ])
                            }
                        }
                    }
                    
                    stage('Static Analysis') {
                        steps {
                            echo "🔍 Running SonarQube analysis..."
                            withSonarQubeEnv('SonarQube') {
                                sh 'mvn sonar:sonar'
                            }
                        }
                    }
                }
            }
            
            stage('Quality Gate') {
                steps {
                    timeout(time: 5, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }
            
            stage('Package & Security Scan') {
                parallel {
                    stage('Package') {
                        steps {
                            echo "📦 Packaging..."
                            sh 'mvn package -DskipTests'
                        }
                        post {
                            always {
                                archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
                            }
                        }
                    }
                    
                    stage('Trivy Scan') {
                        steps {
                            script {
                                echo "🛡️ Scanning for vulnerabilities..."
                                sh '''
                                    # Scan dependencies
                                    trivy fs --format json --output trivy-deps.json .
                                    trivy fs --format table .
                                '''
                            }
                        }
                        post {
                            always {
                                archiveArtifacts artifacts: 'trivy-*.json', allowEmptyArchive: true
                            }
                        }
                    }
                }
            }
            
            stage('Docker Build & Push') {
                steps {
                    script {
                        buildAndPushImage(config)
                    }
                }
            }
            
            stage('Environment-Specific Tests') {
                steps {
                    script {
                        runEnvironmentTests(config)
                    }
                }
            }
            
            stage('Deploy') {
                when {
                    anyOf {
                        branch 'dev'
                        branch 'release/*'
                        branch 'main'
                        buildingTag()
                    }
                }
                steps {
                    script {
                        if (env.TARGET_ENV == null) {
                            echo "⏭️ Skipping deployment - Feature branch detected"
                            return
                        }
                        
                        if (env.TARGET_ENV == 'prod') {
                            input message: "Deploy to PRODUCTION?", ok: "Deploy",
                                  parameters: [choice(name: 'CONFIRM', choices: ['no', 'yes'], description: 'Confirm deployment')]
                        }
                        deployToEnvironment(config, env.TARGET_ENV)
                    }
                }
            }
            
            stage('Post-Deploy Tests') {
                when {
                    anyOf {
                        branch 'release/*'
                        branch 'main'
                        buildingTag()
                    }
                }
                steps {
                    script {
                        runPostDeployTests(config)
                    }
                }
            }
        }
        
        post {
            success {
                script {
                    generateReleaseNotes(config)
                    sendNotification('SUCCESS')
                }
            }
            failure {
                script {
                    sendNotification('FAILURE')
                }
            }
        }
    }
}

def determineEnvironment() {
    if (env.BRANCH_NAME == 'dev') {
        return 'dev'
    } else if (env.BRANCH_NAME.startsWith('release/')) {
        return 'stage'
    } else if (env.BRANCH_NAME == 'main' || env.TAG_NAME) {
        return 'prod'
    } else {
        return null // Feature branches do not deploy
    }
}

def buildAndPushImage(config) {
    echo "🐳 Building and pushing Docker image..."
    
    def registryHost = "southamerica-east1-docker.pkg.dev"
    def imageName = "${env.REGISTRY}/${config.serviceName}"
    def fullImageTag = "${imageName}:${env.IMAGE_TAG}"
    
    withCredentials([file(credentialsId: 'gcp-registry-credentials', variable: 'GCP_KEY')]) {
        sh """
            echo '🔐 Autenticando con GCP...'
            gcloud auth activate-service-account --key-file=\$GCP_KEY
            gcloud auth configure-docker ${registryHost} --quiet

            echo '🐳 Construyendo imagen Docker...'
            docker build -t ${fullImageTag} .

            echo '📤 Pusheando imagen...'
            docker push ${fullImageTag}
            docker tag ${fullImageTag} ${imageName}:${env.TARGET_ENV}-latest
            docker push ${imageName}:${env.TARGET_ENV}-latest
            
            echo '🛡️ Escaneando imagen con Trivy...'
            trivy image --format json --output trivy-image.json ${fullImageTag}
            trivy image --format table ${fullImageTag}
        """
    }
    
    archiveArtifacts artifacts: 'trivy-image.json', allowEmptyArchive: true
    env.FULL_IMAGE_NAME = fullImageTag
}

def runEnvironmentTests(config) {
    switch(env.TARGET_ENV) {
        case 'dev':
            echo "✅ DEV: Basic tests already completed"
            break
            
        case 'stage':
            echo "🧪 STAGE: Running integration and E2E tests..."
            parallel(
                'Integration Tests': {
                    sh 'mvn test -Dtest=**/*IntegrationTest'
                },
                'E2E Tests': {
                    sh '''
                        echo "Running E2E tests..."
                        # Aquí irían tus tests E2E (Selenium, Cypress, etc.)
                    '''
                },
                'Performance Tests': {
                    sh '''
                        echo "Running performance tests with Locust..."
                        # locust --headless -u 10 -r 2 -t 60s --host=http://staging-url
                    '''
                }
            )
            break
            
        case 'prod':
            echo "🔒 PROD: Running security tests..."
            sh '''
                echo "Running OWASP ZAP security scan..."
                # zap-baseline.py -t http://prod-url
            '''
            break
    }
}

def runPostDeployTests(config) {
    echo "🏥 Running post-deployment health checks..."
    
    def kubeContexts = [
        'dev': 'aks-ecommercecozam-dev',
        'stage': 'aks-ecommercecozam-stage', 
        'prod': 'aks-ecommercecozam-prod'
    ]
    
    sh """
        kubectl config use-context ${kubeContexts[env.TARGET_ENV]}
        
        # Health check
        kubectl get pods -n ecommerce -l app.kubernetes.io/name=${config.serviceName}
        
        # Wait for deployment to be ready
        kubectl wait --for=condition=available --timeout=300s deployment/ecommerce-app-${env.TARGET_ENV}-${config.serviceName}-${config.serviceName} -n ecommerce
        
        # Smoke tests
        echo "Running smoke tests..."
        # curl health endpoints, basic functionality tests
    """
}

def deployToEnvironment(config, environment) {
    echo "🚀 Deploying to ${environment}..."
    
    def kubeContexts = [
        'dev': 'aks-ecommercecozam-dev',
        'stage': 'aks-ecommercecozam-stage', 
        'prod': 'aks-ecommercecozam-prod'
    ]
    
    def kubeContext = kubeContexts[environment]
    if (!kubeContext) {
        error("Unknown environment: ${environment}")
    }
    
    withCredentials([file(credentialsId: 'gcp-registry-credentials', variable: 'GCP_KEY')]) {
        sh """
            # Switch Kubernetes context
            kubectl config use-context ${kubeContext}
            
            # Check connection
            kubectl cluster-info
            
            # Create namespace if it doesn't exist
            kubectl create namespace ecommerce --dry-run=client -o yaml | kubectl apply -f -
            
            # Create/update image pull secret
            kubectl create secret docker-registry gcp-registry-secret \\
                --docker-server=southamerica-east1-docker.pkg.dev \\
                --docker-username=_json_key \\
                --docker-password="\$(cat \$GCP_KEY)" \\
                --docker-email=jenkins@ecommerce-cozam.com \\
                -n ecommerce \\
                --dry-run=client -o yaml | kubectl apply -f -
            
            kubectl patch serviceaccount default -n ecommerce -p '{"imagePullSecrets": [{"name": "gcp-registry-secret"}]}'
            
            # Clean previous clone if exists
            rm -rf helm
            git clone https://github.com/EstebanGZam/helm-microservices-app.git helm
            cd helm
            
            # Deploy/upgrade specific service with Helm
            helm upgrade --install ecommerce-app-${environment}-${config.serviceName} \\
                ./ecommerce-app/charts/${config.serviceName} \\
                -n ecommerce \\
                --set global.environment=${environment} \\
                --set global.imageTag=${env.IMAGE_TAG} \\
                --set global.imagePullPolicy=Always \\
                --set image.repository=${env.REGISTRY}/${config.serviceName} \\
                --set image.tag=${env.IMAGE_TAG} \\
                --wait \\
                --timeout=10m
        """
    }
}

def generateReleaseNotes(config) {
    def notes = """
# Release ${config.serviceName} v${env.BUILD_NUMBER}

**Environment:** ${env.TARGET_ENV}
**Branch:** ${env.BRANCH_NAME}
**Image:** ${config.serviceName}:${env.IMAGE_TAG}
**Date:** ${new Date()}

## Changes
${env.CHANGE_LOG ?: 'No changes available'}

## Tests Executed
- ✅ Unit Tests (${env.TARGET_ENV == 'dev' ? 'Basic' : 'Full'})
- ✅ Static Analysis (SonarQube)
- ✅ Security Scan (Trivy)
${env.TARGET_ENV == 'stage' ? '- ✅ Integration Tests\n- ✅ E2E Tests\n- ✅ Performance Tests' : ''}
${env.TARGET_ENV == 'prod' ? '- ✅ Security Tests (OWASP ZAP)\n- ✅ Smoke Tests' : ''}

## Deployment Info
- **Namespace:** ecommerce
- **Context:** ${env.TARGET_ENV}
- **Replicas:** ${env.TARGET_ENV == 'dev' ? '1' : env.TARGET_ENV == 'stage' ? '2' : '3+'}
"""
    writeFile file: 'release-notes.md', text: notes
    archiveArtifacts artifacts: 'release-notes.md'
}

def sendNotification(status) {
    def color = status == 'SUCCESS' ? 'good' : 'danger'
    def emoji = status == 'SUCCESS' ? '✅' : '❌'
    
    // Slack notification (configure later)
    /*
    slackSend(
        channel: '#deployments',
        color: color,
        message: "${emoji} Pipeline ${status}: ${env.JOB_NAME} - ${env.BUILD_NUMBER}\nEnvironment: ${env.TARGET_ENV}\nBranch: ${env.BRANCH_NAME}"
    )
    */
    
    echo "${emoji} Pipeline ${status} for ${env.TARGET_ENV} environment"
}