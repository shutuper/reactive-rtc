# Kubernetes Deployment Guide

## Prerequisites

1. **Kubernetes cluster** (1.24+)
2. **Kafka** cluster (external or via Helm)
3. **Redis** cluster (external or via Helm)
4. **Docker registry** access
5. **kubectl** configured

## Quick Start

### 1. Deploy External Dependencies (if not already available)

**Kafka (using Strimzi operator):**
```bash
kubectl create namespace kafka
kubectl create -f 'https://strimzi.io/install/latest?namespace=kafka' -n kafka
kubectl apply -f - <<EOF
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: rtc-kafka
  namespace: kafka
spec:
  kafka:
    version: 3.6.0
    replicas: 3
    listeners:
      - name: plain
        port: 9092
        type: internal
        tls: false
    config:
      offsets.topic.replication.factor: 3
      transaction.state.log.replication.factor: 3
      transaction.state.log.min.isr: 2
    storage:
      type: persistent-claim
      size: 10Gi
  zookeeper:
    replicas: 3
    storage:
      type: persistent-claim
      size: 5Gi
  entityOperator:
    topicOperator: {}
    userOperator: {}
EOF
```

**Redis (using Helm):**
```bash
helm repo add bitnami https://charts.bitnami.com/bitnami
helm install redis bitnami/redis \
  --namespace rtc \
  --create-namespace \
  --set auth.enabled=false \
  --set master.persistence.size=5Gi
```

### 2. Update ConfigMap

Edit `configmap.yaml` with your actual Kafka and Redis endpoints:
```yaml
KAFKA_BOOTSTRAP: "rtc-kafka-kafka-bootstrap.kafka.svc.cluster.local:9092"
REDIS_URL: "redis://redis-master.rtc.svc.cluster.local:6379"
```

### 3. Deploy Reactive RTC

```bash
kubectl apply -f namespace.yaml
kubectl apply -f configmap.yaml
kubectl apply -f secret.yaml
kubectl apply -f load-balancer-deploy.yaml
kubectl apply -f socket-deploy.yaml
```

### 4. Verify Deployment

```bash
kubectl get pods -n rtc
kubectl logs -n rtc -l app=load-balancer --tail=50
kubectl logs -n rtc -l app=socket --tail=50
```

## Scaling

### Manual Scaling
```bash
kubectl scale deployment socket -n rtc --replicas=5
```

### Autoscaling (HPA)
Already configured in `socket-deploy.yaml`. Verify:
```bash
kubectl get hpa -n rtc
```

### KEDA (Custom Metrics)
Uncomment the `ScaledObject` in `socket-deploy.yaml` and ensure KEDA is installed:
```bash
helm repo add kedacore https://kedacore.github.io/charts
helm install keda kedacore/keda --namespace keda --create-namespace
```

## Monitoring

### Port-forward to access metrics
```bash
kubectl port-forward -n rtc svc/load-balancer-service 8081:8081
kubectl port-forward -n rtc svc/socket-service 8080:8080
```

Access metrics:
- Load-balancer: http://localhost:8081/metrics
- Socket: http://localhost:8080/metrics

### Prometheus Setup

If using Prometheus Operator, the `ServiceMonitor` CRD (commented in manifests) will auto-discover and scrape metrics.

## Troubleshooting

### Pods not starting
```bash
kubectl describe pod -n rtc <pod-name>
kubectl logs -n rtc <pod-name> --previous  # If crashed
```

### Ring not updating
Check load-balancer logs for Kafka connection issues:
```bash
kubectl logs -n rtc -l app=load-balancer | grep -i kafka
```

### WebSocket connections failing
1. Check if socket pods are ready: `kubectl get pods -n rtc -l app=socket`
2. Verify Redis connectivity: `kubectl exec -it <socket-pod> -n rtc -- env | grep REDIS`
3. Check for drain signals in logs

## Production Checklist

- [ ] Update `PUBLIC_DOMAIN_TEMPLATE` in ConfigMap to actual domain
- [ ] Generate strong `RING_SECRET` and update Secret
- [ ] Configure Ingress/LoadBalancer for external access
- [ ] Set up monitoring (Prometheus + Grafana)
- [ ] Configure PodDisruptionBudgets (already in manifests)
- [ ] Set appropriate resource requests/limits
- [ ] Enable network policies for security
- [ ] Configure TLS termination
- [ ] Set up log aggregation (ELK, Loki, etc.)
- [ ] Configure alerts for critical metrics











