# Quick Start Guide - 5 Minutes to Running System

## Prerequisites
- Java 21+
- Maven 3.9+
- Docker & Docker Compose

## Steps

### 1. Build (30 seconds)
```bash
mvn clean package -DskipTests
```

Expected output:
```
[INFO] BUILD SUCCESS
[INFO] Total time: ~25s
```

### 2. Start Infrastructure (60 seconds)
```bash
docker-compose up -d zookeeper kafka redis
```

Wait 30 seconds for Kafka to initialize:
```bash
docker-compose logs kafka | grep "started (kafka.server.KafkaServer)"
```

### 3. Start Load-Balancer (Terminal 1)
```bash
export KAFKA_BOOTSTRAP=localhost:29092
export RING_SECRET=my-secret-key-for-demo
java -jar load-balancer/target/load-balancer-1.0-SNAPSHOT.jar
```

Expected output:
```
INFO  LoadBalancerApp - Starting Load-Balancer
INFO  HttpServer - HTTP server started on port 8081
```

### 4. Start Socket Node (Terminal 2)
```bash
export NODE_ID=socket-node-1
export PUBLIC_WS_URL=ws://localhost:8080/ws
export HTTP_PORT=8080
export KAFKA_BOOTSTRAP=localhost:29092
export REDIS_URL=redis://localhost:6379
export RING_SECRET=my-secret-key-for-demo
java -jar socket/target/socket-1.0-SNAPSHOT.jar
```

Expected output:
```
INFO  SocketApp - Starting Socket node: socket-node-1
INFO  HttpServer - HTTP server started on port 8080
```

### 5. Test It! (Terminal 3)

**Health check:**
```bash
curl http://localhost:8081/healthz
# => OK

curl http://localhost:8080/healthz
# => OK
```

**Get connection URL:**
```bash
curl "http://localhost:8081/api/v1/connect?userId=alice" | jq .
```

Expected response:
```json
{
  "wsUrl": "ws://localhost:8080/ws?userId=alice",
  "resumeToken": "YWxpY2U6MDox...",
  "ringVersion": 1
}
```

**Send heartbeat (simulate socket node):**
```bash
curl -X POST http://localhost:8081/api/v1/nodes/heartbeat \
  -H "Content-Type: application/json" \
  -d '{
    "nodeId": "socket-node-1",
    "activeConn": 100,
    "mps": 50.0,
    "p95LatencyMs": 120.0,
    "queueDepth": 10,
    "cpu": 0.4,
    "mem": 0.5,
    "ts": '$(date +%s000)'
  }' | jq .
```

Expected response:
```json
{
  "accepted": true,
  "ringVersion": 1,
  "maybeScale": "NONE"
}
```

**View ring:**
```bash
curl http://localhost:8081/api/v1/ring | jq .version
```

## What's Running?

| Service | Port | URL |
|---------|------|-----|
| Zookeeper | 2181 | N/A |
| Kafka | 29092 | localhost:29092 |
| Redis | 6379 | redis://localhost:6379 |
| Load-Balancer | 8081 | http://localhost:8081 |
| Socket Node 1 | 8080 | ws://localhost:8080/ws |

## Next Steps

1. **Connect a WebSocket client**:
   ```bash
   npm install -g wscat
   wscat -c "ws://localhost:8080/ws?userId=alice"
   ```

2. **Start a second socket node** (Terminal 4):
   ```bash
   export NODE_ID=socket-node-2
   export HTTP_PORT=8082
   # ... other env vars ...
   java -jar socket/target/socket-1.0-SNAPSHOT.jar
   ```

3. **Read full docs**:
   - `README.md` - Complete guide
   - `ARCHITECTURE.md` - System design
   - `deploy/DEPLOYMENT_GUIDE.md` - Production deployment

## Troubleshooting

**Q: Build fails with "package does not exist"**  
A: Run from project root: `mvn clean package`

**Q: Kafka not ready**  
A: Wait 30-60 seconds after starting, check: `docker-compose logs kafka`

**Q: Connection refused**  
A: Verify services started successfully, check logs

**Q: Ring is empty**  
A: Socket nodes must send heartbeats to LB (see step 5)

## Clean Up

```bash
# Stop Java processes (Ctrl+C in each terminal)

# Stop Docker containers
docker-compose down

# Clean build artifacts
mvn clean
```

---

**Time to first WebSocket connection: < 5 minutes!** 🚀











