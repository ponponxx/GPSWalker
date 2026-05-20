@echo off
chcp 65001 >nul
setlocal

REM ============================================================
REM   GPSWalker - drive ONLY the iPhone
REM
REM   BEFORE running this:
REM     1. iPhone: USB connected, unlocked, Developer Mode on
REM     2. Run tunnel.bat FIRST (asks for Administrator), keep open
REM
REM   Then double-click this file.
REM ============================================================

cd /d "%~dp0"

echo iPhone -> via tunnel daemon (tunnel.bat)
echo Web UI -> http://127.0.0.1:5000
echo.

python app.py --provider none --ios

echo.
echo (server stopped)
pause
endlocal
