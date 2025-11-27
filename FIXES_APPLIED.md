# Fixes Applied - November 27, 2025

## Issues Fixed

### 1. JAR Signature File Error
**Error:** `java.lang.SecurityException: Invalid signature file digest for Manifest main attributes`

**Root Cause:** Maven Shade Plugin was including signature files (*.SF, *.DSA, *.RSA) from signed dependency JARs, which become invalid when the JAR is repackaged.

**Fix:** Added filters to exclude signature files in both `socket/pom.xml` and `load-balancer/pom.xml`:
```xml
<filters>
    <filter>
        <artifact>*:*</artifact>
        <excludes>
            <exclude>META-INF/*.SF</exclude>
            <exclude>META-INF/*.DSA</exclude>
            <exclude>META-INF/*.RSA</exclude>
        </excludes>
    </filter>
</filters>
```

### 2. Netty Version Compatibility Error
**Error:** `java.lang.NoSuchMethodError: 'java.nio.ByteBuffer io.netty.util.internal.PlatformDependent.offsetSlice(java.nio.ByteBuffer, int, int)'`

**Root Cause:** Version conflicts between Netty dependencies. Reactor Netty 1.3.0 and Lettuce 7.1.0 were pulling in incompatible Netty versions.

**Fix:** Updated `pom.xml` to use compatible stable versions:
- Downgraded Reactor BOM from `2025.0.0` to `2024.0.0`
- Downgraded Reactor Netty from `1.3.0` to `1.2.1`
- Downgraded Lettuce from `7.1.0.RELEASE` to `6.4.0.RELEASE`
- Added explicit Netty BOM `4.1.115.Final` as the first import to ensure version consistency

### 3. Redis Connection Error for Load Balancer
**Error:** `io.lettuce.core.RedisConnectionException: Unable to connect to localhost/<unresolved>:6379`

**Root Cause:** Load-balancer container was missing the `REDIS_URL` environment variable, causing it to default to `localhost:6379` instead of the Redis container.

**Fix:** Added to `docker-compose.yml`:
```yaml
environment:
  REDIS_URL: redis://redis:6379
depends_on:
  redis:
    condition: service_healthy
```

### 4. Prometheus SIGBUS Error (ARM64 Platform)
**Error:** `fatal error: fault [signal SIGBUS: bus error code=0x2]`

**Root Cause:** Prometheus `latest` tag has compatibility issues with ARM64 architecture on some systems.

**Fix:** Updated `deploy/docker-compose.prometheus.yml`:
- Pinned Prometheus to stable version `v2.45.0`
- Added `platform: linux/amd64` to force x86_64 emulation
- Pinned Grafana to stable version `10.0.3`

### 5. Maven Shade Plugin Enhancements
**Fix:** Added additional transformers to properly handle service files and Spring resources:
```xml
<transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
<transformer implementation="org.apache.maven.plugins.shade.resource.AppendingTransformer">
    <resource>META-INF/spring.handlers</resource>
</transformer>
<transformer implementation="org.apache.maven.plugins.shade.resource.AppendingTransformer">
    <resource>META-INF/spring.schemas</resource>
</transformer>
```

## Files Modified

1. `pom.xml` - Updated dependency versions and added Netty BOM
2. `socket/pom.xml` - Added Maven Shade Plugin filters and transformers
3. `load-balancer/pom.xml` - Added Maven Shade Plugin filters and transformers
4. `docker-compose.yml` - Added Redis URL environment variable for load-balancer
5. `deploy/docker-compose.prometheus.yml` - Fixed Prometheus and Grafana versions

## How to Apply These Fixes

### Step 1: Clean existing containers and volumes
```bash
docker-compose down -v
docker-compose -f docker-compose.yml -f deploy/docker-compose.prometheus.yml down -v
```

### Step 2: Clean Maven build artifacts
```bash
mvn clean
```

### Step 3: Rebuild the project
```bash
mvn package
```

### Step 4: Rebuild and start containers (without Prometheus/Grafana)
```bash
docker-compose up --build
```

### Step 5: (Optional) Start with Prometheus and Grafana
```bash
docker-compose -f docker-compose.yml -f deploy/docker-compose.prometheus.yml up --build
```

## Verification

After starting the containers, verify:

1. **Load-balancer** should start without Redis connection errors:
   ```bash
   docker logs load-balancer
   ```
   Look for: `Connected to Redis: redis://redis:6379`

2. **Socket nodes** should start without Netty errors:
   ```bash
   docker logs socket-1
   docker logs socket-2
   ```
   Look for successful startup messages

3. **Prometheus** (if running) should not crash with SIGBUS:
   ```bash
   docker logs prometheus
   ```

4. **Grafana** (if running) should not have disk full errors:
   ```bash
   docker logs grafana
   ```

## Technical Details

### Dependency Version Compatibility Matrix
```
Reactor BOM:     2024.0.0
Reactor Netty:   1.2.1
Netty BOM:       4.1.115.Final
Lettuce:         6.4.0.RELEASE
Reactor Kafka:   1.3.25
```

### Why These Versions?
- Reactor Netty 1.2.1 is compatible with Netty 4.1.x series
- Lettuce 6.4.0 uses compatible Netty versions
- The Netty BOM ensures all Netty artifacts use the same version
- These versions are stable and well-tested together

## Troubleshooting

If you still encounter issues:

1. **Check Docker disk space:**
   ```bash
   docker system df
   docker system prune -a --volumes
   ```

2. **View dependency tree to check for conflicts:**
   ```bash
   mvn dependency:tree -Dverbose
   ```

3. **Force rebuild without cache:**
   ```bash
   docker-compose build --no-cache
   ```

4. **Check for port conflicts:**
   ```bash
   lsof -i :8080,8081,8082,6379,9092,9090,3000
   ```

## Notes

- The fixes ensure compatibility across Java 21, Reactor, Netty, and Lettuce
- Signature file exclusion is standard practice for fat JAR creation
- Pinning Prometheus/Grafana versions ensures stability on ARM64 Macs
- All changes maintain backward compatibility with existing functionality

