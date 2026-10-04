# SignalReport - Datenbank-Neuaufbau (Windows)
# Starten ueber dbrebuild.bat (Rechtsklick -> "Als Administrator ausfuehren")
#
# Stoppt den SignalReport-Dienst, baut die Datenbank aus Primary und Shadow in eine
# frische, kompakte Datei neu auf (java -jar signalreport.jar rebuild-db) und startet
# den Dienst wieder. Die alten Dateien werden NICHT geloescht, sondern nach
# %ProgramData%\SignalReport\data\quarantine\rebuild_<Zeit>\ verschoben.
# Der Bericht liegt danach unter %ProgramData%\SignalReport\data\signalreport_rebuild-report_<Zeit>.txt.

if (-not ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "[FEHLER] Administrator-Rechte erforderlich!" -ForegroundColor Red
    Write-Host "Bitte dbrebuild.bat per Rechtsklick -> 'Als Administrator ausfuehren' starten."
    Write-Host ""
    exit 1
}

$INSTALL_DIR = "$env:ProgramFiles\SignalReport"
$DATA_DIR = "$env:ProgramData\SignalReport"

Write-Host "============================================================"
Write-Host "SignalReport - Datenbank-Neuaufbau"
Write-Host "============================================================"
Write-Host ""

if (-not (Test-Path "$INSTALL_DIR\signalreport.jar")) {
    Write-Host "[FEHLER] $INSTALL_DIR\signalreport.jar nicht gefunden. Ist SignalReport installiert?" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path "$DATA_DIR\data\signalreport.mv.db") -and -not (Test-Path "$DATA_DIR\data\signalreport-shadow.mv.db")) {
    Write-Host "[FEHLER] Keine Datenbankdatei unter $DATA_DIR\data gefunden." -ForegroundColor Red
    exit 1
}
try {
    java -version 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Java nicht gefunden" }
} catch {
    Write-Host "[FEHLER] Java nicht gefunden (java muss im PATH liegen)." -ForegroundColor Red
    exit 1
}

# 1. Dienst stoppen (seit 2.0.2 geordnet; StopTimeout des Dienstes ist 120 s)
$svc = Get-Service -Name "SignalReport" -ErrorAction SilentlyContinue
$wasRunning = $false
if ($svc -and $svc.Status -ne "Stopped") {
    $wasRunning = $true
    Write-Host "[INFO] Stoppe SignalReport-Dienst..."
    if (Test-Path "$INSTALL_DIR\prunsrv.exe") {
        & "$INSTALL_DIR\prunsrv.exe" //SS//SignalReport 2>&1 | Out-Null
    } else {
        Stop-Service -Name "SignalReport" -ErrorAction SilentlyContinue
    }
    $deadline = (Get-Date).AddSeconds(150)
    do {
        Start-Sleep -Milliseconds 500
        $svc = Get-Service -Name "SignalReport" -ErrorAction SilentlyContinue
    } while ($svc -and $svc.Status -ne "Stopped" -and (Get-Date) -lt $deadline)
    if ($svc -and $svc.Status -ne "Stopped") {
        Write-Host "[FEHLER] Der Dienst liess sich nicht stoppen. Neuaufbau abgebrochen (Datenbank waere noch in Benutzung)." -ForegroundColor Red
        exit 1
    }
    Write-Host "[OK] Dienst gestoppt."
} else {
    Write-Host "[INFO] Dienst laeuft nicht."
}

# 2. Neuaufbau im Datenverzeichnis ausfuehren (dort liegen config.json und data\)
Write-Host "[INFO] Starte Neuaufbau (das kann je nach Dateigroesse einige Minuten dauern)..."
Write-Host ""
$proc = Start-Process -FilePath "java" -ArgumentList "-Dfile.encoding=UTF-8", "-jar", "`"$INSTALL_DIR\signalreport.jar`"", "rebuild-db" `
    -WorkingDirectory $DATA_DIR -NoNewWindow -Wait -PassThru
Write-Host ""
if ($proc.ExitCode -eq 0) {
    Write-Host "[OK] Neuaufbau abgeschlossen." -ForegroundColor Green
} else {
    Write-Host "[FEHLER] Neuaufbau fehlgeschlagen (Exit-Code $($proc.ExitCode)). Die alten Dateien sind unveraendert." -ForegroundColor Red
}
$report = Get-ChildItem "$DATA_DIR\data\signalreport_rebuild-report_*.txt" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1
if ($report) { Write-Host "[INFO] Bericht: $($report.FullName)" }

# 3. Dienst wieder starten (auch nach einem Fehlschlag, damit die Messung weiterlaeuft)
if ($wasRunning) {
    Write-Host "[INFO] Starte SignalReport-Dienst..."
    if (Test-Path "$INSTALL_DIR\prunsrv.exe") {
        & "$INSTALL_DIR\prunsrv.exe" //ES//SignalReport 2>&1 | Out-Null
    } else {
        Start-Service -Name "SignalReport" -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 3
    $svc = Get-Service -Name "SignalReport" -ErrorAction SilentlyContinue
    if ($svc -and $svc.Status -eq "Running") {
        Write-Host "[OK] SignalReport-Dienst laeuft wieder." -ForegroundColor Green
    } else {
        Write-Host "[WARNUNG] Dienst gestartet, aber Status unklar. Pruefe $DATA_DIR\logs." -ForegroundColor Yellow
    }
}
Write-Host ""
exit $proc.ExitCode
