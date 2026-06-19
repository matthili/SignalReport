# SignalReport - Windows Installations- / Update-Tool
# Starten ueber install.bat (Rechtsklick → "Als Administrator ausfuehren")
# Erkennt eine bestehende Installation automatisch und fuehrt dann nur ein
# JAR-Update durch (Dienst stoppen -> JAR tauschen -> Dienst starten).

# Admin-Rechte pruefen
if (-not ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "[FEHLER] Administrator-Rechte erforderlich!" -ForegroundColor Red
    Write-Host "Bitte install.bat per Rechtsklick → 'Als Administrator ausfuehren' starten."
    Write-Host ""
    exit 1
}

# Arbeitsverzeichnis auf den Ordner des Skripts setzen
Set-Location -Path (Split-Path -Parent $PSCommandPath)

Write-Host "============================================================"
Write-Host "SignalReport - Windows Installations- / Update-Tool"
Write-Host "(Apache Commons Daemon 1.5.1)"
Write-Host "============================================================"
Write-Host ""

# Java pruefen
Write-Host "[INFO] Pruefe Java-Installation..."
try {
    java -version 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Java nicht gefunden" }
} catch {
    Write-Host "[FEHLER] Java 21+ nicht gefunden!" -ForegroundColor Red
    Write-Host "Bitte installiere Java zuerst: https://adoptium.net/"
    Write-Host ""
    exit 1
}

# Installationsverzeichnisse
$INSTALL_DIR = "$env:ProgramFiles\SignalReport"
$DATA_DIR = "$env:ProgramData\SignalReport"

# Die neue JAR muss im selben Ordner wie dieses Skript liegen (fuer Install UND Update)
if (-not (Test-Path "signalreport.jar")) {
    Write-Host "[FEHLER] signalreport.jar nicht gefunden!" -ForegroundColor Red
    Write-Host "Bitte starte das Skript im selben Verzeichnis wie die JAR-Datei."
    Write-Host ""
    exit 1
}

# ============================================================
#  Update-Erkennung: Ist SignalReport bereits installiert?
#  Dann nur die JAR austauschen, statt komplett neu zu installieren.
# ============================================================
$existingService = Get-Service -Name "SignalReport" -ErrorAction SilentlyContinue

if ($existingService -and (Test-Path "$INSTALL_DIR\prunsrv.exe")) {
    Write-Host "[INFO] SignalReport ist bereits installiert -> UPDATE-Modus." -ForegroundColor Cyan
    Write-Host "[INFO] Tausche die installierte signalreport.jar gegen die neue aus."
    Write-Host "[INFO] Daten und Einstellungen ($DATA_DIR) bleiben erhalten."
    Write-Host ""

    # 1. Dienst stoppen (gibt die JAR-Datei frei)
    Write-Host "[INFO] Stoppe SignalReport-Dienst..."
    & "$INSTALL_DIR\prunsrv.exe" //SS//SignalReport 2>&1 | Out-Null

    # Warten, bis der Dienst wirklich gestoppt ist (max. 20 s)
    $deadline = (Get-Date).AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 500
        $svc = Get-Service -Name "SignalReport" -ErrorAction SilentlyContinue
    } while ($svc -and $svc.Status -ne "Stopped" -and (Get-Date) -lt $deadline)

    # Notfalls den noch laufenden java-Prozess dieser JAR beenden, damit die Datei frei wird
    if ($svc -and $svc.Status -ne "Stopped") {
        Write-Host "[WARNUNG] Dienst stoppte nicht rechtzeitig - beende den Java-Prozess..." -ForegroundColor Yellow
        Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -like "*$INSTALL_DIR\signalreport.jar*" } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 2
    }

    # 2. JAR austauschen (mit Wiederholung, falls die Datei noch kurz gesperrt ist)
    $copied = $false
    for ($i = 0; $i -lt 5 -and -not $copied; $i++) {
        try {
            Copy-Item "signalreport.jar" "$INSTALL_DIR\signalreport.jar" -Force -ErrorAction Stop
            $copied = $true
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    if (-not $copied) {
        Write-Host "[FEHLER] signalreport.jar konnte nicht ersetzt werden (Datei noch in Verwendung?)." -ForegroundColor Red
        Write-Host "[INFO] Starte den Dienst wieder und breche ab."
        & "$INSTALL_DIR\prunsrv.exe" //ES//SignalReport 2>&1 | Out-Null
        Write-Host ""
        exit 1
    }
    Write-Host "[OK] signalreport.jar aktualisiert." -ForegroundColor Green

    # Icon ggf. mitaktualisieren (harmlos, falls mitgeliefert)
    if (Test-Path "desktop_icon.ico") {
        Copy-Item "desktop_icon.ico" "$INSTALL_DIR\desktop_icon.ico" -Force -ErrorAction SilentlyContinue
    }

    # 3. Dienst wieder starten
    Write-Host "[INFO] Starte SignalReport-Dienst..."
    & "$INSTALL_DIR\prunsrv.exe" //ES//SignalReport
    Start-Sleep -Seconds 3

    $svc = Get-Service -Name "SignalReport" -ErrorAction SilentlyContinue
    if ($svc -and $svc.Status -eq "Running") {
        Write-Host "[OK] SignalReport-Dienst laeuft wieder." -ForegroundColor Green
    } else {
        Write-Host "[WARNUNG] Dienst gestartet, aber Status unklar. Pruefe $DATA_DIR\logs." -ForegroundColor Yellow
    }

    Write-Host ""
    Write-Host "============================================================"
    Write-Host "✅ Update abgeschlossen!"
    Write-Host "============================================================"
    Write-Host "• Web-Oberflaeche: http://localhost:4567"
    Write-Host "• Die installierte JAR wurde ersetzt; Daten/Einstellungen bleiben erhalten."
    Write-Host "• Logs: $DATA_DIR\logs\signalreport*.log"
    Write-Host ""
    exit 0
}

if ($existingService) {
    # Dienst existiert, aber die Installation ist unvollstaendig (prunsrv.exe fehlt).
    Write-Host "[WARNUNG] Der Dienst 'SignalReport' existiert, aber die Installation in" -ForegroundColor Yellow
    Write-Host "          $INSTALL_DIR ist unvollstaendig (prunsrv.exe fehlt)." -ForegroundColor Yellow
    Write-Host "Bitte zuerst die Deinstallation (uninstall.bat) ausfuehren und dann neu installieren."
    Write-Host ""
    exit 1
}

# ============================================================
#  Ab hier: vollstaendige Neuinstallation
# ============================================================
Write-Host "[INFO] Installationsverzeichnis: $INSTALL_DIR"
Write-Host "[INFO] Datenverzeichnis: $DATA_DIR"
Write-Host ""

# Verzeichnisse erstellen
New-Item -ItemType Directory -Path $INSTALL_DIR -Force | Out-Null
New-Item -ItemType Directory -Path $DATA_DIR -Force | Out-Null
New-Item -ItemType Directory -Path "$DATA_DIR\logs" -Force | Out-Null

# JAR kopieren (Existenz wurde oben bereits geprueft)
Copy-Item "signalreport.jar" "$INSTALL_DIR\signalreport.jar" -Force

# Apache Commons Daemon 1.5.1 herunterladen
Write-Host "[INFO] Lade Apache Commons Daemon 1.5.1 herunter..."
$daemonUrl = "https://archive.apache.org/dist/commons/daemon/binaries/windows/commons-daemon-1.5.1-bin-windows.zip"
$zipPath = "$env:TEMP\commons-daemon.zip"
$extractPath = "$env:TEMP\commons-daemon"

try {
    Invoke-WebRequest -Uri $daemonUrl -OutFile $zipPath -ErrorAction Stop
    Expand-Archive -Path $zipPath -DestinationPath $extractPath -Force

    # Richtige Datei finden (Verzeichnisstruktur kann variieren)
    $prunsrv = Get-ChildItem -Path $extractPath -Filter "prunsrv.exe" -Recurse |
               Where-Object { $_.FullName -like "*amd64*" -or $_.FullName -like "*x64*" } |
               Select-Object -First 1
    $prunmgr = Get-ChildItem -Path $extractPath -Filter "prunmgr.exe" -Recurse |
               Select-Object -First 1

    if (-not $prunsrv) {
        # Fallback: prunsrv.exe im Hauptverzeichnis suchen
        $prunsrv = Get-ChildItem -Path $extractPath -Filter "prunsrv.exe" -Recurse | Select-Object -First 1
    }

    if ($prunsrv) {
        Copy-Item $prunsrv.FullName "$INSTALL_DIR\prunsrv.exe" -Force
    } else {
        throw "prunsrv.exe nicht im Archiv gefunden"
    }

    if ($prunmgr) {
        Copy-Item $prunmgr.FullName "$INSTALL_DIR\prunmgr.exe" -Force
    }

    Write-Host "[OK] Apache Commons Daemon 1.5.1 erfolgreich heruntergeladen." -ForegroundColor Green
} catch {
    Write-Host "[FEHLER] Download fehlgeschlagen: $_" -ForegroundColor Red
    Write-Host "Bitte manuell herunterladen:"
    Write-Host "  $daemonUrl"
    Write-Host "  und prunsrv.exe (amd64) / prunmgr.exe nach $INSTALL_DIR kopieren."
    Write-Host ""
    exit 1
} finally {
    # Temporaere Dateien aufraeumen
    Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
    Remove-Item $extractPath -Recurse -Force -ErrorAction SilentlyContinue
}

if (-not (Test-Path "$INSTALL_DIR\prunsrv.exe")) {
    Write-Host "[FEHLER] prunsrv.exe konnte nicht installiert werden." -ForegroundColor Red
    exit 1
}

# Java-Home ermitteln
$javaVersion = (Get-ItemProperty -Path "HKLM:\SOFTWARE\JavaSoft\JDK" -Name CurrentVersion -ErrorAction SilentlyContinue).CurrentVersion
if ($javaVersion) {
    $JAVA_HOME = (Get-ItemProperty -Path "HKLM:\SOFTWARE\JavaSoft\JDK\$javaVersion" -Name JavaHome -ErrorAction SilentlyContinue).JavaHome
}
if (-not $JAVA_HOME) {
    $JAVA_HOME = "C:\Program Files\Java\jdk-21"
}
Write-Host "[INFO] JAVA_HOME: $JAVA_HOME"

# Dienst installieren mit procrun
Write-Host "[INFO] Installiere Windows-Dienst `"SignalReport`"..."
& "$INSTALL_DIR\prunsrv.exe" //IS//SignalReport `
    --DisplayName="SignalReport Monitoring" `
    --Description="Kontinuierliches Internet-Qualitaets-Monitoring" `
    --StartMode=exe `
    "--StartImage=$JAVA_HOME\bin\java.exe" `
    "--StartParams=-Dfile.encoding=UTF-8#-jar#$INSTALL_DIR\signalreport.jar" `
    --StopMode=exe `
    "--StopImage=$JAVA_HOME\bin\java.exe" `
    "--StopParams=-Dfile.encoding=UTF-8#-jar#$INSTALL_DIR\signalreport.jar#stop" `
    --StopTimeout=10 `
    "--StartPath=$DATA_DIR" `
    --StdOutput=auto `
    --StdError=auto `
    "--LogPath=$DATA_DIR\logs" `
    --LogPrefix=signalreport `
    --LogLevel=info `
    --Startup=auto `
    --DependsOn=Tcpip `
    --ServiceUser=LocalSystem

if ($LASTEXITCODE -ne 0) {
    Write-Host "[FEHLER] Dienst-Installation fehlgeschlagen." -ForegroundColor Red
    Write-Host ""
    exit 1
}

# Dienst starten
Write-Host "[INFO] Starte SignalReport-Dienst..."
& "$INSTALL_DIR\prunsrv.exe" //ES//SignalReport
Start-Sleep -Seconds 3

# Status pruefen
$service = Get-Service -Name "SignalReport" -ErrorAction SilentlyContinue
if ($service -and $service.Status -eq "Running") {
    Write-Host "[OK] SignalReport-Dienst laeuft." -ForegroundColor Green
} else {
    Write-Host "[WARNUNG] Dienst gestartet, aber Status unklar." -ForegroundColor Yellow
}

# Desktop-Verknuepfung (.url-Datei direkt als Text schreiben)
Write-Host "[INFO] Erstelle Desktop-Verknuepfung..."
$urlFile = [Environment]::GetFolderPath('Desktop') + '\SignalReport - Verbindungsanalyse.url'

# Eigenes Icon verwenden, falls vorhanden
$iconFile = "C:\Windows\System32\shell32.dll"
$iconIndex = 131
if (Test-Path "desktop_icon.ico") {
    Copy-Item "desktop_icon.ico" "$INSTALL_DIR\desktop_icon.ico" -Force
    $iconFile = "$INSTALL_DIR\desktop_icon.ico"
    $iconIndex = 0
}

@"
[InternetShortcut]
URL=http://localhost:4567
IconIndex=$iconIndex
IconFile=$iconFile
"@ | Out-File -FilePath $urlFile -Encoding ascii

# Firewall-Regel
Write-Host "[INFO] Erstelle Firewall-Regel fuer Port 4567..."
New-NetFirewallRule -DisplayName "SignalReport" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 4567 -ErrorAction SilentlyContinue | Out-Null

Write-Host ""
Write-Host "============================================================"
Write-Host "✅ Installation abgeschlossen!"
Write-Host "============================================================"
Write-Host "• Web-Oberflaeche: http://localhost:4567"
Write-Host "• Dienst-Name: SignalReport (laeuft als LocalSystem)"
Write-Host "• Datenverzeichnis: $DATA_DIR"
Write-Host "• Logs: $DATA_DIR\logs\signalreport*.log"
Write-Host "• Desktop-Verknuepfung erstellt"
Write-Host "• Verwaltung: `"$INSTALL_DIR\prunmgr.exe`" oeffnen"
Write-Host ""
Write-Host "Hinweis: Der Dienst startet automatisch nach jedem Neustart –"
Write-Host "          auch ohne Benutzer-Login!"
Write-Host ""
