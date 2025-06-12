def call(Map config) {
    pipeline {
        agent any
        
        parameters {
            choice(
                name: 'ENVIRONMENT',
                choices: ['dev', 'stage', 'prod'],
                description: 'Target environment'
            )
        }
        
        environment {
            SERVICE_NAME = "${config.serviceName}"
            SERVICE_PORT = "${config.servicePort}"
            IMAGE_TAG = "${env.BUILD_NUMBER}-${env.GIT_COMMIT.take(7)}"
            MAVEN_OPTS = '-Xmx512m'
        }
        
        tools {
            maven 'Maven-3.8.6'
            jdk 'Java-11'
        }
        
        stages {
            stage('Build & Test') {
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
                            sh 'mvn test'
                        }
                        post {
                            always {
                                junit testResults: '**/target/surefire-reports/*.xml'
                            }
                        }
                    }
                }
            }
            
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
            
            stage('Docker Build & Push') {
                steps {
                    script {
                        echo "🐳 Building and pushing Docker image..."

                        def registryHost = "southamerica-east1-docker.pkg.dev"
                        def registry = "${registryHost}/certain-perigee-459722-b4/ecommerce-microservices"
                        def imageName = "${registry}/${config.serviceName}"
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
                                docker tag ${fullImageTag} ${imageName}:${params.ENVIRONMENT}-latest
                                docker push ${imageName}:${params.ENVIRONMENT}-latest
                            """
                        }

                        // Save for use in deploy
                        env.FULL_IMAGE_NAME = fullImageTag
                    }
                }
            }
            
            stage('Deploy') {
                steps {
                    script {
                        deployToEnvironment(config, params.ENVIRONMENT)
                    }
                }
            }
        }
        
        post {
            success {
                script {
                    generateReleaseNotes(config)
                }
            }
        }
    }
}

def deployToEnvironment(config, environment) {
    echo "🚀 Deploying to ${environment}..."
    
    // Configure Kubernetes context according to environment
    def kubeContexts = [
        'dev': 'aks-ecommercecozam-dev',
        'stage': 'aks-ecommercecozam-stage', 
        'prod': 'aks-ecommercecozam-prod'
    ]
    
    def kubeContext = kubeContexts[environment]
    if (!kubeContext) {
        error("Unknown environment: ${environment}")
    }
    
    sh """
        # Switch Kubernetes context
        kubectl config use-context ${kubeContext}
        
        # Check connection
        kubectl cluster-info
        
        # Clean previous clone if exists
        rm -rf helm
        git clone https://github.com/EstebanGZam/helm-microservices-app.git helm
        cd helm
        
        # Create namespace if it doesn't exist
        kubectl create namespace ecommerce --dry-run=client -o yaml | kubectl apply -f -
        
        # Deploy/upgrade specific service with Helm
        helm upgrade --install ecommerce-app-${environment}-${config.serviceName} \\
            ./ecommerce-app/charts/${config.serviceName} \\
            -n ecommerce \\
            --set global.environment=${environment} \\
            --set global.imageTag=${env.IMAGE_TAG} \\
            --set global.imagePullPolicy=Always \\
            --set image.repository=us-central1-docker.pkg.dev/certain-perigee-459722-b4/ecommerce-microservices/${config.serviceName} \\
            --set image.tag=${env.IMAGE_TAG} \\
            --wait \\
            --timeout=5m
            
        # Verify deployment
        kubectl get pods -n ecommerce -l app.kubernetes.io/name=${config.serviceName}
        kubectl rollout status deployment/ecommerce-app-${environment}-${config.serviceName}-${config.serviceName} -n ecommerce
    """
}

def generateReleaseNotes(config) {
    def notes = """
# Release ${config.serviceName} v${env.BUILD_NUMBER}

**Environment:** ${params.ENVIRONMENT}
**Image:** ${config.serviceName}:${env.IMAGE_TAG}
**Date:** ${new Date()}

## Changes
${env.CHANGE_LOG ?: 'No changes available'}
"""
    writeFile file: 'release-notes.md', text: notes
    archiveArtifacts artifacts: 'release-notes.md'
}