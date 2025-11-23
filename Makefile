.PHONY: build clean test run-infra run-socket run-lb stop help

# Default target
help:
	@echo "Reactive RTC - Make targets:"
	@echo "  make build        - Build all modules (skip tests)"
	@echo "  make test         - Run all tests"
	@echo "  make run-infra    - Start Kafka + Redis in Docker"
	@echo "  make run-socket   - Run socket node 1 locally"
	@echo "  make run-lb       - Run load-balancer locally"
	@echo "  make stop         - Stop all Docker containers"
	@echo "  make clean        - Clean all build artifacts"

# Build all modules
build:
	mvn clean package -DskipTests

# Run tests
test:
	mvn verify

# Clean build artifacts
clean:
	mvn clean
	rm -rf .m2-local

# Start infrastructure (Kafka + Redis)
run-infra:
	docker-compose up -d zookeeper kafka redis
	@echo "Waiting for Kafka to be ready..."
	@sleep 20
	@echo "Infrastructure ready!"

# Run load-balancer
run-lb: build
	@echo "Starting load-balancer..."
	cd load-balancer && \
	export KAFKA_BOOTSTRAP=localhost:29092 && \
	export RING_SECRET=my-secret-key-for-demo && \
	java -jar target/load-balancer-1.0-SNAPSHOT.jar

# Run socket node 1
run-socket: build
	@echo "Starting socket-node-1..."
	cd socket && \
	export NODE_ID=socket-node-1 && \
	export PUBLIC_WS_URL=ws://localhost:8080/ws && \
	export HTTP_PORT=8080 && \
	export KAFKA_BOOTSTRAP=localhost:29092 && \
	export REDIS_URL=redis://localhost:6379 && \
	export RING_SECRET=my-secret-key-for-demo && \
	java -jar target/socket-1.0-SNAPSHOT.jar

# Run socket node 2
run-socket2: build
	@echo "Starting socket-node-2..."
	cd socket && \
	export NODE_ID=socket-node-2 && \
	export PUBLIC_WS_URL=ws://localhost:8082/ws && \
	export HTTP_PORT=8082 && \
	export KAFKA_BOOTSTRAP=localhost:29092 && \
	export REDIS_URL=redis://localhost:6379 && \
	export RING_SECRET=my-secret-key-for-demo && \
	java -jar target/socket-1.0-SNAPSHOT.jar

# Stop all containers
stop:
	docker-compose down

# Run full stack with Docker Compose
docker-run: build
	docker-compose up --build











