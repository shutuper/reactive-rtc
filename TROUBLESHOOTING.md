# Troubleshooting Guide

## Current Status

### Services Running:
- ✅ Load-Balancer (port 8081) - Working
- ✅ Prometheus (port 9090) - Working  
- ✅ Grafana (port 3000) - Working
- ❌ Socket-1 (port 8080) - Netty shading issue
- ❌ Socket-2 (port 8082) - Netty shading issue

### Issue: `ClassNotFoundException: io.netty.util.internal.SWARUtil`

This is a Netty internal utility class that's missing from the shaded JAR. The class exists but the shade plugin isn't including it properly.

### Quick Access Info:

**Grafana Login:**
- URL: http://localhost:3000
- Username: `admin`
- Password: `admin`

**Prometheus:**
- URL: http://localhost:9090

### Solution Options:

**Option 1: Use without shading (recommended for now)**
```bash
# Run locally without Docker
mvn clean package
cd socket
java -cp target/classes:$(mvn dependency:build-classpath -q -DincludeScope=compile | grep -v '^\['):target/classes com.qqsuccubus.socket.SocketApp
```

**Option 2: Fix the shade plugin configuration**
The SWARUtil class needs special handling in the shade plugin. This requires configuration beyond the current scope.

**Option 3: Use Pre-built JARs**
```bash
# Build without Docker
mvn clean package -DskipTests
# Then copy built jars to deployment
```

### Access URLs:

- Grafana: http://localhost:3000 (admin/admin)
- Prometheus: http://localhost:9090
- Load-Balancer: http://localhost:8081
