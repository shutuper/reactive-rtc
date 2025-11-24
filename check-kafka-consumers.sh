#!/bin/bash

# Kafka Consumer Groups Diagnostic Script
# This script helps identify why only some nodes appear in Kafka metrics

set -e

echo "========================================="
echo "  Kafka Consumer Groups Diagnostic"
echo "========================================="
echo ""

echo "1. Socket Nodes Status"
echo "----------------------------------------"
docker-compose ps 2>/dev/null | grep socket || kubectl get pods -n reactive-rtc 2>/dev/null | grep socket
echo ""

echo "2. Kafka Consumer Groups (Expected: socket-delivery-socket-node-1, socket-delivery-socket-node-2)"
echo "----------------------------------------"
docker exec kafka kafka-consumer-groups --bootstrap-server localhost:9092 --list 2>/dev/null | grep "socket-delivery" || echo "⚠️  No consumer groups found or Kafka not accessible"
echo ""

echo "3. Kafka Topics (Expected: delivery_node_socket-node-1, delivery_node_socket-node-2)"
echo "----------------------------------------"
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --list 2>/dev/null | grep "delivery_node" || echo "⚠️  No delivery topics found"
echo ""

echo "4. Kafka Exporter Metrics - Consumer Group Members"
echo "----------------------------------------"
curl -s http://localhost:9308/metrics 2>/dev/null | grep 'kafka_consumergroup_members{consumergroup="socket-delivery' || echo "⚠️  Kafka Exporter not accessible"
echo ""

echo "5. Kafka Exporter Metrics - Consumer Lag"
echo "----------------------------------------"
curl -s http://localhost:9308/metrics 2>/dev/null | grep 'kafka_consumergroup_lag_sum{consumergroup="socket-delivery' || echo "⚠️  No lag metrics found"
echo ""

echo "6. Socket Node Logs - Kafka Connection Status"
echo "----------------------------------------"
echo "Socket-1:"
docker logs socket-1 2>&1 | grep -i "kafka consumers started" | tail -1 || echo "  ⚠️  Socket-1: No 'Kafka consumers started' log found"

echo "Socket-2:"
docker logs socket-2 2>&1 | grep -i "kafka consumers started" | tail -1 || echo "  ⚠️  Socket-2: No 'Kafka consumers started' log found"
echo ""

echo "7. Prometheus Query Test"
echo "----------------------------------------"
echo "Query: kafka_consumergroup_lag_sum{consumergroup=~\"socket-delivery-.*\"}"
curl -s 'http://localhost:9090/api/v1/query?query=kafka_consumergroup_lag_sum%7Bconsumergroup%3D~%22socket-delivery-.*%22%7D' 2>/dev/null | jq -r '.data.result[] | "  \(.metric.consumergroup): \(.value[1])"' || echo "⚠️  Prometheus not accessible"
echo ""

echo "========================================="
echo "  Diagnosis Summary"
echo "========================================="
echo ""

# Count consumer groups
CONSUMER_COUNT=$(docker exec kafka kafka-consumer-groups --bootstrap-server localhost:9092 --list 2>/dev/null | grep "socket-delivery" | wc -l || echo "0")

echo "Active consumer groups: $CONSUMER_COUNT"
echo ""

if [ "$CONSUMER_COUNT" -lt 2 ]; then
    echo "⚠️  WARNING: Expected 2+ consumer groups, found $CONSUMER_COUNT"
    echo ""
    echo "Possible causes:"
    echo "  1. Some socket nodes aren't running"
    echo "  2. Some socket nodes failed to connect to Kafka"
    echo "  3. Some socket nodes haven't consumed any messages yet"
    echo ""
    echo "Recommended actions:"
    echo "  1. Check socket node logs: docker logs socket-1"
    echo "  2. Restart socket nodes: docker-compose restart socket-1 socket-2"
    echo "  3. Verify Kafka connectivity: docker exec socket-1 nc -zv kafka 9092"
else
    echo "✓ All consumer groups active"
fi

