# SignalReport - Windows Deinstallations-Tool
# Starten ueber uninstall.bat (Rechtsklick → "Als Administrator ausfuehren")

# Admin-Rechte pruefen
if (-not ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "[FEHLER] Administrator-Rechte erforderlich!" -ForegroundColor Red
    Write-Host "Bitte uninstall.bat per Rechtsklick → 'Als Administrator ausfuehren' starten."
    Write-Host ""
    exit 1
}

# Arbeitsverzeichnis auf den Ordner des Skripts setzen
Set-Location -Path (Split-Path -Parent $PSCommandPath)

Write-Host "============================================================"
Write-Host "SignalReport - Windows Deinstallations-Tool"
Write-Host "============================================================"
Write-Host ""

$INSTALL_DIR = "$env:ProgramFiles\SignalReport"
$DATA_DIR = "$env:ProgramData\SignalReport"

# Dienst stoppen und entfernen
if (Test-Path "$INSTALL_DIR\prunsrv.exe") {
    Write-Host "[INFO] Stoppe SignalReport-Dienst..."
    & "$INSTALL_DIR\prunsrv.exe" //SS//SignalReport 2>&1 | Out-Null

    Write-Host "[INFO] Entferne Windows-Dienst..."
    & "$INSTALL_DIR\prunsrv.exe" //DS//SignalReport 2>&1 | Out-Null
}

# Dateien loeschen
Write-Host "[INFO] Loesche Installationsverzeichnis: $INSTALL_DIR"
if (Test-Path $INSTALL_DIR) {
    Remove-Item $INSTALL_DIR -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "[INFO] Loesche Datenverzeichnis: $DATA_DIR"
if (Test-Path $DATA_DIR) {
    Remove-Item $DATA_DIR -Recurse -Force -ErrorAction SilentlyContinue
}

# Desktop-Verknuepfung entfernen
Write-Host "[INFO] Entferne Desktop-Verknuepfung..."
$desktopLink = [Environment]::GetFolderPath('Desktop') + '\SignalReport - Verbindungsanalyse.url'
Remove-Item $desktopLink -Force -ErrorAction SilentlyContinue

# Firewall-Regel entfernen
Write-Host "[INFO] Entferne Firewall-Regel..."
Remove-NetFirewallRule -DisplayName "SignalReport" -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "============================================================"
Write-Host "✅ Deinstallation abgeschlossen!"
Write-Host "============================================================"
Write-Host "• SignalReport-Dienst entfernt"
Write-Host "• Alle Dateien geloescht"
Write-Host "• Desktop-Verknuepfung entfernt"
Write-Host ""
