# Reactive RTC Load Test with Gatling

Load testing for reactive-rtc WebSocket system running in Minikube.

## Prerequisites

- Java 21
- Maven 3.6+
- Running reactive-rtc cluster in Minikube (see `deploy/k8s/QUICKSTART.md`)

## Quick Start with Minikube

### 1. Start Port Forward

First, expose the nginx gateway from Minikube:

```bash
# In a separate terminal, run:
minikube kubectl -- port-forward -n rtc svc/nginx-gateway-service 8080:80
```

Keep this running during the test.

### 2. Build the Project

```bash
cd gatling-load-test
mvn clean compile
```

### 3. Run Small Test (10 users)

```bash
mvn gatling:test \
  -Dclients=10 \
  -Drampup=1 \
  -Dduration=2
```

### 4. Run Medium Test (100 users)

```bash
mvn gatling:test \
  -Dclients=100 \
  -Drampup=1 \
  -Dduration=5
```

### 5. Run Larger Test (1000 users)

```bash
mvn gatling:test \
  -Dclients=1000 \
  -Drampup=2 \
  -Dduration=10
```

## Configuration Options

| Property | Description | Default |
|----------|-------------|---------|
| `-Dclients` | Target number of clients | 100 |
| `-Drampup` | Ramp-up duration (minutes) | 1 |
| `-Dduration` | Total test duration (minutes) | 5 |
| `-Dinterval` | Message interval (seconds) | 3 |
| `-Dgateway.url` | HTTP gateway URL | http://localhost:8080 |
| `-Dws.gateway.url` | WebSocket gateway URL | ws://localhost:8080 |
| `-DmaxReconnectAttempts` | Max reconnection attempts | 10 |

## Test Flow

The load test simulates realistic user behavior:

### 1. Resolve Node

Client calls load balancer `/api/v1/resolve` to get assigned socket node:

```http
GET http://localhost:8080/api/v1/resolve?clientId=user1234
```

Response:
```json
{
  "nodeId": "socket-7d9f8b6c5-abc12"
}
```

### 2. WebSocket Connection

Client connects via nginx gateway which routes to the specific socket node:

```
ws://localhost:8080/ws/socket-7d9f8b6c5-abc12/connect?clientId=user1234
```

Nginx extracts the node ID from the path and proxies to that pod's DNS:
`socket-7d9f8b6c5-abc12.socket.rtc.svc.cluster.local:8080`

### 3. Message Sending

Each client sends messages every 3 seconds to a randomly chosen client:

```json
{
  "msgId": "123e4567-e89b-12d3-a456-426614174000",
  "from": "user1234",
  "toClientId": "user5678",
  "type": "message",
  "payloadJson": "{\"message\":\"Hello from load test!\",\"roomId\":\"room1\"}",
  "ts": 1700409600000
}
```

### 4. Automatic Reconnection

If disconnected, the client:
1. Re-requests socket assignment from load balancer
2. Reconnects to the assigned socket node
3. Continues sending messages

## Example Test Profiles

### Development Test (10 users)
```bash
mvn gatling:test \
  -Dclients=10 \
  -Drampup=1 \
  -Dduration=2 \
  -Dinterval=1
```

### Smoke Test (100 users)
```bash
mvn gatling:test \
  -Dclients=100 \
  -Drampup=1 \
  -Dduration=5 \
  -Dinterval=3
```

### Stress Test (500 users)
```bash
mvn gatling:test \
  -Dclients=500 \
  -Drampup=2 \
  -Dduration=10 \
  -Dinterval=3
```

### Endurance Test (1000 users)
```bash
mvn gatling:test \
  -Dclients=1000 \
  -Drampup=5 \
  -Dduration=30 \
  -Dinterval=3
```

## Reports

After the test completes, Gatling generates an HTML report at:

```
target/gatling/<simulation-name>-<timestamp>/index.html
```

Open it in your browser to see:
- Active users over time
- Response time distribution
- Requests per second
- Success/failure rates

## Monitoring During Test

### Watch Pods
```bash
minikube kubectl -- get pods -n rtc -w
```

### View Application Logs
```bash
# Load balancer
minikube kubectl -- logs -n rtc -l app=load-balancer -f

# Socket nodes
minikube kubectl -- logs -n rtc -l app=socket -f
```

### Check Prometheus Metrics
```bash
# In another terminal:
minikube kubectl -- port-forward -n rtc svc/prometheus-service 9090:9090

# Open http://localhost:9090
# Query: rtc_socket_active_connections
```

## Troubleshooting

### Connection Refused

1. Make sure port-forward is running:
   ```bash
   minikube kubectl -- port-forward -n rtc svc/nginx-gateway-service 8080:80
   ```

2. Check if pods are running:
   ```bash
   minikube kubectl -- get pods -n rtc
   ```

3. Test the endpoint manually:
   ```bash
   curl http://localhost:8080/ws/connect?clientId=test
   ```

### High Failure Rate

1. Check pod logs for errors:
   ```bash
   minikube kubectl -- logs -n rtc -l app=socket --tail=100
   ```

2. Check if Kafka is healthy:
   ```bash
   minikube kubectl -- get kafka -n rtc
   ```

3. Reduce load:
   ```bash
   mvn gatling:test -Dclients=50 -Drampup=2
   ```

### Out of Memory

Increase JVM heap:
```bash
export MAVEN_OPTS="-Xmx4G -Xms4G"
mvn gatling:test -Dclients=1000
```

## Using with Minikube Tunnel (Alternative)

Instead of port-forward, you can use minikube tunnel:

```bash
# Terminal 1: Start tunnel (requires sudo)
minikube tunnel

# Terminal 2: Get the service IP
minikube kubectl -- get svc nginx-gateway-service -n rtc

# Use the EXTERNAL-IP in your test
mvn gatling:test \
  -Dgateway.url=http://<EXTERNAL-IP> \
  -Dws.gateway.url=ws://<EXTERNAL-IP> \
  -Dclients=100
```

## System Requirements

For running load tests:

| Clients | RAM | CPU |
|---------|-----|-----|
| 100 | 1GB | 1 core |
| 1,000 | 2GB | 2 cores |
| 10,000 | 8GB | 4 cores |
| 100,000 | 16GB | 8 cores |

For Minikube cluster:
```bash
minikube start --driver=docker --cpus=4 --memory=8192
```
