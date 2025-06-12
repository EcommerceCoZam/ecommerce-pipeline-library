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
            jdk 'Java-17'
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
            
            stage('Docker Build') {
                steps {
                    script {
                        echo "🐳 Building Docker image..."
                        def imageName = "${config.serviceName}:${env.IMAGE_TAG}"
                        docker.build(imageName, "-f Dockerfile .")
                        
                        // Tag by environment
                        docker.image(imageName).tag("${config.serviceName}:${params.ENVIRONMENT}-latest")
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
    
    sh """
        cd helm
        ./deploy-helm.sh ${environment} upgrade || ./deploy-helm.sh ${environment} install
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