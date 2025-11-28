# Multi-stage build for Load-Balancer module
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /workspace

# Copy parent POM and create a filtered version without socket module
COPY pom.xml /tmp/pom.xml
RUN sed '/socket/d' /tmp/pom.xml > pom.xml

# Copy core module first (for caching)
COPY core/pom.xml core/
COPY core/src core/src

# Copy load-balancer module
COPY load-balancer/pom.xml load-balancer/
COPY load-balancer/src load-balancer/src

# Build with Maven
RUN mvn clean package -DskipTests

# Runtime stage - Use full JDK for better compatibility (non-Alpine for virtual threads)
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the shaded JAR
COPY --from=build /workspace/load-balancer/target/load-balancer-1.0-SNAPSHOT.jar app.jar

# Expose HTTP port
EXPOSE 8081

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8081/healthz || exit 1

# Run the application with Virtual Threads enabled
ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true", \
    "-jar", "app.jar"]

