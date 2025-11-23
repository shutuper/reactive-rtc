# Deployment Guide - Reactive RTC

## Local Development

### 1. Start Infrastructure
```bash
make run-infra
# OR
docker-compose up -d zookeeper kafka redis
```

### 2. Build Project
```bash
make build
# OR
mvn clean package -DskipTests
```

### 3. Run Load-Balancer
```bash
make run-lb
# In separate terminal
```

### 4. Run Socket Nodes
```bash
make run-socket
# In separate terminal

make run-socket2
# In another terminal
```

### 5. Test Connection
```bash
# Get connect info
curl "http://localhost:8081/api/v1/connect?userId=alice" | jq .

# Connect with wscat
npm install -g wscat
wscat -c "ws://localhost:8080/ws?userId=alice"
```

## Docker Compose Deployment

### Build and Run
```bash
docker-compose up --build
```

### Stop
```bash
docker-compose down
```

## Kubernetes Production Deployment

### Prerequisites Checklist

- [ ] Kubernetes cluster (1.24+)
- [ ] `kubectl` configured
- [ ] Kafka cluster available
- [ ] Redis cluster available
- [ ] Docker images built and pushed
- [ ] Ingress controller installed
- [ ] Prometheus operator (optional)
- [ ] KEDA installed (optional)

### Step-by-Step

#### 1. Build and Push Images

```bash
# Build
mvn clean package -DskipTests

# Tag
docker build -t your-registry/reactive-rtc-socket:v1.0.0 -f socket/Dockerfile .
docker build -t your-registry/reactive-rtc-load-balancer:v1.0.0 -f load-balancer/Dockerfile .

# Push
docker push your-registry/reactive-rtc-socket:v1.0.0
docker push your-registry/reactive-rtc-load-balancer:v1.0.0
```

#### 2. Update ConfigMap

Edit `deploy/k8s/configmap.yaml`:
```yaml
data:
  KAFKA_BOOTSTRAP: "your-kafka-cluster:9092"
  REDIS_URL: "redis://your-redis-cluster:6379"
  PUBLIC_DOMAIN_TEMPLATE: "wss://socket-%s.yourdomain.com/ws"
```

#### 3. Update Secret

Generate strong secret:
```bash
openssl rand -base64 32
# Update deploy/k8s/secret.yaml with base64-encoded value
```

#### 4. Deploy to Kubernetes

```bash
kubectl apply -f deploy/k8s/namespace.yaml
kubectl apply -f deploy/k8s/configmap.yaml
kubectl apply -f deploy/k8s/secret.yaml
kubectl apply -f deploy/k8s/load-balancer-deploy.yaml
kubectl apply -f deploy/k8s/socket-deploy.yaml
```

#### 5. Verify Deployment

```bash
# Check pods
kubectl get pods -n rtc -w

# Check services
kubectl get svc -n rtc

# View logs
kubectl logs -n rtc -l app=load-balancer --tail=100
kubectl logs -n rtc -l app=socket --tail=100

# Check metrics
kubectl port-forward -n rtc svc/load-balancer-service 8081:8081
curl http://localhost:8081/metrics
```

#### 6. Configure Ingress (for external access)

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: rtc-ingress
  namespace: rtc
  annotations:
    nginx.ingress.kubernetes.io/websocket-services: "socket-service"
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
spec:
  tls:
    - hosts:
        - "*.yourdomain.com"
      secretName: rtc-tls
  rules:
    - host: "*.yourdomain.com"
      http:
        paths:
          - path: /ws
            pathType: Prefix
            backend:
              service:
                name: socket-service
                port:
                  number: 8080
```

## Monitoring Setup

### Prometheus (with Operator)

```bash
# Uncomment ServiceMonitor in deploy/k8s/load-balancer-deploy.yaml
kubectl apply -f deploy/k8s/load-balancer-deploy.yaml
```

### Grafana Dashboards

Key panels to create:
1. **Connections**: `sum(rtc_socket_active_connections)`
2. **Throughput**: `rate(rtc_socket_deliver_mps[5m])`
3. **Latency p95**: `histogram_quantile(0.95, rate(rtc_socket_latency_bucket[5m]))`
4. **Ring health**: `rtc_lb_ring_nodes` & `rtc_lb_ring_vnodes`
5. **Drops**: `rate(rtc_socket_drops_total[5m])`

### Alerts

```yaml
groups:
  - name: reactive-rtc
    rules:
      - alert: HighLatency
        expr: histogram_quantile(0.95, rate(rtc_socket_latency_bucket[5m])) > 500
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High p95 latency detected"
          
      - alert: HighDropRate
        expr: rate(rtc_socket_drops_total[5m]) > 10
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Messages being dropped"
          
      - alert: NoActiveNodes
        expr: rtc_lb_ring_nodes == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "No socket nodes in ring"
```

## Scaling Strategies

### Manual Scaling
```bash
kubectl scale deployment socket -n rtc --replicas=10
```

### HPA (CPU/Memory-based)
Already configured in `socket-deploy.yaml`. Adjust thresholds:
```bash
kubectl edit hpa socket-hpa -n rtc
```

### KEDA (Custom Metrics)
1. Install KEDA:
   ```bash
   helm repo add kedacore https://kedacore.github.io/charts
   helm install keda kedacore/keda --namespace keda --create-namespace
   ```

2. Uncomment `ScaledObject` in `socket-deploy.yaml`

3. Apply:
   ```bash
   kubectl apply -f deploy/k8s/socket-deploy.yaml
   ```

## Troubleshooting

### Pods CrashLooping
```bash
kubectl describe pod -n rtc <pod-name>
kubectl logs -n rtc <pod-name> --previous
```

Common causes:
- Kafka/Redis not accessible
- Wrong RING_SECRET
- Resource limits too low

### WebSocket Connections Failing
```bash
# Test from inside cluster
kubectl run -it --rm debug --image=alpine --restart=Never -n rtc -- sh
apk add curl
curl http://socket-service:8080/healthz
```

### Ring Not Updating
```bash
# Check Kafka connectivity
kubectl logs -n rtc -l app=load-balancer | grep -i kafka

# Verify topics exist
kubectl exec -it kafka-0 -n kafka -- kafka-topics.sh --list --bootstrap-server localhost:9092
```

## Security Hardening

### 1. Network Policies
```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: socket-netpol
  namespace: rtc
spec:
  podSelector:
    matchLabels:
      app: socket
  policyTypes:
    - Ingress
    - Egress
  ingress:
    - from:
        - podSelector: {}
      ports:
        - protocol: TCP
          port: 8080
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              name: kafka
      ports:
        - protocol: TCP
          port: 9092
    - to:
        - podSelector:
            matchLabels:
              app: redis
      ports:
        - protocol: TCP
          port: 6379
```

### 2. Pod Security Standards
```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: rtc
  labels:
    pod-security.kubernetes.io/enforce: restricted
    pod-security.kubernetes.io/audit: restricted
    pod-security.kubernetes.io/warn: restricted
```

### 3. Resource Quotas
```yaml
apiVersion: v1
kind: ResourceQuota
metadata:
  name: rtc-quota
  namespace: rtc
spec:
  hard:
    requests.cpu: "20"
    requests.memory: "40Gi"
    limits.cpu: "40"
    limits.memory: "80Gi"
    pods: "50"
```

## Performance Tuning

### JVM Options
In Dockerfiles or K8s env:
```yaml
env:
  - name: JAVA_OPTS
    value: "-XX:+UseZGC -XX:MaxRAMPercentage=75.0 -XX:+UseStringDeduplication"
```

### Reactor Netty Tuning
```java
System.setProperty("reactor.netty.ioWorkerCount", "8");
System.setProperty("reactor.netty.pool.maxConnections", "500");
```

### Kafka Consumer Tuning
Adjust in KafkaService:
```properties
max.poll.records=500
fetch.min.bytes=1024
fetch.max.wait.ms=500
```

### Redis Connection Pool
Lettuce default pool size is sufficient for most cases. For high throughput:
```java
ClientOptions.builder()
    .socketOptions(SocketOptions.builder()
        .connectTimeout(Duration.ofSeconds(5))
        .build())
    .build();
```

## Rollout Strategy

### Blue-Green Deployment
1. Deploy new version as separate deployment
2. Update load-balancer to include both versions in ring
3. Drain old version gradually
4. Remove old deployment when drained

### Canary Deployment
1. Deploy canary with 10% of replicas
2. Monitor metrics for errors/latency
3. Gradually increase canary replicas
4. Rollback if issues detected

## Disaster Recovery

### Backup Strategy
- **Kafka**: Enable topic retention, use MirrorMaker for DR cluster
- **Redis**: RDB snapshots to S3/GCS every 6 hours
- **Configuration**: Store in Git, use GitOps (ArgoCD/Flux)

### Recovery Procedures
1. **Kafka down**: Socket nodes queue in memory (limited), LB serves stale ring
2. **Redis down**: Sessions lost, clients reconnect and create new sessions
3. **All socket nodes down**: LB returns 503, clients retry with backoff
4. **Load-balancer down**: Existing connections work, new connections fail

## Cost Optimization

1. **Use spot instances** for socket nodes (graceful drain handles interruptions)
2. **Scale to zero during off-hours** (if applicable)
3. **Right-size resources** based on metrics
4. **Use reserved instances** for load-balancer (stable workload)
5. **Compress Kafka messages** if bandwidth is expensive











