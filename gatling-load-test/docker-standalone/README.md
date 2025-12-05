# Standalone Gatling Load Test for Reactive RTC

Run Gatling load tests from any PC with Docker against a remote Reactive RTC instance.

**Works on:** Linux, macOS, Windows (with Docker Desktop)

## Quick Start

### On the Server PC (running Minikube)

1. **Expose the service to your network:**

```bash
cd deploy/k8s
chmod +x expose-to-network.sh
./expose-to-network.sh
```

This will:
- Start port-forwarding on all network interfaces
- Display your local IP address
- Show the URL to use from other PCs

2. **Note your IP address** (e.g., `192.168.1.100`)

3. **Open firewall port 8080** if needed:
   - **macOS**: System Preferences → Security & Privacy → Firewall → Firewall Options → Add rule
   - **Windows**: Windows Defender Firewall → Inbound Rules → New Rule → Port 8080
   - **Linux**: `sudo ufw allow 8080/tcp`

### On the Test PC (running Gatling)

1. **Copy this folder** to the test PC:
```bash
# From the test PC, copy via scp:
scp -r user@server-pc:/path/to/reactive-rtc/gatling-load-test/docker-standalone .

# Or just copy these files manually:
# - Dockerfile
# - entrypoint.sh
# - run-tests.sh      (Linux/macOS)
# - run-tests.ps1     (Windows PowerShell)
# - run-tests.bat     (Windows CMD)
# - docker-compose.yml
# - README.md
```

2. **Install Docker** if not already installed:
```bash
# macOS
brew install --cask docker

# Ubuntu/Debian
curl -fsSL https://get.docker.com | sh

# Windows: Download Docker Desktop
```

3. **Run the tests:**

### Linux / macOS
```bash
cd docker-standalone
chmod +x run-tests.sh

# Basic test (1000 clients, 5 minutes)
./run-tests.sh -h 192.168.1.100

# Heavy test (10K clients, 10 minutes)
./run-tests.sh -h 192.168.1.100 -c 10000 -d 10 -r 3

# Multiple parallel instances (3 instances × 10K = 30K clients)
./run-tests.sh -h 192.168.1.100 -c 10000 -n 3

# Maximum load (10 instances × 100K = 1M clients)
./run-tests.sh -h 192.168.1.100 -c 100000 -n 10 -d 15 -r 5
```

### Windows (PowerShell)
```powershell
cd docker-standalone

# Basic test
.\run-tests.ps1 -TargetHost 192.168.1.100

# Heavy test
.\run-tests.ps1 -TargetHost 192.168.1.100 -Clients 10000 -Duration 10 -RampUp 3

# Multiple instances (3 × 10K = 30K clients)
.\run-tests.ps1 -TargetHost 192.168.1.100 -Clients 10000 -Instances 3

# Maximum load
.\run-tests.ps1 -TargetHost 192.168.1.100 -Clients 100000 -Instances 10 -Duration 15 -RampUp 5
```

### Windows (Command Prompt)
```cmd
cd docker-standalone

REM Basic test
run-tests.bat 192.168.1.100

REM 3 instances, 10K clients each
run-tests.bat 192.168.1.100 10000 3

REM Heavy load: 50K clients, 5 instances, 10 min duration, 3 min rampup
run-tests.bat 192.168.1.100 50000 5 10 3
```

## Command Line Options

### Linux/macOS (run-tests.sh)
```
Usage: ./run-tests.sh -h <host> [options]

Required:
  -h, --host HOST       Target host IP (e.g., 192.168.1.100)

Options:
  -p, --port PORT       Target port (default: 8080)
  -c, --clients NUM     Clients per instance (default: 1000)
  -r, --rampup MIN      Ramp-up time in minutes (default: 2)
  -d, --duration MIN    Test duration in minutes (default: 5)
  -i, --interval MS     Message interval in ms (default: 5000)
  -n, --instances NUM   Parallel test instances (default: 1)
  -s, --secure          Use WSS/HTTPS instead of WS/HTTP
  -b, --build           Force rebuild Docker image
```

### Windows PowerShell (run-tests.ps1)
```powershell
.\run-tests.ps1 -TargetHost <host> [options]

Required:
  -TargetHost    Target host IP (e.g., 192.168.1.100)

Options:
  -Port          Target port (default: 8080)
  -Clients       Clients per instance (default: 1000)
  -RampUp        Ramp-up time in minutes (default: 2)
  -Duration      Test duration in minutes (default: 5)
  -Interval      Message interval in ms (default: 5000)
  -Instances     Parallel test instances (default: 1)
  -Secure        Use WSS/HTTPS instead of WS/HTTP
  -Build         Force rebuild Docker image
```

### Windows CMD (run-tests.bat)
```cmd
run-tests.bat <host> [clients] [instances] [duration] [rampup]

Arguments (positional):
  host       Target host IP (required)
  clients    Clients per instance (default: 1000)
  instances  Parallel test instances (default: 1)
  duration   Test duration in minutes (default: 5)
  rampup     Ramp-up time in minutes (default: 2)
```

## Running Multiple Tests in Parallel

### Method 1: Using run-tests.sh

```bash
# 5 parallel instances, 50K clients each = 250K total
./run-tests.sh -h 192.168.1.100 -c 50000 -n 5 -d 10
```

### Method 2: Using docker-compose

```bash
# Set environment and scale
export TARGET_HOST=192.168.1.100
export TOTAL_CLIENTS=50000
export DURATION_MINUTES=10

docker-compose up --scale gatling=5
```

### Method 3: Manual Docker commands

```bash
# Run 3 tests in parallel with different prefixes
for i in 1 2 3; do
  docker run -d \
    --name gatling-test-$i \
    -e TARGET_HOST=192.168.1.100 \
    -e TOTAL_CLIENTS=50000 \
    -e CLIENT_PREFIX="test-$i" \
    --ulimit nofile=1048576:1048576 \
    gatling-load-test:latest
done

# Follow logs
docker logs -f gatling-test-1

# Stop all
docker stop gatling-test-1 gatling-test-2 gatling-test-3
```

## Building the Image Manually

If you need to build the image without running tests:

```bash
# From project root
docker build -t gatling-load-test:latest \
  -f gatling-load-test/docker-standalone/Dockerfile .

# Or from this directory
docker build -t gatling-load-test:latest -f Dockerfile ../..
```

## Recommended Test Scenarios

### Smoke Test (Verify connectivity)
```bash
./run-tests.sh -h 192.168.1.100 -c 10 -d 1 -r 1
```

### Light Load (Development)
```bash
./run-tests.sh -h 192.168.1.100 -c 1000 -d 5
```

### Medium Load (Integration)
```bash
./run-tests.sh -h 192.168.1.100 -c 10000 -d 10 -n 2
```

### Heavy Load (Performance)
```bash
./run-tests.sh -h 192.168.1.100 -c 50000 -d 15 -r 5 -n 3
```

### Stress Test (1M+ connections)
```bash
# Requires multiple test PCs or high-spec machine
./run-tests.sh -h 192.168.1.100 -c 100000 -d 20 -r 10 -n 10
```

## Test PC Requirements

For high connection counts, ensure your test PC has:

| Connections | RAM    | CPU Cores | Open Files |
|-------------|--------|-----------|------------|
| 1,000       | 2 GB   | 2         | 10,000     |
| 10,000      | 4 GB   | 4         | 100,000    |
| 100,000     | 16 GB  | 8         | 500,000    |
| 1,000,000   | 64 GB  | 16        | 2,000,000  |

### Increase System Limits (Linux)

```bash
# Add to /etc/security/limits.conf
* soft nofile 1048576
* hard nofile 1048576
* soft nproc 65535
* hard nproc 65535

# Add to /etc/sysctl.conf
net.ipv4.ip_local_port_range = 1024 65535
net.core.somaxconn = 65535
net.ipv4.tcp_tw_reuse = 1

# Apply
sudo sysctl -p
```

### Increase System Limits (macOS)

```bash
# Temporary (until reboot)
sudo launchctl limit maxfiles 1048576 1048576
ulimit -n 1048576

# Permanent: Add to /Library/LaunchDaemons/limit.maxfiles.plist
```

## Troubleshooting

### Cannot connect to target host
```bash
# Test connectivity
ping 192.168.1.100
nc -zv 192.168.1.100 8080

# On server, verify port forward is running
ps aux | grep port-forward

# Check firewall
# macOS: System Preferences > Security > Firewall
# Linux: sudo ufw status
```

### Docker image build fails
```bash
# Clean and rebuild
docker rmi gatling-load-test:latest
./run-tests.sh -h 192.168.1.100 -b
```

### Too many open files error
```bash
# Check current limits
ulimit -n

# Increase (temporary)
ulimit -n 1048576

# Docker already sets this, but verify
docker run --rm gatling-load-test:latest sh -c 'ulimit -n'
```

### WebSocket connections failing
```bash
# Test WebSocket manually
npm install -g wscat
wscat -c "ws://192.168.1.100:8080/ws/socket-xxx/connect?clientId=test"

# Check API is working
curl "http://192.168.1.100:8080/api/v1/resolve?clientId=test"
```

## Results

Test results are saved to `./results/YYYYMMDD_HHMMSS/`:
- `simulation.log` - Detailed request/response log
- `index.html` - Gatling HTML report (open in browser)

View the report:
```bash
open results/*/index.html   # macOS
xdg-open results/*/index.html  # Linux
```

