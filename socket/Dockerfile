# Multi-stage build for Socket module
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /workspace

# Copy parent POM and create a filtered version without load-balancer module
COPY pom.xml /tmp/pom.xml
RUN sed '/load-balancer/d' /tmp/pom.xml > pom.xml

# Copy core module first (for caching)
COPY core/pom.xml core/
COPY core/src core/src

# Copy socket module
COPY socket/pom.xml socket/
COPY socket/src socket/src

# Build with Maven
RUN mvn clean package -DskipTests

# Runtime stage - Use full JDK for Virtual Threads support (non-Alpine for better compatibility)
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the shaded JAR
COPY --from=build /workspace/socket/target/socket-1.0-SNAPSHOT.jar app.jar

# Copy entrypoint script that sets hostname dynamically
COPY socket/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# Expose HTTP port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8080/healthz || exit 1

# Use entrypoint script to set hostname before starting JVM
ENTRYPOINT ["/app/entrypoint.sh"]

