# Gatling Load Test for Reactive RTC - PowerShell Version
# Run on Windows with Docker Desktop
#
# Usage:
#   .\run-tests.ps1 -Host 192.168.1.100
#   .\run-tests.ps1 -Host 192.168.1.100 -Clients 10000 -Instances 3
#   .\run-tests.ps1 -Host 192.168.1.100 -Clients 50000 -Instances 5 -Duration 10

param(
    [Parameter(Mandatory=$true)]
    [string]$TargetHost,
    
    [int]$Port = 8080,
    [int]$Clients = 1000,
    [int]$RampUp = 2,
    [int]$Duration = 5,
    [int]$Interval = 5000,
    [int]$Instances = 1,
    [switch]$Secure,
    [switch]$Build
)

$ErrorActionPreference = "Stop"

# Colors
function Write-Info { Write-Host $args -ForegroundColor Cyan }
function Write-Success { Write-Host $args -ForegroundColor Green }
function Write-Warn { Write-Host $args -ForegroundColor Yellow }
function Write-Err { Write-Host $args -ForegroundColor Red }

$WsProtocol = if ($Secure) { "wss" } else { "ws" }
$TotalConnections = $Clients * $Instances

Write-Info "=========================================="
Write-Info "  Reactive RTC - Gatling Load Test"
Write-Info "=========================================="
Write-Host ""

Write-Success "Test Configuration:"
Write-Host "  Target:            $TargetHost`:$Port"
Write-Host "  Protocol:          $WsProtocol"
Write-Host "  Clients/instance:  $Clients"
Write-Host "  Instances:         $Instances"
Write-Host "  Total connections: $TotalConnections"
Write-Host "  Ramp-up:           $RampUp minutes"
Write-Host "  Duration:          $Duration minutes"
Write-Host "  Message interval:  $Interval ms"
Write-Host ""

# Test connectivity
Write-Info "Testing connectivity..."
try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $tcp.Connect($TargetHost, $Port)
    $tcp.Close()
    Write-Success "  [OK] $TargetHost`:$Port is reachable"
} catch {
    Write-Err "  [FAIL] Cannot connect to $TargetHost`:$Port"
    Write-Host ""
    Write-Host "Make sure:"
    Write-Host "  1. The target host is running and accessible"
    Write-Host "  2. Firewall allows connections on port $Port"
    Write-Host "  3. The nginx service is exposed"
    exit 1
}

# Test API
Write-Host "Testing API..."
try {
    $response = Invoke-RestMethod -Uri "http://$TargetHost`:$Port/api/v1/resolve?clientId=test" -TimeoutSec 5
    Write-Success "  [OK] API is responding"
} catch {
    Write-Warn "  [WARN] API test failed: $_"
}
Write-Host ""

# Create results directory
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$resultsDir = ".\results\$timestamp"
New-Item -ItemType Directory -Path $resultsDir -Force | Out-Null
Write-Host "Results will be saved to: $resultsDir"
Write-Host ""

# Build image if needed
$imageExists = docker images -q gatling-load-test:latest 2>$null
if ($Build -or -not $imageExists) {
    Write-Info "Building Docker image..."
    docker build -t gatling-load-test:latest -f Dockerfile ..\..
    if ($LASTEXITCODE -ne 0) {
        Write-Err "Failed to build Docker image"
        exit 1
    }
    Write-Host ""
}

# Run tests
Write-Info "Starting $Instances test instance(s)..."
Write-Host ""

$containers = @()
$hostname = $env:COMPUTERNAME

for ($i = 1; $i -le $Instances; $i++) {
    $prefix = "test-$i-$hostname"
    $containerName = "gatling-test-$i"
    
    Write-Host "Starting instance $i/$Instances (prefix: $prefix)..."
    
    docker run -d `
        --name $containerName `
        --rm `
        -e TARGET_HOST=$TargetHost `
        -e TARGET_PORT=$Port `
        -e WS_PROTOCOL=$WsProtocol `
        -e TOTAL_CLIENTS=$Clients `
        -e RAMPUP_MINUTES=$RampUp `
        -e DURATION_MINUTES=$Duration `
        -e MESSAGE_INTERVAL_MS=$Interval `
        -e CLIENT_PREFIX=$prefix `
        -v "${PWD}\results:/app/results" `
        gatling-load-test:latest
    
    if ($LASTEXITCODE -eq 0) {
        $containers += $containerName
    }
}

Write-Host ""
Write-Success "All instances started!"
Write-Host ""
Write-Host "Monitor logs:"
foreach ($container in $containers) {
    Write-Host "  docker logs -f $container"
}
Write-Host ""
Write-Host "Stop all tests:"
Write-Host "  docker stop $($containers -join ' ')"
Write-Host ""

# Follow first container logs
Write-Host "Following logs from first container (Ctrl+C to detach)..."
Write-Host ""
docker logs -f $containers[0]





