@echo off
REM Gatling Load Test for Reactive RTC - Windows Batch Version
REM 
REM Usage:
REM   run-tests.bat 192.168.1.100
REM   run-tests.bat 192.168.1.100 1000 3
REM   run-tests.bat 192.168.1.100 10000 5 10 3
REM
REM Parameters:
REM   %1 - Target host IP (required)
REM   %2 - Clients per instance (default: 1000)
REM   %3 - Number of instances (default: 1)
REM   %4 - Duration in minutes (default: 5)
REM   %5 - Ramp-up in minutes (default: 2)

setlocal enabledelayedexpansion

REM Parse arguments
set TARGET_HOST=%1
set CLIENTS=%2
set INSTANCES=%3
set DURATION=%4
set RAMPUP=%5

REM Set defaults
if "%TARGET_HOST%"=="" (
    echo.
    echo Usage: run-tests.bat ^<host^> [clients] [instances] [duration] [rampup]
    echo.
    echo Examples:
    echo   run-tests.bat 192.168.1.100                    # Basic test
    echo   run-tests.bat 192.168.1.100 10000 3            # 3 instances, 10K clients each
    echo   run-tests.bat 192.168.1.100 50000 5 10 3       # Heavy load test
    echo.
    exit /b 1
)

if "%CLIENTS%"=="" set CLIENTS=1000
if "%INSTANCES%"=="" set INSTANCES=1
if "%DURATION%"=="" set DURATION=5
if "%RAMPUP%"=="" set RAMPUP=2

set /a TOTAL=%CLIENTS%*%INSTANCES%

echo.
echo ==========================================
echo   Reactive RTC - Gatling Load Test
echo ==========================================
echo.
echo Configuration:
echo   Target:            %TARGET_HOST%:8080
echo   Clients/instance:  %CLIENTS%
echo   Instances:         %INSTANCES%
echo   Total connections: %TOTAL%
echo   Duration:          %DURATION% minutes
echo   Ramp-up:           %RAMPUP% minutes
echo.

REM Test connectivity
echo Testing connectivity to %TARGET_HOST%:8080...
powershell -Command "Test-NetConnection -ComputerName %TARGET_HOST% -Port 8080 -WarningAction SilentlyContinue | Select-Object -ExpandProperty TcpTestSucceeded" > nul 2>&1
if errorlevel 1 (
    echo [FAIL] Cannot connect to %TARGET_HOST%:8080
    echo Make sure the target is accessible and firewall allows port 8080
    exit /b 1
)
echo [OK] Connection successful
echo.

REM Create results directory
for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value') do set datetime=%%I
set TIMESTAMP=%datetime:~0,8%_%datetime:~8,6%
set RESULTS_DIR=results\%TIMESTAMP%
mkdir "%RESULTS_DIR%" 2>nul

echo Results will be saved to: %RESULTS_DIR%
echo.

REM Check if image exists, build if not
docker images -q gatling-load-test:latest > nul 2>&1
if errorlevel 1 (
    echo Building Docker image...
    docker build -t gatling-load-test:latest -f Dockerfile ..\..
    if errorlevel 1 (
        echo Failed to build Docker image
        exit /b 1
    )
    echo.
)

REM Start test instances
echo Starting %INSTANCES% test instance(s)...
echo.

set CONTAINERS=

for /l %%i in (1,1,%INSTANCES%) do (
    set PREFIX=test-%%i-%COMPUTERNAME%
    set CONTAINER_NAME=gatling-test-%%i
    
    echo Starting instance %%i/%INSTANCES%...
    
    docker run -d ^
        --name !CONTAINER_NAME! ^
        --rm ^
        -e TARGET_HOST=%TARGET_HOST% ^
        -e TARGET_PORT=8080 ^
        -e WS_PROTOCOL=ws ^
        -e TOTAL_CLIENTS=%CLIENTS% ^
        -e RAMPUP_MINUTES=%RAMPUP% ^
        -e DURATION_MINUTES=%DURATION% ^
        -e MESSAGE_INTERVAL_MS=5000 ^
        -e CLIENT_PREFIX=!PREFIX! ^
        -v "%CD%\results:/app/results" ^
        gatling-load-test:latest
    
    set CONTAINERS=!CONTAINERS! !CONTAINER_NAME!
)

echo.
echo All instances started!
echo.
echo Monitor logs:
echo   docker logs -f gatling-test-1
echo.
echo Stop all tests:
echo   docker stop%CONTAINERS%
echo.

REM Follow logs
echo Following logs from first container (Ctrl+C to stop)...
echo.
docker logs -f gatling-test-1

endlocal





