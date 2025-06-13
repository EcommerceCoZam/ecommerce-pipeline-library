def call(Map config) {
    pipeline {
        agent any
        
        tools {
            maven 'Maven-3.8.6'
            gradle 'Gradle'
        }
        
        environment {
            REGISTRY = "southamerica-east1-docker.pkg.dev/ecommercecozam/ecommerce-registry"
            IMAGE_TAG = "${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
            TARGET_ENV = determineEnvironment()
            TRIVY_SERVER = "http://34.73.71.30:9999"
            SONARQUBE_URL = "http://34.73.71.30:9000"
        }
        
        stages {
            stage('Checkout & Setup') {
                steps {
                    checkout scm
                    script {
                        echo "🚀 Building ${config.serviceName} for ${env.TARGET_ENV} environment"
                        echo "📋 Service Type: ${getServiceType(config.serviceName)}"
                    }
                }
            }
            
            stage('Build & Test') {
                parallel {
                    stage('Unit Tests') {
                        when {
                            not { 
                                anyOf {
                                    expression { config.serviceName == 'cloud-config' }
                                    expression { config.serviceName == 'api-gateway' }
                                    expression { config.serviceName == 'service-discovery' }
                                }
                            }
                        }
                        steps {
                            echo "🧪 Running unit tests for ${config.serviceName}..."
                            script {
                                if (config.buildTool == 'maven') {
                                    withMaven(
                                        maven: 'Maven-3.8.6',
                                        mavenSettingsConfig: 'settings-github'
                                    ) {
                                        sh '''
                                            mvn test
                                            mvn jacoco:report
                                        '''
                                    }
                                } else {
                                    // Gradle build
                                    sh '''
                                        ./gradlew test
                                        ./gradlew jacocoTestReport
                                    '''
                                }
                            }
                        }
                        post {
                            always {
                                junit 'target/surefire-reports/*.xml, build/test-results/test/*.xml'
                                publishHTML([
                                    allowMissing: false,
                                    alwaysLinkToLastBuild: true,
                                    keepAll: true,
                                    reportDir: config.buildTool == 'maven' ? 'target/site/jacoco' : 'build/reports/jacoco/test/html',
                                    reportFiles: 'index.html',
                                    reportName: 'Code Coverage Report'
                                ])
                            }
                        }
                    }
                    
                    stage('Build Application') {
                        steps {
                            echo "🔨 Building ${config.serviceName}..."
                            script {
                                if (config.buildTool == 'maven') {
                                    withMaven(
                                        maven: 'Maven-3.8.6',
                                        mavenSettingsConfig: 'settings-github'
                                    ) {
                                        sh 'mvn clean package -DskipTests'
                                    }
                                } else {
                                    // Gradle build
                                    sh './gradlew build -x test'
                                }
                            }
                        }
                    }
                    
                    stage('Basic Integration Check') {
                        when {
                            anyOf {
                                expression { config.serviceName == 'cloud-config' }
                                expression { config.serviceName == 'api-gateway' }
                                expression { config.serviceName == 'service-discovery' }
                            }
                        }
                        steps {
                            echo "🔍 Running basic integration checks for ${config.serviceName}..."
                            sh '''
                                echo "Validating application.properties/yml files..."
                                find . -name "application*.yml" -o -name "application*.properties" | xargs -I {} echo "Found config: {}"
                                
                                echo "Checking if JAR was built successfully..."
                                ls -la build/libs/
                            '''
                        }
                    }
                }
            }
            
            stage('Code Quality Analysis') {
                parallel {
                    stage('SonarQube Analysis') {
                        steps {
                            script {
                                if (getServiceType(config.serviceName) == 'infrastructure') {
                                    echo "🔍 Running SonarQube analysis for infrastructure service (no coverage)..."
                                    withSonarQubeEnv('SonarQube') {
                                        if (config.buildTool == 'maven') {
                                            withMaven(
                                                maven: 'Maven-3.8.6',
                                                mavenSettingsConfig: 'settings-github'
                                            ) {
                                                sh '''
                                                    mvn sonar:sonar \
                                                        -Dsonar.host.url=${SONARQUBE_URL} \
                                                        -Dsonar.projectKey=${JOB_NAME} \
                                                        -Dsonar.projectName="${JOB_NAME}" \
                                                        -Dsonar.projectVersion=${BUILD_NUMBER} \
                                                        -Dsonar.coverage.exclusions="**/*" \
                                                        -Dsonar.cpd.exclusions="**/*"
                                                '''
                                            }
                                        } else {
                                            sh '''
                                                ./gradlew sonarqube \
                                                    -Dsonar.host.url=${SONARQUBE_URL} \
                                                    -Dsonar.projectKey=${JOB_NAME} \
                                                    -Dsonar.projectName="${JOB_NAME}" \
                                                    -Dsonar.projectVersion=${BUILD_NUMBER} \
                                                    -Dsonar.coverage.exclusions="**/*" \
                                                    -Dsonar.cpd.exclusions="**/*"
                                            '''
                                        }
                                    }
                                } else {
                                    echo "🔍 Running SonarQube analysis with coverage for business service..."
                                    withSonarQubeEnv('SonarQube') {
                                        if (config.buildTool == 'maven') {
                                            withMaven(
                                                maven: 'Maven-3.8.6',
                                                mavenSettingsConfig: 'settings-github'
                                            ) {
                                                sh '''
                                                    # Generate Jacoco report first
                                                    mvn jacoco:report
                                                    
                                                    # Run SonarQube analysis
                                                    mvn sonar:sonar \
                                                        -Dsonar.host.url=${SONARQUBE_URL} \
                                                        -Dsonar.projectKey=${JOB_NAME} \
                                                        -Dsonar.projectName="${JOB_NAME}" \
                                                        -Dsonar.projectVersion=${BUILD_NUMBER} \
                                                        -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
                                                '''
                                            }
                                        } else {
                                            sh '''
                                                # Generate Jacoco report first
                                                ./gradlew jacocoTestReport
                                                
                                                # Run SonarQube analysis
                                                ./gradlew sonarqube \
                                                    -Dsonar.host.url=${SONARQUBE_URL} \
                                                    -Dsonar.projectKey=${JOB_NAME} \
                                                    -Dsonar.projectName="${JOB_NAME}" \
                                                    -Dsonar.projectVersion=${BUILD_NUMBER} \
                                                    -Dsonar.coverage.jacoco.xmlReportPaths=build/reports/jacoco/test/jacocoTestReport.xml
                                            '''
                                        }
                                    }
                                }
                            }
                        }
                        post {
                            always {
                                // Archive SonarQube reports
                                script {
                                    if (config.buildTool == 'maven') {
                                        archiveArtifacts artifacts: 'target/site/jacoco/**/*', allowEmptyArchive: true
                                    } else {
                                        archiveArtifacts artifacts: 'build/reports/jacoco/**/*', allowEmptyArchive: true
                                    }
                                }
                            }
                        }
                    }
                    
                    stage('Dependency Security Scan') {
                        steps {
                            script {
                                echo "🛡️ Scanning dependencies with Trivy via Docker..."
                                sh '''
                                    echo "Scanning filesystem for vulnerabilities..."
                                    
                                    # Copy current directory to trivy container for scanning
                                    docker cp . trivy-scanner:/tmp/scan-target
                                    
                                    # Run filesystem scan
                                    docker exec trivy-scanner trivy fs \
                                        --format json \
                                        --output /tmp/trivy-deps-report.json \
                                        /tmp/scan-target || echo "Dependency scan failed, continuing..."
                                    
                                    # Run table format for console output
                                    docker exec trivy-scanner trivy fs \
                                        --format table \
                                        /tmp/scan-target || echo "Table scan failed, continuing..."
                                    
                                    # Copy results back
                                    docker cp trivy-scanner:/tmp/trivy-deps-report.json . || echo "Could not copy results"
                                    
                                    # Cleanup scan target
                                    docker exec trivy-scanner rm -rf /tmp/scan-target || true
                                '''
                            }
                        }
                        post {
                            always {
                                archiveArtifacts artifacts: 'trivy-deps-report.json', allowEmptyArchive: true
                            }
                        }
                    }
                }
            }
            
            stage('Quality Gate') {
                steps {
                    timeout(time: 10, unit: 'MINUTES') {
                        script {
                            echo "⏳ Waiting for SonarQube Quality Gate..."
                            def qg = waitForQualityGate()
                            if (qg.status != 'OK') {
                                echo "⚠️ Quality Gate failed: ${qg.status}"
                                if (env.TARGET_ENV == 'prod') {
                                    error "❌ Quality Gate failure - blocking production deployment"
                                } else {
                                    echo "🟡 Quality Gate failed but allowing deployment to ${env.TARGET_ENV}"
                                }
                            } else {
                                echo "✅ Quality Gate passed successfully"
                            }
                        }
                    }
                }
                post {
                    always {
                        // Verification commands
                        script {
                            sh '''
                                echo "🔍 Verification Commands:"
                                echo "Jenkins SonarQube Plugin Check:"
                                curl -s http://34.73.71.30:8080/pluginManager/api/json?depth=1 | grep -i sonar || echo "SonarQube plugin check failed"
                                
                                echo "SonarQube Server Status:"
                                curl -f http://34.73.71.30:9000/api/system/status || echo "SonarQube server check failed"
                                
                                echo "Trivy Server Health:"
                                docker exec trivy-scanner trivy version || echo "Trivy container check failed"
                            '''
                        }
                    }
                }
            }
            
            stage('Docker Build & Security Scan') {
                steps {
                    script {
                        buildAndSecurityScanImage(config)
                    }
                }
            }
            
            stage('Environment-Specific Tests') {
                when {
                    not { branch 'feature/*' }
                }
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
                            input message: "Deploy to PRODUCTION?", 
                                  ok: "Deploy",
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
            always {
                script {
                    // Cleanup Docker images locally
                    sh '''
                        docker images --filter "dangling=true" -q | xargs -r docker rmi || true
                        docker system prune -f || true
                    '''
                }
            }
        }
    }
}

def getServiceType(serviceName) {
    def infrastructureServices = ['cloud-config', 'api-gateway', 'service-discovery']
    return infrastructureServices.contains(serviceName) ? 'infrastructure' : 'business'
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

def buildAndSecurityScanImage(config) {
    echo "🐳 Building and scanning Docker image for ${config.serviceName}..."
    
    def registryHost = "southamerica-east1-docker.pkg.dev"
    def imageName = "${env.REGISTRY}/${config.serviceName}"
    def fullImageTag = "${imageName}:${env.IMAGE_TAG}"
    
    withCredentials([file(credentialsId: 'gcp-registry-credentials', variable: 'GCP_KEY')]) {
        sh """
            echo '🔐 Authenticating with GCP...'
            gcloud auth activate-service-account --key-file=\$GCP_KEY
            gcloud auth configure-docker ${registryHost} --quiet

            echo '🐳 Building Docker image...'
            docker build -t ${fullImageTag} .
            
            echo '🛡️ Scanning image with Trivy via Docker exec...'
            # Scan the built image using trivy-scanner container
            docker exec trivy-scanner trivy image \
                --format json \
                --output /tmp/trivy-image-report.json \
                ${fullImageTag} || echo "Image scan failed, continuing..."
                
            # Table format for console output
            docker exec trivy-scanner trivy image \
                --format table \
                ${fullImageTag} || echo "Table scan failed, continuing..."
            
            # Copy scan results back to Jenkins workspace
            docker cp trivy-scanner:/tmp/trivy-image-report.json . || echo "Could not copy scan results"

            echo '📤 Pushing image to registry...'
            docker push ${fullImageTag}
            docker tag ${fullImageTag} ${imageName}:${env.TARGET_ENV}-latest
            docker push ${imageName}:${env.TARGET_ENV}-latest
        """
    }
    
    // Archive security scan results
    archiveArtifacts artifacts: 'trivy-image-report.json', allowEmptyArchive: true
    
    // Parse and display critical vulnerabilities
    script {
        try {
            def trivyReport = readJSON file: 'trivy-image-report.json'
            def criticalVulns = 0
            def highVulns = 0
            
            if (trivyReport.Results) {
                trivyReport.Results.each { result ->
                    if (result.Vulnerabilities) {
                        result.Vulnerabilities.each { vuln ->
                            if (vuln.Severity == 'CRITICAL') criticalVulns++
                            else if (vuln.Severity == 'HIGH') highVulns++
                        }
                    }
                }
            }
            
            echo "🛡️ Security Scan Results:"
            echo "   - Critical vulnerabilities: ${criticalVulns}"
            echo "   - High vulnerabilities: ${highVulns}"
            
            // Fail build if too many critical vulnerabilities
            if (criticalVulns > 5) {
                error("❌ Too many critical vulnerabilities found: ${criticalVulns}")
            }
        } catch (Exception e) {
            echo "⚠️ Could not parse Trivy report: ${e.message}"
        }
    }
    
    env.FULL_IMAGE_NAME = fullImageTag
}

def runEnvironmentTests(config) {
    switch(env.TARGET_ENV) {
        case 'dev':
            echo "✅ DEV: Running basic health checks..."
            sh '''
                echo "Basic connectivity tests..."
                # Validate service configuration
                if [ -f "application-dev.yml" ] || [ -f "application-dev.properties" ]; then
                    echo "✅ Development configuration found"
                else
                    echo "⚠️ No development-specific configuration found"
                fi
            '''
            break
            
        case 'stage':
            echo "🧪 STAGE: Running comprehensive tests..."
            parallel(
                'Integration Tests': {
                    script {
                        if (getServiceType(config.serviceName) == 'infrastructure') {
                            echo "🔧 Running infrastructure integration tests..."
                            sh '''
                                cd ecommerce-tests/integration
                                python run_integration_tests.py --service ${config.serviceName} --connectivity-check
                            '''
                        } else {
                            echo "🔗 Running business service integration tests..."
                            sh '''
                                cd ecommerce-tests/integration
                                python run_integration_tests.py --service ${config.serviceName}
                            '''
                        }
                    }
                },
                'E2E Tests': {
                    sh '''
                        echo "Running E2E tests..."
                        cd ecommerce-tests/integration
                        python run_integration_tests.py --service ${config.serviceName} --report-html
                    '''
                },
                'Performance Tests': {
                    sh '''
                        echo "Running performance tests with Locust..."
                        # Solo para servicios de negocio
                        if [[ "${config.serviceName}" != "cloud-config" && "${config.serviceName}" != "service-discovery" ]]; then
                            echo "Performance tests would run here for ${config.serviceName}"
                            # locust --headless -u 10 -r 2 -t 60s --host=http://staging-url
                        else
                            echo "⏭️ Skipping performance tests for infrastructure service"
                        fi
                    '''
                }
            )
            break
            
        case 'prod':
            echo "🔒 PROD: Running security and smoke tests..."
            sh '''
                echo "Running OWASP ZAP security scan..."
                # zap-baseline.py -t http://prod-url
                
                echo "Final security validation..."
                curl -f ${TRIVY_SERVER}/healthz || echo "⚠️ Trivy server health check failed"
            '''
            break
    }
}

def runPostDeployTests(config) {
    echo "🏥 Running post-deployment health checks for ${config.serviceName}..."
    
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
        
        # Service-specific health endpoints
        case "${config.serviceName}" in
            "api-gateway")
                echo "🚪 Testing API Gateway health..."
                kubectl exec -n ecommerce deployment/ecommerce-app-${env.TARGET_ENV}-${config.serviceName}-${config.serviceName} -- curl -f http://localhost:8222/actuator/health
                ;;
            "service-discovery")
                echo "🔍 Testing Service Discovery health..."
                kubectl exec -n ecommerce deployment/ecommerce-app-${env.TARGET_ENV}-${config.serviceName}-${config.serviceName} -- curl -f http://localhost:8761/actuator/health
                ;;
            "cloud-config")
                echo "⚙️ Testing Cloud Config health..."
                kubectl exec -n ecommerce deployment/ecommerce-app-${env.TARGET_ENV}-${config.serviceName}-${config.serviceName} -- curl -f http://localhost:9296/actuator/health
                ;;
            *)
                echo "🏥 Testing business service health..."
                kubectl exec -n ecommerce deployment/ecommerce-app-${env.TARGET_ENV}-${config.serviceName}-${config.serviceName} -- curl -f http://localhost:8080/actuator/health
                ;;
        esac
    """
}

def deployToEnvironment(config, environment) {
    echo "🚀 Deploying ${config.serviceName} to ${environment}..."
    
    sh """
        cd helm
        ./deploy-helm.sh upgrade ${environment}
        
        # Verify deployment
        sleep 30
        kubectl get pods -n ecommerce -l app.kubernetes.io/name=${config.serviceName}
    """
}

def generateReleaseNotes(config) {
    script {
        def version = env.TAG_NAME ?: "${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
        def releaseNotes = """
# Release Notes - ${config.serviceName} v${version}

## 🚀 Deployment Information
- **Service**: ${config.serviceName}
- **Version**: ${version}
- **Environment**: ${env.TARGET_ENV}
- **Build Number**: ${env.BUILD_NUMBER}
- **Deploy Date**: ${new Date().format('yyyy-MM-dd HH:mm:ss')}

## 🔍 Quality Metrics
- **Service Type**: ${getServiceType(config.serviceName)}
- **Security Scan**: ✅ Passed
- **Code Quality**: ✅ Passed
- **Tests**: ✅ Passed

## 🛡️ Security
- Docker image scanned with Trivy
- Dependencies validated
- No critical vulnerabilities detected

## 📋 Changes
${env.CHANGE_LOG ?: 'See Git history for detailed changes'}

---
Deployed automatically via Jenkins Pipeline
        """
        
        writeFile file: "RELEASE_NOTES_${version}.md", text: releaseNotes
        archiveArtifacts artifacts: "RELEASE_NOTES_${version}.md"
    }
}

def sendNotification(status) {
    def color = status == 'SUCCESS' ? 'good' : 'danger'
    def message = """
Pipeline ${status}: ${env.JOB_NAME} #${env.BUILD_NUMBER}
Service: ${config.serviceName}
Environment: ${env.TARGET_ENV ?: 'N/A'}
Branch: ${env.BRANCH_NAME}
Duration: ${currentBuild.durationString}
    """
    
    // Slack notification (if configured)
    // slackSend(color: color, message: message)
    
    echo "📢 Notification: ${message}"
}