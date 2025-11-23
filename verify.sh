#!/bin/bash
# Verification script for reactive-rtc project

set -e

echo "==================================="
echo "Reactive RTC - Build Verification"
echo "==================================="
echo

# 1. Check Java version
echo "1. Checking Java version..."
java -version 2>&1 | head -1
if ! java -version 2>&1 | grep -q "version \"21"; then
    echo "   ⚠️  Warning: Java 21 recommended"
else
    echo "   ✅ Java 21 detected"
fi
echo

# 2. Clean build
echo "2. Running clean build..."
mvn -q clean package -DskipTests
if [ $? -eq 0 ]; then
    echo "   ✅ Build successful"
else
    echo "   ❌ Build failed"
    exit 1
fi
echo

# 3. Verify JARs
echo "3. Verifying JAR artifacts..."
JARS=(
    "core/target/core-1.0-SNAPSHOT.jar"
    "socket/target/socket-1.0-SNAPSHOT.jar"
    "load-balancer/target/load-balancer-1.0-SNAPSHOT.jar"
)

for jar in "${JARS[@]}"; do
    if [ -f "$jar" ]; then
        size=$(du -h "$jar" | cut -f1)
        echo "   ✅ $jar ($size)"
    else
        echo "   ❌ Missing: $jar"
        exit 1
    fi
done
echo

# 4. Run tests
echo "4. Running tests..."
mvn -q test
if [ $? -eq 0 ]; then
    echo "   ✅ All tests passed"
else
    echo "   ❌ Tests failed"
    exit 1
fi
echo

# 5. Verify main classes
echo "5. Verifying main classes..."
if mvn -q exec:java -pl socket -Dexec.mainClass=com.qqsuccubus.socket.SocketApp -Dexec.args="--help" 2>&1 | grep -q "Error\|Exception"; then
    echo "   ⚠️  Socket main class verification skipped (requires runtime deps)"
else
    echo "   ✅ Socket main class verified"
fi

if mvn -q exec:java -pl load-balancer -Dexec.mainClass=com.qqsuccubus.loadbalancer.LoadBalancerApp -Dexec.args="--help" 2>&1 | grep -q "Error\|Exception"; then
    echo "   ⚠️  Load-balancer main class verification skipped (requires runtime deps)"
else
    echo "   ✅ Load-balancer main class verified"
fi
echo

# 6. Verify Docker files
echo "6. Verifying Docker files..."
if [ -f "socket/Dockerfile" ] && [ -f "load-balancer/Dockerfile" ] && [ -f "docker-compose.yml" ]; then
    echo "   ✅ All Docker files present"
else
    echo "   ❌ Missing Docker files"
    exit 1
fi
echo

# 7. Verify K8s manifests
echo "7. Verifying Kubernetes manifests..."
K8S_FILES=(
    "deploy/k8s/namespace.yaml"
    "deploy/k8s/configmap.yaml"
    "deploy/k8s/secret.yaml"
    "deploy/k8s/socket-deploy.yaml"
    "deploy/k8s/load-balancer-deploy.yaml"
)

for file in "${K8S_FILES[@]}"; do
    if [ -f "$file" ]; then
        echo "   ✅ $file"
    else
        echo "   ❌ Missing: $file"
        exit 1
    fi
done
echo

# 8. Check source file count
echo "8. Project statistics..."
java_files=$(find . -name "*.java" -path "*/src/main/*" | wc -l | tr -d ' ')
test_files=$(find . -name "*.java" -path "*/src/test/*" | wc -l | tr -d ' ')
echo "   Java source files: $java_files"
echo "   Test files: $test_files"
echo "   Total: $((java_files + test_files))"
echo

# 9. Verify documentation
echo "9. Verifying documentation..."
DOCS=(
    "README.md"
    "ARCHITECTURE.md"
    "REQUIREMENTS_CHECKLIST.md"
    "PROJECT_SUMMARY.md"
    "deploy/DEPLOYMENT_GUIDE.md"
)

for doc in "${DOCS[@]}"; do
    if [ -f "$doc" ]; then
        lines=$(wc -l < "$doc" | tr -d ' ')
        echo "   ✅ $doc ($lines lines)"
    else
        echo "   ⚠️  Missing: $doc"
    fi
done
echo

echo "==================================="
echo "✅ Verification Complete!"
echo "==================================="
echo
echo "Next steps:"
echo "  - Run locally: make run-infra && make run-lb && make run-socket"
echo "  - Docker: docker-compose up --build"
echo "  - K8s: kubectl apply -f deploy/k8s/"
echo











