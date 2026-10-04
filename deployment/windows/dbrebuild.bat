@echo off
cd /d "%~dp0"
powershell -ExecutionPolicy Bypass -File "%~dp0dbrebuild.ps1"
echo.
pause
