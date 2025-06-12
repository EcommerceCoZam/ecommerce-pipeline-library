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
                                publishTestResults testResultsPattern: '**/target/surefire-reports/*.xml'
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
                        
                        // Configure registry
                        def registry = "us-central1-docker.pkg.dev/certain-perigee-459722-b4/ecommerce-microservices"
                        def imageName = "${registry}/${config.serviceName}"
                        def fullImageTag = "${imageName}:${env.IMAGE_TAG}"
                        
                        // Build image
                        def dockerImage = docker.build(fullImageTag, "-f Dockerfile .")
                        
                        // Push to registry
                        docker.withRegistry("https://${registry}", 'gcp-registry-credentials') {
                            dockerImage.push(env.IMAGE_TAG)
                            dockerImage.push("${params.ENVIRONMENT}-latest")
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
        
        # Deploy con Helm
        git clone https://github.com/EstebanGZam/helm-microservices-app.git helm
        cd helm
        ./deploy-helm.sh ${environment} upgrade \
            --set global.imageTag=${env.IMAGE_TAG} \
            --set ${config.serviceName}.image.repository=us-central1-docker.pkg.dev/certain-perigee-459722-b4/ecommerce-microservices/${config.serviceName}
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