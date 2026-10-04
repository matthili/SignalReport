#!/bin/bash
# SignalReport - Datenbank-Neuaufbau (Linux/macOS)
# Aufruf: sudo bash dbrebuild.sh
#
# Stoppt den SignalReport-Dienst, baut die Datenbank aus Primary und Shadow in eine
# frische, kompakte Datei neu auf (java -jar signalreport.jar rebuild-db) und startet
# den Dienst wieder. Die alten Dateien werden NICHT geloescht, sondern nach
# <Datenverzeichnis>/data/quarantine/rebuild_<Zeit>/ verschoben. Der Bericht liegt
# danach unter <Datenverzeichnis>/data/signalreport_rebuild-report_<Zeit>.txt.
set -u

echo "============================================================"
echo "SignalReport - Datenbank-Neuaufbau"
echo "============================================================"
echo

if [ "$EUID" -ne 0 ]; then
    echo "[FEHLER] Root-Rechte erforderlich! Bitte mit 'sudo bash dbrebuild.sh' ausfuehren."
    exit 1
fi
if ! command -v java &> /dev/null; then
    echo "[FEHLER] Java nicht gefunden."
    exit 1
fi

OS="$(uname -s)"
if [ "$OS" = "Linux" ]; then
    PLATFORM="linux"
    SERVICE_USER="signalreport"
    INSTALL_DIR="/opt/signalreport"
    DATA_DIR="/var/lib/signalreport"
elif [ "$OS" = "Darwin" ]; then
    PLATFORM="macos"
    SERVICE_USER="$(logname 2>/dev/null || echo "$SUDO_USER")"
    INSTALL_DIR="/Applications/SignalReport"
    DATA_DIR="/Users/$SERVICE_USER/Library/Application Support/SignalReport"
else
    echo "[FEHLER] Nicht unterstuetzte Plattform: $OS"
    exit 1
fi

if [ ! -f "$INSTALL_DIR/signalreport.jar" ]; then
    echo "[FEHLER] $INSTALL_DIR/signalreport.jar nicht gefunden. Ist SignalReport installiert?"
    exit 1
fi
if [ ! -f "$DATA_DIR/data/signalreport.mv.db" ] && [ ! -f "$DATA_DIR/data/signalreport-shadow.mv.db" ]; then
    echo "[FEHLER] Keine Datenbankdatei unter $DATA_DIR/data gefunden."
    exit 1
fi

# 1. Dienst stoppen (SIGTERM -> Shutdown-Hook schliesst die Datenbanken sauber)
echo "[INFO] Stoppe SignalReport-Dienst..."
if [ "$PLATFORM" = "linux" ]; then
    systemctl stop signalreport 2>/dev/null || true
else
    launchctl bootout system/com.signalreport.service 2>/dev/null || \
        launchctl unload /Library/LaunchDaemons/com.signalreport.service.plist 2>/dev/null || true
fi
sleep 3

# 2. Neuaufbau als Dienst-Benutzer im Datenverzeichnis ausfuehren (Dateirechte bleiben stimmig).
#    -XX:TieredStopAtLevel=1 laesst nur den einfachen JIT-Compiler (C1) arbeiten: Auf dem
#    Referenzsystem stuerzte JDK 26.0.1 mit dem optimierenden Compiler (C2) mitten im
#    Neuaufbau ab (EXCEPTION_ACCESS_VIOLATION in java.util.TimSort). Fuer dieses einmalige,
#    vor allem festplattenlastige Werkzeug kostet C1 wenig und vermeidet solche Abstuerze.
echo "[INFO] Java fuer den Neuaufbau:"
java -version 2>&1 | sed 's/^/       /'
echo "[INFO] Starte Neuaufbau (das kann je nach Dateigroesse einige Minuten dauern)..."
echo
sudo -u "$SERVICE_USER" bash -c "cd '$DATA_DIR' && java -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8 -jar '$INSTALL_DIR/signalreport.jar' rebuild-db"
RESULT=$?
echo
if [ $RESULT -eq 0 ]; then
    echo "[OK] Neuaufbau abgeschlossen."
else
    echo "[FEHLER] Neuaufbau fehlgeschlagen (Exit-Code $RESULT). Die alten Dateien sind unveraendert."
fi
REPORT="$(ls -t "$DATA_DIR"/data/signalreport_rebuild-report_*.txt 2>/dev/null | head -n 1)"
if [ -n "$REPORT" ]; then
    echo "[INFO] Bericht: $REPORT"
fi

# 3. Dienst wieder starten (auch nach einem Fehlschlag, damit die Messung weiterlaeuft)
echo "[INFO] Starte SignalReport-Dienst..."
if [ "$PLATFORM" = "linux" ]; then
    systemctl start signalreport 2>/dev/null || true
    sleep 2
    if systemctl is-active --quiet signalreport; then
        echo "[OK] SignalReport-Dienst laeuft wieder."
    else
        echo "[WARNUNG] Dienst gestartet, aber Status unklar (journalctl -u signalreport)."
    fi
else
    if launchctl bootstrap system /Library/LaunchDaemons/com.signalreport.service.plist 2>/dev/null; then
        launchctl kickstart system/com.signalreport.service 2>/dev/null || true
    else
        launchctl load /Library/LaunchDaemons/com.signalreport.service.plist 2>/dev/null || true
        launchctl start com.signalreport.service 2>/dev/null || true
    fi
    echo "[OK] Startbefehl an launchd gesendet."
fi
echo
exit $RESULT
