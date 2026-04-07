#!/bin/bash
set -e

# Arbeitsverzeichnis auf den Ordner des Skripts setzen
cd "$(dirname "$0")"

echo "============================================================"
echo "SignalReport - Linux/macOS Deinstallations-Tool"
echo "============================================================"
echo

# Root-Rechte prüfen
if [ "$EUID" -ne 0 ]; then
    echo "[FEHLER] Root-Rechte erforderlich!"
    echo "Bitte mit 'sudo ./uninstall.sh' ausführen."
    echo
    exit 1
fi

# Plattform erkennen
OS="$(uname -s)"
if [ "$OS" = "Linux" ]; then
    PLATFORM="linux"
    USER="signalreport"
    INSTALL_DIR="/opt/signalreport"
    DATA_DIR="/var/lib/signalreport"
    LOG_DIR="/var/log/signalreport"
elif [ "$OS" = "Darwin" ]; then
    PLATFORM="macos"
    USER="$(logname 2>/dev/null || echo "$SUDO_USER")"
    INSTALL_DIR="/Applications/SignalReport"
    DATA_DIR="/Users/$USER/Library/Application Support/SignalReport"
    LOG_DIR="$DATA_DIR/logs"
else
    echo "[FEHLER] Nicht unterstützte Plattform: $OS"
    exit 1
fi

# Dienst stoppen und entfernen
echo "[INFO] Stoppe SignalReport-Dienst..."
if [ "$PLATFORM" = "linux" ]; then
    systemctl stop signalreport 2>/dev/null || true
    systemctl disable signalreport 2>/dev/null || true
    rm -f /etc/systemd/system/signalreport.service
    systemctl daemon-reload
elif [ "$PLATFORM" = "macos" ]; then
    launchctl stop com.signalreport.service 2>/dev/null || true
    launchctl unload /Library/LaunchDaemons/com.signalreport.service.plist 2>/dev/null || true
    rm -f /Library/LaunchDaemons/com.signalreport.service.plist
fi

# Dateien löschen
echo "[INFO] Lösche Installationsverzeichnis: $INSTALL_DIR"
rm -rf "$INSTALL_DIR" 2>/dev/null || true

echo "[INFO] Lösche Datenverzeichnis: $DATA_DIR"
rm -rf "$DATA_DIR" 2>/dev/null || true

echo "[INFO] Lösche Log-Verzeichnis: $LOG_DIR"
rm -rf "$LOG_DIR" 2>/dev/null || true

# Benutzer entfernen (Linux)
if [ "$PLATFORM" = "linux" ]; then
    if id "$USER" &>/dev/null; then
        echo "[INFO] Entferne Systembenutzer $USER..."
        userdel "$USER" 2>/dev/null || true
    fi
fi

# Desktop-Verknüpfung entfernen (macOS)
if [ "$PLATFORM" = "macos" ]; then
    echo "[INFO] Entferne Desktop-Verknüpfung..."
    rm -f "/Users/$USER/Desktop/SignalReport - Verbindungsanalyse.webloc" 2>/dev/null || true
fi

echo
echo "============================================================"
echo "✅ Deinstallation abgeschlossen!"
echo "============================================================"
echo "• SignalReport-Dienst entfernt"
echo "• Alle Dateien gelöscht"
if [ "$PLATFORM" = "macos" ]; then
    echo "• Desktop-Verknüpfung entfernt"
fi
echo