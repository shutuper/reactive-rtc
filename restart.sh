#!/bin/bash
# Cleanup and restart script for reactive-rtc

echo "==> Stopping all containers..."
docker-compose down -v

echo ""
echo "==> Cleaning Docker system..."
docker system prune -f

echo ""
echo "==> Removing old volumes..."
docker volume rm reactive-rtc_zookeeper-data reactive-rtc_zookeeper-log reactive-rtc_kafka-data reactive-rtc_redis-data 2>/dev/null || true

echo ""
echo "==> Rebuilding and starting services..."
docker-compose up --build -d

echo ""
echo "==> Waiting for services to start..."
sleep 10

echo ""
echo "==> Checking container status..."
docker-compose ps

echo ""
echo "==> Checking logs for errors..."
echo ""
echo "--- Zookeeper ---"
docker logs zookeeper --tail 20

echo ""
echo "--- Kafka ---"
docker logs kafka --tail 20

echo ""
echo "--- Redis ---"
docker logs redis --tail 20

echo ""
echo "--- Load Balancer ---"
docker logs load-balancer --tail 20

echo ""
echo "--- Socket 1 ---"
docker logs socket-1 --tail 20

echo ""
echo "--- Socket 2 ---"
docker logs socket-2 --tail 20

echo ""
echo "==> Done! Check the logs above for any errors."
echo ""
echo "To follow logs in real-time, run:"
echo "  docker-compose logs -f"

