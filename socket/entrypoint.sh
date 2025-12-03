#!/bin/sh
# Entrypoint script for socket node
# Sets the hostname to POD_NAME for DNS registration with headless service

set -e

# Set hostname to pod name if POD_NAME is set
# This enables DNS: {POD_NAME}.socket.rtc.svc.cluster.local
if [ -n "$POD_NAME" ]; then
    echo "Setting hostname to: $POD_NAME"
    hostname "$POD_NAME"
    echo "Hostname set successfully"
else
    echo "WARNING: POD_NAME not set, using default hostname"
fi

# Start the Java application
exec java \
    -XX:+UseZGC \
    -XX:+ZGenerational \
    -XX:MaxRAMPercentage=75.0 \
    -Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true \
    -jar /app/app.jar

