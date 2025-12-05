# Standalone Gatling Load Test for Reactive RTC
# Can be run on any machine with Docker
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy only gatling module (no parent pom needed)
COPY gatling-load-test/pom.xml /app/
COPY gatling-load-test/src /app/src

# Build the jar with dependencies
RUN mvn dependency:resolve -q
RUN mvn package -DskipTests -q

# Runtime image
FROM eclipse-temurin:21-jre

WORKDIR /app

# Install required tools
RUN apt-get update && apt-get install -y curl netcat-openbsd dnsutils && rm -rf /var/lib/apt/lists/*

# Create lib directory
RUN mkdir -p /app/lib

# Copy built artifacts
COPY --from=build /app/target/gatling-load-test-*.jar /app/
COPY --from=build /app/target/dependency/ /app/lib/
COPY gatling-load-test/docker-standalone/entrypoint.sh /app/

RUN chmod +x /app/entrypoint.sh

# Environment variables with defaults
ENV TARGET_HOST="host.docker.internal" \
    TARGET_PORT="8080" \
    TOTAL_CLIENTS="100" \
    RAMPUP_MINUTES="1" \
    DURATION_MINUTES="5" \
    MESSAGE_INTERVAL_MS="5000" \
    CLIENT_PREFIX="docker" \
    WS_PROTOCOL="ws"

# Increase file descriptors for high connection count
RUN echo "* soft nofile 1048576" >> /etc/security/limits.conf && \
    echo "* hard nofile 1048576" >> /etc/security/limits.conf

ENTRYPOINT ["/app/entrypoint.sh"]





