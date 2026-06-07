@echo off
chcp 65001 >nul
setlocal

REM ============================================================
REM   GPSWalker  -  edit ONLY the line below
REM   Open the GPSWalker app on your phone, read "Phone address",
REM   and put that <ip>:<port> here.
REM ============================================================
set PHONE=10.212.144.91:8080
REM ============================================================

cd /d "%~dp0"

echo Starting GPSWalker -> phone %PHONE%
echo Web UI will be at http://127.0.0.1:5000
echo.

python app.py --provider http --phone %PHONE%

echo.
echo (server stopped)
pause
endlocal
