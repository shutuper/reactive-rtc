# Quick Metrics Viewing Guide

## 🚀 View Metrics in 3 Steps

### Step 1: Start the System
```bash
docker-compose up -d
```

### Step 2: View Raw Metrics
```bash
# Socket node metrics
curl http://localhost:8080/metrics

# Load-balancer metrics
curl http://localhost:8081/metrics
```

### Step 3: Start Prometheus + Grafana
```bash
docker-compose -f docker-compose.yml -f deploy/docker-compose.prometheus.yml up -d
```

**Access:**
- 📊 **Prometheus**: http://localhost:9090
- 📈 **Grafana**: http://localhost:3000 (admin/admin)

---

## Sample Queries

In Prometheus UI (http://localhost:9090), try:

**Active connections:**
```
rtc_socket_active_connections
```

**Message rate:**
```
rate(rtc_socket_deliver_mps[5m])
```

**p95 latency:**
```
histogram_quantile(0.95, rate(rtc_socket_latency_bucket[5m]))
```

---

## Full Guide

See **[METRICS_GUIDE.md](METRICS_GUIDE.md)** for complete documentation.
