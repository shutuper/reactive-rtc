# Reactive RTC Load Test with Gatling

Professional load testing for reactive-rtc WebSocket system using [Gatling](https://gatling.io).

## Features

✅ **1 Million concurrent connections** in 10 minutes  
✅ **Realistic user behavior** - message sending every 3 seconds  
✅ **Automatic reconnection** on disconnect with retry logic  
✅ **Beautiful HTML reports** with charts and metrics  
✅ **Load Balancer integration** - automatic node resolution  
✅ **Sequential client IDs** - from user1 to user1000000  

## Prerequisites

- Java 21
- Maven 3.6+
- Running reactive-rtc services:
  - Load Balancer on `localhost:8080`
  - Socket Node 1 on `localhost:8081`
  - Socket Node 2 on `localhost:8082`
  - Socket Node 3 on `localhost:8083`

## Quick Start

### 1. Build the Project

```bash
cd gatling-load-test
mvn clean compile
```

### 2. Run Small Test (100 users)

```bash
mvn gatling:test \
  -Dclients=100 \
  -Drampup=1 \
  -Dduration=5
```

### 3. Run Full Test (1M users in 10 minutes)

```bash
mvn gatling:test \
  -Dclients=1000000 \
  -Drampup=10 \
  -Dduration=20
```

## Configuration Options

| Property | Description | Default |
|----------|-------------|---------|
| `-Dclients` | Target number of clients | 1000000 |
| `-Drampup` | Ramp-up duration (minutes) | 10 |
| `-Dduration` | Total test duration (minutes) | 20 |
| `-Dinterval` | Message interval (seconds) | 3 |
| `-Dlb.url` | Load balancer URL | http://localhost:8080 |
| `-DmaxReconnectAttempts` | Max reconnection attempts | 10 |

## Test Scenario

The load test simulates realistic user behavior following this flow:

### 1. **Node Resolution**

Client calls load balancer to resolve which socket node to connect to:

```http
GET http://localhost:8080/api/v1/resolve?clientId=user1234
```

Response:
```json
{
  "nodeId": "node-1"
}
```

### 2. **WebSocket Connection**

Based on the `nodeId` from step 1, client connects to the appropriate WebSocket:

- `node-1` → `ws://localhost:8081/ws`
- `node-2` → `ws://localhost:8082/ws`
- `node-3` → `ws://localhost:8083/ws`

### 3. **Message Sending**

Each client sends messages every **3 seconds** to a **randomly chosen client** within the generated ID range.

Message format:
```json
{
  "msgId": "123e4567-e89b-12d3-a456-426614174000",
  "from": "user1234",
  "toClientId": "user5678",
  "type": "message",
  "payloadJson": "{\"message\":\"Hello world!\",\"roomId\":\"room1\"}",
  "ts": 1700409600000
}
```

### 4. **Automatic Reconnection**

If a client disconnects (network issues, server restart, etc.):
1. Client keeps its existing `clientId`
2. Re-resolves node from load balancer (step 1)
3. Reconnects to the assigned WebSocket (step 2)
4. Continues sending messages (step 3)
5. Retries up to `maxReconnectAttempts` times

## Understanding the Reports

After the test completes, Gatling generates an HTML report at:

```
target/gatling/<simulation-name>-<timestamp>/index.html
```

### Key Metrics

**🎯 Active Users**
- Shows concurrent connections over time
- Should reach 1M at 10-minute mark for full test

**📊 Response Times**
- Load balancer resolution time (`/api/v1/resolve`)
- WebSocket connection time
- Message send latency

**✅ Success Rate**
- Connection success/failure ratio
- Message delivery rate
- Reconnection success rate

**📈 Requests Per Second**
- Total throughput
- Messages/sec at peak load
- Connection rate

### Report Sections

1. **Global** - Overall statistics across all requests
2. **Details** - Per-request breakdown (Resolve Node, Connect WebSocket, Send Message)
3. **Active Users** - Concurrent connections chart
4. **Response Time Distribution** - Percentiles (50th, 75th, 95th, 99th)
5. **Requests Per Second** - Throughput over time
6. **Responses Per Second** - Success/failure rates

## Example Test Profiles

### Development Test (10 users)
```bash
mvn gatling:test \
  -Dclients=10 \
  -Drampup=1 \
  -Dduration=2 \
  -Dinterval=1
```

### Smoke Test (1,000 users)
```bash
mvn gatling:test \
  -Dclients=1000 \
  -Drampup=1 \
  -Dduration=5 \
  -Dinterval=3
```

### Stress Test (100,000 users)
```bash
mvn gatling:test \
  -Dclients=100000 \
  -Drampup=5 \
  -Dduration=15 \
  -Dinterval=3
```

### Endurance Test (500,000 users)
```bash
mvn gatling:test \
  -Dclients=500000 \
  -Drampup=10 \
  -Dduration=60 \
  -Dinterval=3
```

### Peak Test (1M users)
```bash
mvn gatling:test \
  -Dclients=1000000 \
  -Drampup=10 \
  -Dduration=30 \
  -Dinterval=3 \
  -DmaxReconnectAttempts=20
```

## System Requirements

### For 1M Connections

**Machine running Gatling:**
- CPU: 16+ cores recommended
- RAM: 32GB+ (each connection uses ~30-50KB)
- Disk: 10GB+ for logs and reports
- Network: High bandwidth, low latency

**OS Tuning Required:**
- Increase file descriptors: `ulimit -n 1000000`
- Tune TCP settings for high connection counts
- Increase JVM heap size

### OS Tuning

**Linux/Mac:**
```bash
# Increase file descriptors (temporary)
ulimit -n 1000000

# Increase file descriptors (permanent)
# Add to /etc/security/limits.conf:
# * soft nofile 1000000
# * hard nofile 1000000

# Increase network buffer sizes
sudo sysctl -w net.core.rmem_max=134217728
sudo sysctl -w net.core.wmem_max=134217728
sudo sysctl -w net.ipv4.tcp_rmem="4096 87380 134217728"
sudo sysctl -w net.ipv4.tcp_wmem="4096 65536 134217728"

# Enable TCP reuse (helps with reconnections)
sudo sysctl -w net.ipv4.tcp_tw_reuse=1

# Increase ephemeral port range
sudo sysctl -w net.ipv4.ip_local_port_range="1024 65535"
```

**JVM Options:**
```bash
# For 100K connections
export JAVA_OPTS="-Xmx8G -Xms8G -XX:+UseG1GC"

# For 1M connections
export JAVA_OPTS="-Xmx16G -Xms16G -XX:+UseG1GC -XX:MaxGCPauseMillis=500"
export MAVEN_OPTS="$JAVA_OPTS"
```

## Monitoring During Test

### Real-Time Console Output

Gatling displays real-time progress in the console:

```
================================================================================
2025-11-28 03:00:00                                          60s elapsed
---- Requests ------------------------------------------------------------------
> Global                                                   (OK=50000  KO=0     )
> Resolve Node                                             (OK=10000  KO=0     )
> Connect WebSocket                                        (OK=10000  KO=0     )
> Send Message                                             (OK=30000  KO=0     )

---- WebSocket User ------------------------------------------------------------
[##########################################################################]100%
          waiting: 0      / active: 10000  / done: 0     
================================================================================
```

### Watch Server Metrics

```bash
# Monitor socket connections per node
watch -n 1 'netstat -an | grep :8081 | grep ESTABLISHED | wc -l'

# Watch Docker logs
docker logs -f socket-1 | grep -E "connected|disconnected"
docker logs -f load-balancer | grep -E "resolve|assign"

# Monitor resource usage
watch -n 1 "docker stats --no-stream"
```

### Prometheus Metrics

If you have Prometheus running, query these metrics during the test:

```promql
# Active WebSocket connections per node
websocket_connections_active{node_id="node-1"}

# Messages per second
rate(messages_sent_total[1m])

# Connection errors
rate(connection_errors_total[1m])
```

## Troubleshooting

### Out of Memory Error

```bash
# Increase JVM heap size
export MAVEN_OPTS="-Xmx16G -Xms16G"
mvn gatling:test -Dclients=1000000
```

### Too Many Open Files

```bash
# Check current limit
ulimit -n

# Increase limit (temporary)
ulimit -n 1000000

# For permanent change, edit /etc/security/limits.conf
echo "* soft nofile 1000000" | sudo tee -a /etc/security/limits.conf
echo "* hard nofile 1000000" | sudo tee -a /etc/security/limits.conf
```

### Connection Refused / Timeout

1. **Verify services are running:**
   ```bash
   curl http://localhost:8080/healthz  # Load balancer
   curl http://localhost:8081/healthz  # Socket node 1
   curl http://localhost:8082/healthz  # Socket node 2
   curl http://localhost:8083/healthz  # Socket node 3
   ```

2. **Check if ports are listening:**
   ```bash
   netstat -an | grep -E "8080|8081|8082|8083" | grep LISTEN
   ```

3. **Review service logs:**
   ```bash
   docker logs load-balancer
   docker logs socket-1
   ```

### Slow Ramp-Up / Low Throughput

- **Reduce clients or increase ramp-up time:**
  ```bash
  mvn gatling:test -Dclients=10000 -Drampup=5
  ```

- **Run on more powerful machine** with more CPU cores and RAM

- **Use multiple Gatling instances** (distributed load testing)

### High Failure Rate

1. **Check server capacity:** Are socket nodes overwhelmed?
2. **Increase server resources:** Scale up or scale out socket nodes
3. **Check Kafka/Redis:** Are they handling the load?
4. **Review logs:** Look for errors in load balancer and socket nodes

## Advanced Configuration

### Custom Gatling Configuration

Create `src/test/resources/gatling.conf`:

```hocon
gatling {
  core {
    outputDirectoryBaseName = "reactive-rtc-test"
    runDescription = "1M WebSocket Connections"
  }
  
  http {
    requestTimeout = 30000
    pooledConnectionIdleTimeout = 60000
    connectionTtl = 120000
  }
  
  socket {
    connectTimeout = 10000
    tcpNoDelay = true
    soKeepAlive = true
  }
  
  data {
    writers = [console, file]
  }
}
```

### Distributed Load Testing

For > 1M connections, run multiple Gatling instances across different machines:

**Machine 1:**
```bash
mvn gatling:test -Dclients=500000 -Drampup=10
```

**Machine 2:**
```bash
mvn gatling:test -Dclients=500000 -Drampup=10
```

Then combine reports using Gatling Enterprise or custom aggregation scripts.

### Custom Assertions

Modify the assertions in the test file to match your SLA requirements:

```java
.assertions(
    global().failedRequests().percent().lte(5.0),      // Max 5% failure
    global().responseTime().percentile3().lte(3000),   // p95 < 3s
    global().responseTime().percentile4().lte(5000),   // p99 < 5s
    global().successfulRequests().count().gte(100000)  // At least 100K success
)
```

## CI/CD Integration

### GitHub Actions

```yaml
name: Load Test

on:
  schedule:
    - cron: '0 2 * * *' # Daily at 2 AM
  workflow_dispatch:

jobs:
  load-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      
      - name: Start Services
        run: |
          docker-compose up -d
          sleep 30  # Wait for services to be ready
      
      - name: Run Load Test
        run: |
          cd gatling-load-test
          mvn gatling:test -Dclients=10000 -Drampup=2 -Dduration=5
      
      - name: Upload Results
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: gatling-results
          path: gatling-load-test/target/gatling/
      
      - name: Stop Services
        if: always()
        run: docker-compose down
```

## Performance Tuning Tips

### For Load Testing Machine

1. **Use SSD** for faster I/O operations
2. **Disable swap** to avoid memory paging
3. **Close unnecessary applications** to free up resources
4. **Use wired network** instead of Wi-Fi for stability
5. **Run on same network/region** as target system to reduce latency

### For Target System

1. **Scale socket nodes horizontally** before testing
2. **Tune Kafka** for high throughput
3. **Optimize Redis** connection pooling
4. **Enable virtual threads** in Java 21
5. **Monitor system resources** during test

## Interpreting Results

### Good Performance Indicators

- ✅ Connection success rate > 95%
- ✅ p95 response time < 1 second for node resolution
- ✅ p95 response time < 2 seconds for WebSocket connection
- ✅ Zero or minimal reconnection attempts
- ✅ Stable active user count throughout test

### Warning Signs

- ⚠️ Connection success rate < 90%
- ⚠️ p95 response time > 5 seconds
- ⚠️ High reconnection rate (> 10%)
- ⚠️ Declining active users over time
- ⚠️ Increasing error rates as load increases

## References

- [Gatling Documentation](https://gatling.io/docs/gatling/)
- [WebSocket Testing Guide](https://docs.gatling.io/guides/use-cases/websocket/)
- [Gatling Java SDK](https://gatling.io/docs/gatling/reference/current/core/injection/)
- [Load Testing Best Practices](https://gatling.io/docs/gatling/tutorials/advanced/)

## License

MIT
