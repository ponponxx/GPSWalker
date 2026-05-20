@echo off
chcp 65001 >nul
setlocal

REM ============================================================
REM   GPSWalker - drive Android + iPhone at the same time
REM
REM   BEFORE running this:
REM     1. Android: GPSWalker app open + "Start mock service"
REM     2. iPhone : USB connected, unlocked, Developer Mode on
REM     3. Run tunnel.bat FIRST (it asks for Administrator) and
REM        leave its window open.
REM
REM   Then edit the Android IP below and double-click this file.
REM ============================================================
set PHONE=192.168.68.103:8080
REM ============================================================

cd /d "%~dp0"

echo Android  -> %PHONE%
echo iPhone   -> via tunnel daemon (tunnel.bat)
echo Web UI   -> http://127.0.0.1:5000
echo.

python app.py --provider http --phone %PHONE% --ios

echo.
echo (server stopped)
pause
endlocal
