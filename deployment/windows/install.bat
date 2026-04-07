@echo off
cd /d "%~dp0"
powershell -ExecutionPolicy Bypass -File "%~dp0in.ps1"
echo.
pause
