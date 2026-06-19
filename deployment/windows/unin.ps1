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

# Was soll erhalten bleiben?
Write-Host "Was soll bei der Deinstallation erhalten bleiben?"
Write-Host "  [1] Nichts (alles loeschen)            [Standard]"
Write-Host "  [2] Konfigurationsdatei (config.json)"
Write-Host "  [3] Datenbank (gesammelte Messdaten)"
Write-Host "  [4] Konfigurationsdatei UND Datenbank"
$keepChoice = Read-Host "Auswahl [1-4]"
$keepConfig = $false
$keepDb = $false
switch ($keepChoice) {
    "2" { $keepConfig = $true }
    "3" { $keepDb = $true }
    "4" { $keepConfig = $true; $keepDb = $true }
    default { }
}
Write-Host ""

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

Write-Host "[INFO] Datenverzeichnis: $DATA_DIR"
if (-not $keepConfig -and -not $keepDb) {
    if (Test-Path $DATA_DIR) {
        Remove-Item $DATA_DIR -Recurse -Force -ErrorAction SilentlyContinue
    }
    Write-Host "[INFO] Datenverzeichnis geloescht."
} else {
    Write-Host "[INFO] Bereinige Datenverzeichnis (ausgewaehlte Daten bleiben erhalten)..."
    Remove-Item "$DATA_DIR\logs" -Recurse -Force -ErrorAction SilentlyContinue
    if (-not $keepConfig) {
        Remove-Item "$DATA_DIR\config.json" -Force -ErrorAction SilentlyContinue
    }
    if (-not $keepDb) {
        Remove-Item "$DATA_DIR\data" -Recurse -Force -ErrorAction SilentlyContinue
    }
    Write-Host "[INFO] Behalten in ${DATA_DIR}:"
    if ($keepConfig) { Write-Host "        - config.json" }
    if ($keepDb) { Write-Host "        - data\ (Datenbank)" }
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
if ($keepConfig -or $keepDb) {
    Write-Host "• Programmdateien geloescht; ausgewaehlte Daten in $DATA_DIR behalten"
} else {
    Write-Host "• Alle Dateien geloescht"
}
Write-Host "• Desktop-Verknuepfung entfernt"
Write-Host ""
