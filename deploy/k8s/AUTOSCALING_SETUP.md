# Autoscaling Setup Guide

## Current Status

✅ **ScaleSignal is published to Kafka** (`rtc.control.scale` topic)
❌ **No autoscaler is currently consuming it**

The load-balancer computes scaling directives and publishes `ScaleSignal` messages to Kafka, but you need to connect an autoscaler to actually execute the scaling.

## Integration Options

### Option 1: KEDA with Kafka Trigger (Recommended)

Create a custom operator that consumes the `ScaleSignal` topic:

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: socket-keda-kafka-scaler
  namespace: rtc
spec:
  scaleTargetRef:
    name: socket
  minReplicaCount: 3
  maxReplicaCount: 20
  triggers:
    - type: kafka
      metadata:
        bootstrapServers: kafka:9092
        topic: rtc.control.scale
        consumerGroup: rtc-socket-autoscaler
        lagThreshold: "1"
        # Consume ScaleSignal messages
        # Scale based on targetReplicas field
```

### Option 2: Custom Kubernetes Controller ✅ IMPLEMENTED

A complete controller is now available in `autoscaler-controller/` that:
1. Subscribes to the `rtc.control.scale` Kafka topic
2. Parses `ScaleSignal` messages
3. Updates the StatefulSet replica count via Kubernetes API

**Deploy the controller:**
```bash
cd autoscaler-controller
docker build -t qqsuccubus/autoscaler-controller:latest .
kubectl apply -f k8s-deployment.yaml
```

**How it works:**
- **Scale-IN**: Controller updates StatefulSet replicas → Kubernetes gracefully terminates pods
- **Scale-OUT**: Logs the event → HPA automatically scales up based on CPU/Memory

See `autoscaler-controller/README.md` for details.

### Option 3: External Metrics with HPA

Expose `ScaleSignal.targetReplicas` as a custom external metric:

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: socket-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: StatefulSet
    name: socket
  minReplicas: 3
  maxReplicas: 20
  metrics:
    - type: External
      external:
        metric:
          name: rtc_recommended_replicas
          selector:
            matchLabels:
              service: socket
        target:
          type: Value
          value: 1
```

## Quick Start with Current HPA

The included HPA in `deploy/k8s/socket-deploy.yaml` uses CPU/Memory metrics and **does not consume ScaleSignal**. To activate:

```bash
kubectl apply -f deploy/k8s/socket-deploy.yaml
```

This HPA will:
- Scale based on CPU utilization (> 70%)
- Scale based on Memory utilization (> 80%)
- Min 3 replicas, Max 20 replicas

## Testing Scale Signals

### 1. Check if signals are published:

```bash
# Consume from Kafka
kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic rtc.control.scale \
  --from-beginning
```

### 2. Generate load to trigger scaling:

```bash
# Create many WebSocket connections
for i in {1..1000}; do
  wscat -c "ws://localhost:8082/ws?userId=user$i" &
done
```

### 3. Monitor scaling decisions:

Check load-balancer logs:
```bash
docker logs reactive-rtc-load-balancer | grep "Scaling directive"
```

Expected output:
```
Scaling directive: action=SCALE_OUT, target=4, reason=Demand exceeds capacity...
```

## Next Steps

1. **Deploy to Kubernetes**: The HPA in `socket-deploy.yaml` will auto-scale
2. **Implement Kafka consumer**: Create operator to consume `ScaleSignal` from Kafka
3. **Monitor**: Use Grafana to watch scaling decisions in real-time

## Additional Resources

- [KEDA Documentation](https://keda.sh/)
- [Kubernetes HPA](https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/)
- See `AUTOSCALING_GUIDE.md` for detailed scaling algorithm

