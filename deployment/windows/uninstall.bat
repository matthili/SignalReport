@echo off
cd /d "%~dp0"
powershell -ExecutionPolicy Bypass -File "%~dp0unin.ps1"
echo.
pause
