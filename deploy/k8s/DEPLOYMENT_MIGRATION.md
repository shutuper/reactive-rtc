# ✅ Socket Nodes Now Use Deployment

## Changes Made

All references to StatefulSet for socket nodes have been replaced with Deployment.

---

## Why Deployment?

### ✅ Pod Deletion Costs Work Correctly

**With StatefulSet** (before):
```
Scale-in: 5 → 4 replicas
- Master updates deletion costs: socket-0=500, socket-1=100, socket-4=50
- Kubernetes IGNORES costs
- Always terminates socket-4 (highest ordinal)
- Result: May remove a busy pod ❌
```

**With Deployment** (after):
```
Scale-in: 5 → 4 replicas
- Master updates deletion costs: socket-xxx=500, socket-yyy=100, socket-zzz=50
- Kubernetes RESPECTS costs
- Terminates socket-zzz (lowest deletion cost = 50 connections)
- Result: Always removes least-loaded pod ✅
```

### ✅ Stable DNS Still Works

**Key**: Set `subdomain: socket` in pod spec + use headless service

**DNS Resolution**:
- Pod name: `socket-7d9f8b6c5-abc12`
- DNS: `socket-7d9f8b6c5-abc12.socket.rtc.svc.cluster.local`
- Nginx routes: `/ws/socket-7d9f8b6c5-abc12/connect` → pod DNS

**Example Connection Flow**:
1. Client: `GET /ws/connect?clientId=user123`
2. Load-balancer: Returns `{"socketNodeId": "socket-7d9f8b6c5-abc12", ...}`
3. Client: `ws://<nginx-ip>/ws/socket-7d9f8b6c5-abc12/connect`
4. Nginx: Extracts `socket-7d9f8b6c5-abc12`, resolves DNS, proxies to pod

---

## Files Modified

### 1. `deploy/k8s/04-socket.yaml`
**Changed**: `kind: StatefulSet` → `kind: Deployment`
**Added**: `subdomain: socket` in pod spec
**Result**: Deployment that respects pod deletion costs + stable DNS

### 2. `deploy/k8s/02-config.yaml`
**Changed**: `SOCKET_STATEFULSET_NAME` → `SOCKET_WORKLOAD_NAME`
**Reason**: Generic name works for both Deployment and StatefulSet

### 3. `deploy/k8s/03-load-balancer.yaml`
**Changed**: Environment variable name to `SOCKET_WORKLOAD_NAME`
**Result**: Load-balancer can scale any workload type

### 4. `deploy/k8s/05-keda-scaler.yaml`
**Changed**: `kind: StatefulSet` → `kind: Deployment` in scaleTargetRef
**Result**: KEDA backup scaler targets Deployment

### 5. `load-balancer/src/.../LBConfig.java`
**Changed**: `socketStatefulSetName` → `socketWorkloadName`
**Result**: Configuration works with any workload type

### 6. `load-balancer/src/.../LoadBalancerApp.java`
**Changed**: Method call to use `config.getSocketWorkloadName()`
**Result**: Instantiates scaler with correct workload name

### 7. `load-balancer/src/.../k8s/StatefulSetScalerService.java`
**Changed**: Try Deployment first, StatefulSet as fallback
**Result**: Automatically detects and scales correct workload type

### 8. `deploy/k8s/06-nginx-gateway.yaml`
**Updated**: Comments to clarify it works with both workload types
**Result**: Nginx routes to any pod name format

---

## How It Works

### Pod Creation (Deployment)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: socket
spec:
  replicas: 2
  template:
    spec:
      subdomain: socket  # ← This is the key!
      containers:
        - name: socket
          env:
            - name: NODE_ID
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name  # Uses actual pod name
```

**Result**: Pod gets:
- Name: `socket-7d9f8b6c5-abc12` (random from ReplicaSet)
- NODE_ID env var: `socket-7d9f8b6c5-abc12`
- DNS (via subdomain): `socket-7d9f8b6c5-abc12.socket.rtc.svc.cluster.local`

### Scale-Down with Pod Deletion Costs

```java
// In LoadBalancer.java
private Mono<Void> publishScalingSignal(ScalingDecision scalingDecision, List<NodeEntry> entries) {
    // 1. Calculate deletion costs (activeConnections)
    Map<String, Integer> nodeConnectionCounts = entries.stream()
        .collect(Collectors.toMap(
            NodeEntry::nodeId,
            e -> e.lastHeartbeat.getActiveConn()
        ));
    
    // 2. Update pod annotations
    podDeletionCostService.updatePodDeletionCosts(nodeConnectionCounts);
    // Example result:
    //   socket-xxx: cost=500 (busy)
    //   socket-yyy: cost=100 (moderate)
    //   socket-zzz: cost=50  (idle) ← Will be terminated
    
    // 3. Scale Deployment
    socketNodeScalerService.scaleStatefulSet(-1, reason);
    // Kubernetes selects socket-zzz (lowest cost)
    
    // 4. PreStop hook → /drain → 5-min draining → termination
}
```

### Nginx Routing

```nginx
# Pattern: /ws/{podId}/connect
location ~ ^/ws/(?<podid>[a-zA-Z0-9-]+)/connect$ {
  # Works with any pod name format:
  # - StatefulSet: socket-0, socket-1, socket-2
  # - Deployment: socket-7d9f8b6c5-abc12, socket-9f8b6c5d7-xyz89
  
  set $socket_upstream "http://${podid}.socket.rtc.svc.cluster.local:8080";
  rewrite ^/ws/[^/]+/connect$ /ws/connect break;
  proxy_pass $socket_upstream;
}
```

---

## Deployment Instructions

### New Deployments

```bash
cd deploy/k8s
./deploy.sh
```

The script will deploy:
1. RBAC
2. ConfigMap (with `SOCKET_WORKLOAD_NAME`)
3. Load-balancer (with workload scaling)
4. **Socket Deployment** (not StatefulSet)
5. KEDA backup scaler (targets Deployment)
6. Nginx gateway
7. Prometheus
8. Monitoring (Grafana + Kafka Exporter)

### Migrating from StatefulSet

If you already have a StatefulSet deployed:

```bash
# 1. Backup
kubectl get statefulset socket -n rtc -o yaml > backup-statefulset.yaml

# 2. Delete StatefulSet (keep pods running)
kubectl delete statefulset socket -n rtc --cascade=orphan

# 3. Apply Deployment
kubectl apply -f deploy/k8s/04-socket.yaml

# 4. Deployment will adopt existing pods (matching labels)
# New pods will be created with subdomain field

# 5. Gradually replace old pods
kubectl delete pod socket-0 -n rtc  # Will be replaced by Deployment pod
kubectl delete pod socket-1 -n rtc
# etc.

# 6. Verify
kubectl get deployment socket -n rtc
kubectl get pods -n rtc -l app=socket
```

---

## Verification

### Check DNS Resolution

```bash
# Get a socket pod name
POD_NAME=$(kubectl get pods -n rtc -l app=socket -o jsonpath='{.items[0].metadata.name}')

# Test DNS from nginx pod
kubectl exec -n rtc -l app=nginx-gateway -- nslookup ${POD_NAME}.socket.rtc.svc.cluster.local

# Should return the pod IP
```

### Check Pod Deletion Costs

```bash
# View deletion costs
kubectl get pods -n rtc -l app=socket -o json | \
  jq '.items[] | {
    name: .metadata.name, 
    deletionCost: .metadata.annotations["controller.kubernetes.io/pod-deletion-cost"]
  }'

# Expected output:
# { "name": "socket-7d9f8b6c5-abc12", "deletionCost": "500" }
# { "name": "socket-9f8b6c5d7-xyz89", "deletionCost": "100" }
# { "name": "socket-1a2b3c4d5-pqr78", "deletionCost": "50" }
```

### Test Scaling

```bash
# Watch Deployment scaling
kubectl get deployment socket -n rtc --watch

# Trigger scale decision (increase load or wait for automatic decision)

# View logs
kubectl logs -n rtc -l app=load-balancer -f | grep -i "scaling deployment"

# Expected output:
# INFO [StatefulSetScalerService] Scaling Deployment socket from 2 to 5 replicas
# INFO [StatefulSetScalerService] ✓ Successfully scaled Deployment socket to 5 replicas
```

### Test Scale-Down with Deletion Costs

```bash
# 1. Note current deletion costs
kubectl get pods -n rtc -l app=socket -o json | jq '.items[] | {name: .metadata.name, cost: .metadata.annotations["controller.kubernetes.io/pod-deletion-cost"], ready: .status.conditions[] | select(.type=="Ready") | .status}'

# 2. Reduce load and wait for scale-in decision

# 3. Watch which pod gets terminated
kubectl get events -n rtc --watch | grep socket

# 4. Should see pod with LOWEST deletion cost being terminated
# Example: socket-1a2b3c4d5-pqr78 (cost=50) terminated first ✅
```

---

## Summary

✅ **Socket nodes now use Deployment** instead of StatefulSet
✅ **Pod deletion costs work** - least-loaded pods removed first
✅ **Stable DNS works** - via `subdomain: socket` field
✅ **Nginx routing unchanged** - works with any pod name format
✅ **Draining works** - preStop hook calls `/drain` endpoint
✅ **Load-balancer scales directly** - via Kubernetes API
✅ **KEDA as backup** - now targets Deployment, not StatefulSet

**Key Benefits**:
- 🎯 Optimal pod selection during scale-down
- 🎯 Minimizes user disruption
- 🎯 Respects load-balancer's intelligence
- 🎯 Stable DNS for nginx routing
- 🎯 All features preserved

Everything is production-ready with Deployment! 🚀


