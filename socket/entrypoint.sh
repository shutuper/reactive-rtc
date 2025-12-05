#!/bin/sh
# Entrypoint script for socket node
# - Sets hostname for DNS registration
# - Configures ulimits for 1M+ connections
# - Tunes kernel parameters for high connection count
# - Starts the Java application

set -e

echo "=== Socket Node Startup ==="

# Set hostname to pod name if POD_NAME is set
# This enables DNS: {POD_NAME}.socket.rtc.svc.cluster.local
if [ -n "$POD_NAME" ]; then
    echo "Setting hostname to: $POD_NAME"
    hostname "$POD_NAME" 2>/dev/null || echo "WARNING: Could not set hostname (requires NET_ADMIN)"
    echo "Hostname set successfully"
else
    echo "WARNING: POD_NAME not set, using default hostname"
fi

# Configure kernel parameters for high connection count
# These require NET_ADMIN capability and write access to /proc/sys
echo "Configuring kernel parameters..."

# TCP tuning for high connection count
sysctl_set() {
    if [ -w "/proc/sys/$1" ]; then
        echo "$2" > "/proc/sys/$1" 2>/dev/null && echo "  - $1 = $2" || echo "  - WARNING: Could not set $1"
    else
        echo "  - SKIP: $1 (read-only or not available)"
    fi
}

# Try to set network parameters (requires pod-level sysctls or privileged mode)
echo "Network kernel parameters:"
sysctl_set "net/core/rmem_max" "16777216"
sysctl_set "net/core/wmem_max" "16777216"
sysctl_set "net/core/rmem_default" "262144"
sysctl_set "net/core/wmem_default" "262144"
sysctl_set "net/ipv4/tcp_rmem" "4096 262144 16777216"
sysctl_set "net/ipv4/tcp_wmem" "4096 262144 16777216"
sysctl_set "net/ipv4/tcp_max_syn_backlog" "65535"
sysctl_set "net/ipv4/tcp_fin_timeout" "15"
sysctl_set "net/ipv4/tcp_keepalive_time" "60"
sysctl_set "net/ipv4/tcp_keepalive_intvl" "10"
sysctl_set "net/ipv4/tcp_keepalive_probes" "5"

echo ""

# Configure ulimits for high connection count (1M+ connections)
# Each WebSocket connection uses ~1 file descriptor
echo "Configuring ulimits for high connection count..."

# Try to set ulimits (requires SYS_RESOURCE capability or root)
# nofile: max open files (file descriptors)
# Target: 2M file descriptors to support 1M+ connections with headroom
if ulimit -n 2097152 2>/dev/null; then
    echo "  - nofile (open files): $(ulimit -n)"
else
    # Try a lower value
    if ulimit -n 1048576 2>/dev/null; then
        echo "  - nofile (open files): $(ulimit -n) (reduced)"
    else
        echo "  - WARNING: Could not set nofile limit (current: $(ulimit -n))"
    fi
fi

# nproc: max processes/threads
if ulimit -u 65535 2>/dev/null; then
    echo "  - nproc (processes): $(ulimit -u)"
else
    echo "  - WARNING: Could not set nproc limit (current: $(ulimit -u))"
fi

# memlock: max locked memory (for zero-copy networking)
if ulimit -l unlimited 2>/dev/null; then
    echo "  - memlock: unlimited"
else
    echo "  - WARNING: Could not set memlock limit"
fi

# Display current limits
echo ""
echo "Current ulimits:"
echo "  - Open files (nofile): $(ulimit -n)"
echo "  - Max processes (nproc): $(ulimit -u)"
echo "  - Stack size: $(ulimit -s)"
echo ""

# Display pod-level sysctls (set via Kubernetes)
echo "Pod-level sysctls (via Kubernetes):"
cat /proc/sys/net/core/somaxconn 2>/dev/null && echo "  - net.core.somaxconn = $(cat /proc/sys/net/core/somaxconn)" || true
cat /proc/sys/net/ipv4/ip_local_port_range 2>/dev/null && echo "  - net.ipv4.ip_local_port_range = $(cat /proc/sys/net/ipv4/ip_local_port_range)" || true
cat /proc/sys/net/ipv4/tcp_tw_reuse 2>/dev/null && echo "  - net.ipv4.tcp_tw_reuse = $(cat /proc/sys/net/ipv4/tcp_tw_reuse)" || true
echo ""

# JVM settings for high connection count
# -XX:+UseZGC: Low-latency garbage collector
# -XX:+ZGenerational: Generational ZGC for better throughput
# -XX:MaxRAMPercentage=75.0: Use 75% of container memory
# -Dio.netty.eventLoopThreads: Match CPU cores for Netty
# -Dio.netty.maxDirectMemory: Allow more direct memory for buffers
JAVA_OPTS="${JAVA_OPTS:-}"
JAVA_OPTS="$JAVA_OPTS -XX:+UseZGC"
JAVA_OPTS="$JAVA_OPTS -XX:+ZGenerational"
JAVA_OPTS="$JAVA_OPTS -XX:MaxRAMPercentage=75.0"
JAVA_OPTS="$JAVA_OPTS -Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true"
JAVA_OPTS="$JAVA_OPTS -Dio.netty.eventLoopThreads=${NETTY_THREADS:-0}"
JAVA_OPTS="$JAVA_OPTS -Djava.net.preferIPv4Stack=true"

# For very high connection counts, increase direct memory
# Each connection uses ~4KB of buffer space
# 1M connections * 4KB = 4GB direct memory
JAVA_OPTS="$JAVA_OPTS -XX:MaxDirectMemorySize=${MAX_DIRECT_MEMORY:-4g}"

echo "Starting Java application with options:"
echo "  $JAVA_OPTS"
echo ""

# Start the Java application
exec java $JAVA_OPTS -jar /app/app.jar

