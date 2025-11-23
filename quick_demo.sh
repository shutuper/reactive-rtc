#!/bin/bash

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  🚀 REACTIVE RTC - QUICK DEMO SCRIPT"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo

# Step 1: Remove old network if it exists, then create fresh
echo "1️⃣  Setting up Docker network..."
docker network rm reactive-rtc-network 2>/dev/null || true
docker network create reactive-rtc-network

# Step 2: Start everything
echo "2️⃣  Starting all services..."
docker compose -f docker-compose.yml \
               -f deploy/docker-compose.prometheus.yml up -d

echo
echo "⏳ Waiting 10 seconds for services to initialize..."
sleep 10

# Step 3: Verify services
echo
echo "3️⃣  Verifying services..."
echo

curl -s http://localhost:8081/healthz > /dev/null && echo "✅ Load-Balancer: OK" || echo "❌ Load-Balancer: DOWN"
curl -s http://localhost:8080/healthz > /dev/null && echo "✅ Socket-1: OK" || echo "❌ Socket-1: DOWN"
curl -s http://localhost:8082/healthz > /dev/null && echo "✅ Socket-2: OK" || echo "❌ Socket-2: DOWN"
curl -s http://localhost:9090/-/healthy > /dev/null && echo "✅ Prometheus: OK" || echo "❌ Prometheus: DOWN"
curl -s http://localhost:3000/api/health > /dev/null && echo "✅ Grafana: OK" || echo "❌ Grafana: DOWN"

echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  📊 ACCESS YOUR SERVICES"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo
echo "📍 Grafana:     http://localhost:3000 (admin/admin)"
echo "📍 Prometheus:  http://localhost:9090"
echo "📍 Socket-1:    http://localhost:8080"
echo "📍 Socket-2:    http://localhost:8082"
echo "📍 Load-Balancer: http://localhost:8081"
echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  🎯 NEXT STEPS"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo
echo "1. Open Grafana: http://localhost:3000"
echo "2. Login: admin/admin"
echo "3. Add Prometheus data source: http://prometheus:9090"
echo "4. Create dashboard using queries from METRICS_GUIDE.md"
echo
echo "📖 For full instructions, see: FULL_SYSTEM_DEMO.md"
echo
