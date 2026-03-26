@echo off
setlocal

echo ============================================================
echo SignalReport - Windows Deinstallations-Tool
echo ============================================================
echo.

:: Admin-Rechte prüfen
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [FEHLER] Administrator-Rechte erforderlich!
    echo Bitte mit rechter Maustaste "Als Administrator ausführen" starten.
    echo.
    pause
    exit /b 1
)

set "INSTALL_DIR=%ProgramFiles%\SignalReport"
set "DATA_DIR=%ProgramData%\SignalReport"

:: Dienst stoppen und entfernen
if exist "%INSTALL_DIR%\prunsrv.exe" (
    echo [INFO] Stoppe SignalReport-Dienst...
    "%INSTALL_DIR%\prunsrv.exe" //SS//SignalReport >nul 2>&1

    echo [INFO] Entferne Windows-Dienst...
    "%INSTALL_DIR%\prunsrv.exe" //DS//SignalReport >nul 2>&1
)

:: Dateien löschen
echo [INFO] Lösche Installationsverzeichnis: %INSTALL_DIR%
if exist "%INSTALL_DIR%" rmdir /s /q "%INSTALL_DIR%" >nul 2>&1

echo [INFO] Lösche Datenverzeichnis: %DATA_DIR%
if exist "%DATA_DIR%" rmdir /s /q "%DATA_DIR%" >nul 2>&1

:: Desktop-Verknüpfung entfernen
echo [INFO] Entferne Desktop-Verknüpfung...
del "%USERPROFILE%\Desktop\SignalReport - Verbindungsanalyse.url" >nul 2>&1

:: Firewall-Regel entfernen
echo [INFO] Entferne Firewall-Regel...
netsh advfirewall firewall delete rule name="SignalReport" >nul 2>&1

echo.
echo ============================================================
echo ✅ Deinstallation abgeschlossen!
echo ============================================================
echo • SignalReport-Dienst entfernt
echo • Alle Dateien gelöscht
echo • Desktop-Verknüpfung entfernt
echo.
pause