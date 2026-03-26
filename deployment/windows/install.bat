@echo off
setlocal enabledelayedexpansion

echo ============================================================
echo SignalReport - Windows Installations-Tool
echo (Apache Commons Daemon 1.5.1)
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

:: Java prüfen
echo [INFO] Prüfe Java-Installation...
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [FEHLER] Java 21+ nicht gefunden!
    echo Bitte installiere Java zuerst: https://adoptium.net/
    echo.
    pause
    exit /b 1
)

:: Installationsverzeichnis
set "INSTALL_DIR=%ProgramFiles%\SignalReport"
set "DATA_DIR=%ProgramData%\SignalReport"

echo [INFO] Installationsverzeichnis: %INSTALL_DIR%
echo [INFO] Datenverzeichnis: %DATA_DIR%
echo.

:: Verzeichnisse erstellen
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%" 2>nul
if not exist "%DATA_DIR%" mkdir "%DATA_DIR%" 2>nul
if not exist "%DATA_DIR%\logs" mkdir "%DATA_DIR%\logs" 2>nul

:: JAR kopieren
if not exist "signalreport.jar" (
    echo [FEHLER] signalreport.jar nicht gefunden!
    echo Bitte starte das Skript im selben Verzeichnis wie die JAR-Datei.
    echo.
    pause
    exit /b 1
)
copy "signalreport.jar" "%INSTALL_DIR%\signalreport.jar" >nul

:: Apache Commons Daemon 1.5.1 herunterladen
echo [INFO] Lade Apache Commons Daemon 1.5.1 herunter...
powershell -Command ^
  "try { ^
     Invoke-WebRequest -Uri 'https://archive.apache.org/dist/commons/daemon/binaries/windows/commons-daemon-1.5.1-bin-windows.zip' ^
     -OutFile '%TEMP%\commons-daemon.zip' -ErrorAction Stop; ^
     Expand-Archive -Path '%TEMP%\commons-daemon.zip' -DestinationPath '%TEMP%\commons-daemon' -Force; ^
     Copy-Item '%TEMP%\commons-daemon\prunsrv-x64.exe' '%INSTALL_DIR%\prunsrv.exe' -Force; ^
     Copy-Item '%TEMP%\commons-daemon\prunmgr.exe' '%INSTALL_DIR%\prunmgr.exe' -Force; ^
     Write-Host '[OK] Apache Commons Daemon 1.5.1 erfolgreich heruntergeladen.'; ^
   } catch { ^
     Write-Host '[FEHLER] Download fehlgeschlagen. Bitte manuell herunterladen:'; ^
     Write-Host 'https://archive.apache.org/dist/commons/daemon/binaries/windows/commons-daemon-1.5.1-bin-windows.zip'; ^
     Write-Host 'und prunsrv-x64.exe / prunmgr.exe nach %INSTALL_DIR% kopieren.'; ^
     exit 1; ^
   }"

if not exist "%INSTALL_DIR%\prunsrv.exe" (
    echo.
    pause
    exit /b 1
)

:: Java-Home ermitteln
for /f "tokens=2*" %%a in ('reg query "HKLM\SOFTWARE\JavaSoft\JDK" /v CurrentVersion 2^>nul') do set JAVA_VERSION=%%b
if defined JAVA_VERSION (
    for /f "tokens=2*" %%a in ('reg query "HKLM\SOFTWARE\JavaSoft\JDK\%JAVA_VERSION%" /v JavaHome 2^>nul') do set JAVA_HOME=%%b
) else (
    set JAVA_HOME=C:\Program Files\Java\jdk-21
)

:: Dienst installieren mit procrun
echo [INFO] Installiere Windows-Dienst "SignalReport"...
"%INSTALL_DIR%\prunsrv.exe" //IS//SignalReport ^
    --DisplayName="SignalReport Monitoring" ^
    --Description="Kontinuierliches Internet-Qualitäts-Monitoring" ^
    --StartMode=jvm ^
    --StopMode=jvm ^
    --StartClass=at.mafue.signalreport.SignalReportApp ^
    --StopClass=at.mafue.signalreport.SignalReportApp ^
    --StopMethod=main ^
    --Jvm=%JAVA_HOME%\bin\server\jvm.dll ^
    --JvmOptions=-Dfile.encoding=UTF-8 ^
    --Classpath=%INSTALL_DIR%\signalreport.jar ^
    --StartPath=%INSTALL_DIR% ^
    --StdOutput=auto ^
    --StdError=auto ^
    --LogPath=%DATA_DIR%\logs ^
    --LogPrefix=signalreport ^
    --LogLevel=info ^
    --Startup=auto ^
    --ServiceUser=LocalSystem

if %errorlevel% neq 0 (
    echo [FEHLER] Dienst-Installation fehlgeschlagen.
    echo.
    pause
    exit /b 1
)

:: Dienst starten
echo [INFO] Starte SignalReport-Dienst...
"%INSTALL_DIR%\prunsrv.exe" //ES//SignalReport
timeout /t 3 /nobreak >nul

:: Status prüfen
sc query SignalReport | find "RUNNING" >nul
if %errorlevel% equ 0 (
    echo [OK] SignalReport-Dienst läuft.
) else (
    echo [WARNUNG] Dienst gestartet, aber Status unklar.
)

:: Desktop-Verknüpfung
echo [INFO] Erstelle Desktop-Verknüpfung...
powershell -Command ^
  "$s=(New-Object -ComObject WScript.Shell).CreateShortcut([Environment]::GetFolderPath('Desktop') + '\SignalReport - Verbindungsanalyse.url'); ^
   $s.TargetPath='http://localhost:4567'; ^
   $s.IconLocation='C:\Windows\System32\shell32.dll,131'; ^
   $s.Save()"

:: Firewall-Regel
echo [INFO] Erstelle Firewall-Regel für Port 4567...
netsh advfirewall firewall add rule name="SignalReport" dir=in action=allow protocol=TCP localport=4567 >nul 2>&1

echo.
echo ============================================================
echo ✅ Installation abgeschlossen!
echo ============================================================
echo • Web-Oberfläche: http://localhost:4567
echo • Dienst-Name: SignalReport (läuft als LocalSystem)
echo • Datenverzeichnis: %DATA_DIR%
echo • Logs: %DATA_DIR%\logs\signalreport*.log
echo • Desktop-Verknüpfung erstellt
echo • Verwaltung: "%INSTALL_DIR%\prunmgr.exe" öffnen
echo.
echo Hinweis: Der Dienst startet automatisch nach jedem Neustart –
echo           auch ohne Benutzer-Login!
echo.
pause