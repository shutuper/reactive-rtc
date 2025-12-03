# Socket Nodes: Deployment Architecture

## Configuration Choice: Deployment (NOT StatefulSet)

The socket nodes use **Kubernetes Deployment** instead of StatefulSet to enable optimal pod selection during scale-down operations.

---

## Why Deployment?

### ✅ Pod Deletion Costs Work
```yaml
# Scale-in scenario with Deployment:
1. Load-balancer detects low load
2. Master updates pod deletion costs:
   - socket-abc12: 50 connections  → cost = 50
   - socket-def34: 500 connections → cost = 500
   - socket-ghi56: 100 connections → cost = 100

3. Master scales Deployment: 3 → 2 replicas

4. Kubernetes RESPECTS deletion costs
   → Terminates socket-abc12 (lowest cost = 50 connections) ✅ OPTIMAL

5. PreStop hook calls /drain
6. 5-minute graceful draining
7. Pod terminates after drain complete
```

### ❌ StatefulSet Problem
```yaml
# Scale-in scenario with StatefulSet:
1. Load-balancer updates pod deletion costs:
   - socket-0: 50 connections  → cost = 50
   - socket-1: 500 connections → cost = 500
   - socket-2: 100 connections → cost = 100

2. Master scales StatefulSet: 3 → 2 replicas

3. Kubernetes IGNORES deletion costs
   → Always terminates socket-2 (highest ordinal) ❌
   → May have 100 connections (not optimal)

4. PreStop hook calls /drain
5. 5-minute graceful draining
6. Pod terminates after drain complete
```

---

## DNS Resolution

### With Deployment + Subdomain

Each pod gets stable DNS via the `subdomain: socket` field:

```yaml
spec:
  template:
    spec:
      subdomain: socket  # References headless service
```

**Result**:
- Pod name: `socket-7d9f8b6c5-abc12` (random)
- DNS: `socket-7d9f8b6c5-abc12.socket.rtc.svc.cluster.local` ✅
- Nginx can route to: `/ws/socket-7d9f8b6c5-abc12/connect`

### Nginx Configuration

Nginx regex pattern handles both formats:

```nginx
location ~ ^/ws/(?<podid>[a-zA-Z0-9-]+)/connect$ {
  set $socket_upstream "http://${podid}.socket.rtc.svc.cluster.local:8080";
  rewrite ^/ws/[^/]+/connect$ /ws/connect break;
  proxy_pass $socket_upstream;
}
```

**Works with**:
- StatefulSet names: `socket-0`, `socket-1`, `socket-2`
- Deployment names: `socket-7d9f8b6c5-abc12`, `socket-6f8a9d4b3-xyz78`

---

## Benefits Summary

| Feature | StatefulSet | Deployment |
|---------|-------------|------------|
| **Stable pod names** | ✅ Yes (`socket-0`) | ❌ No (random) |
| **Stable DNS** | ✅ Built-in | ✅ Via subdomain |
| **Pod deletion costs** | ❌ Ignored | ✅ Respected |
| **Optimal scale-down** | ❌ No | ✅ Yes |
| **Least-loaded pod removed** | ❌ No | ✅ Yes |
| **Debugging ease** | ✅ Easy | ⚠️ Harder |
| **Production optimization** | ⚠️ OK | ✅ Better |

---

## Configuration

### Environment Variables (ConfigMap)

```yaml
SOCKET_WORKLOAD_NAME: "socket"     # Name of Deployment to scale
SOCKET_MIN_REPLICAS: "2"           # Never scale below this
SOCKET_MAX_REPLICAS: "50"          # Never scale above this
```

### Deployment Spec

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: socket
spec:
  replicas: 2
  template:
    spec:
      subdomain: socket  # ← CRITICAL for stable DNS
      terminationGracePeriodSeconds: 330
      containers:
        - name: socket
          lifecycle:
            preStop:
              httpGet:
                path: /drain  # ← Graceful shutdown
```

---

## Testing

### Verify DNS Resolution

```bash
# Get a socket pod name
SOCKET_POD=$(kubectl get pods -n rtc -l app=socket -o jsonpath='{.items[0].metadata.name}')
echo "Pod name: $SOCKET_POD"

# Test DNS from nginx pod
NGINX_POD=$(kubectl get pods -n rtc -l app=nginx-gateway -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n rtc $NGINX_POD -- nslookup ${SOCKET_POD}.socket.rtc.svc.cluster.local

# Should return the pod's IP ✅
```

### Verify Pod Deletion Costs

```bash
# View deletion costs
kubectl get pods -n rtc -l app=socket -o json | \
  jq '.items[] | {
    name: .metadata.name, 
    deletionCost: .metadata.annotations["controller.kubernetes.io/pod-deletion-cost"]
  }'

# Expected output:
# { "name": "socket-abc12", "deletionCost": "50" }   ← Will be removed first
# { "name": "socket-def34", "deletionCost": "500" }  ← Will be kept
# { "name": "socket-ghi56", "deletionCost": "100" }
```

### Test Scaling

```bash
# Watch deployment scaling
kubectl get deployment socket -n rtc --watch

# View scaling logs
kubectl logs -n rtc -l app=load-balancer -f | grep -i "scaling deployment"

# Expected:
# INFO [StatefulSetScalerService] Scaling Deployment socket from 2 to 5 replicas
# INFO [StatefulSetScalerService] ✓ Successfully scaled Deployment socket to 5 replicas
```

---

## Conclusion

**Deployment is the optimal choice** for socket nodes because:

1. ✅ **Pod deletion costs work** - Least-loaded pods removed first
2. ✅ **Stable DNS still works** - Via subdomain field
3. ✅ **Minimizes user disruption** - Terminates idle pods, keeps busy pods
4. ✅ **Production-optimized** - Better resource utilization
5. ✅ **Drain works correctly** - PreStop hook called for any pod

The only trade-off is random pod names, which is acceptable for the significant benefits gained.

**All configurations updated** to use Deployment instead of StatefulSet! 🚀

