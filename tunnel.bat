@echo off
chcp 65001 >nul
REM ============================================================
REM   GPSWalker iOS tunnel daemon
REM   iOS 17+ (incl. iOS 26) needs a RemoteXPC tunnel.
REM   This MUST run as Administrator -- the script self-elevates.
REM   Keep this window OPEN the whole time you use the iPhone.
REM ============================================================

REM --- self-elevate to Administrator ---
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo Requesting administrator privileges...
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

cd /d "%~dp0"
echo === GPSWalker iOS tunnel daemon (Administrator) ===
echo Keep this window OPEN while controlling the iPhone.
echo Connect + unlock the iPhone, trust this PC if asked.
echo.

python -m pymobiledevice3 remote tunneld

echo.
echo (tunnel daemon stopped)
pause
