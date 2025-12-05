# Gatling Load Test Docker Image
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /app

# Copy pom.xml first for dependency caching
COPY pom.xml .

# Download dependencies
RUN mvn dependency:resolve -q

# Copy source code
COPY src ./src

# Build the jar with dependencies
RUN mvn package -DskipTests -q

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Install required tools
RUN apk add --no-cache curl bash netcat-openbsd

# Create directories
RUN mkdir -p /app/lib /app/results

# Copy built artifacts
COPY --from=build /app/target/gatling-load-test-*.jar /app/
COPY --from=build /app/target/dependency/ /app/lib/

# Set high ulimits for load testing
ENV GATLING_OPTS="-Xms512m -Xmx2g -XX:+UseZGC"

# Default test parameters
ENV CLIENTS=100
ENV RAMPUP=1
ENV DURATION=5
ENV INTERVAL=3
ENV GATEWAY_URL=http://nginx-gateway-service.rtc.svc.cluster.local
ENV WS_GATEWAY_URL=ws://nginx-gateway-service.rtc.svc.cluster.local
ENV CLIENT_PREFIX=k8s

# Entry point script
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

ENTRYPOINT ["/app/entrypoint.sh"]
