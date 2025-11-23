# Multi-stage build for Socket module
FROM maven:3.9-eclipse-temurin-21 AS build

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

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy the shaded JAR
COPY --from=build /workspace/socket/target/socket-1.0-SNAPSHOT.jar app.jar

# Expose HTTP port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8080/healthz || exit 1

# Run the application
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]

