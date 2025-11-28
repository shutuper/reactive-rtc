# Dockerfile for Gatling Load Testing
FROM maven:3.9-eclipse-temurin-21

WORKDIR /test

# Copy pom and source
COPY pom.xml .
COPY src ./src

# Download dependencies first (for better caching)
RUN mvn dependency:go-offline

# Configure JVM for high-concurrency testing
ENV JAVA_OPTS="-XX:+UseZGC -Xms2g -Xmx4g -Dio.netty.eventLoopThreads=32"

# Mark that we're running in Docker (used by test to determine URLs)
ENV DOCKER_ENV=true

# Default Gatling parameters (can be overridden via environment variables)
ENV LB_URL=http://load-balancer:8080
ENV CLIENTS=50000
ENV RAMPUP=10
ENV DURATION=20
ENV INTERVAL=3

# Run Gatling test
ENTRYPOINT mvn gatling:test \
    -Dlb.url=${LB_URL} \
    -Dclients=${CLIENTS} \
    -Drampup=${RAMPUP} \
    -Dduration=${DURATION} \
    -Dinterval=${INTERVAL}

