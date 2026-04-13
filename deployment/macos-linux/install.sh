#!/bin/bash
set -e

# Arbeitsverzeichnis auf den Ordner des Skripts setzen
cd "$(dirname "$0")"

echo "============================================================"
echo "SignalReport - Linux/macOS Installations-Tool"
echo "============================================================"
echo

# Root-Rechte pruefen
if [ "$EUID" -ne 0 ]; then
    echo "[FEHLER] Root-Rechte erforderlich!"
    echo "Bitte mit 'sudo bash install.sh' ausfuehren."
    echo
    exit 1
fi

# Java pruefen
echo "[INFO] Pruefe Java-Installation..."
if ! command -v java &> /dev/null; then
    echo "[FEHLER] Java 21+ nicht gefunden!"
    echo "Bitte installiere Java zuerst:"
    echo "  Ubuntu/Debian: sudo apt install openjdk-21-jdk"
    echo "  macOS: brew install openjdk@21"
    echo
    exit 1
fi

# Plattform erkennen
OS="$(uname -s)"
if [ "$OS" = "Linux" ]; then
    PLATFORM="linux"
    USER="signalreport"
    GROUP="signalreport"
    INSTALL_DIR="/opt/signalreport"
    DATA_DIR="/var/lib/signalreport"
    LOG_DIR="/var/log/signalreport"
elif [ "$OS" = "Darwin" ]; then
    PLATFORM="macos"
    USER="$(logname 2>/dev/null || echo "$SUDO_USER")"
    GROUP="staff"
    INSTALL_DIR="/Applications/SignalReport"
    DATA_DIR="/Users/$USER/Library/Application Support/SignalReport"
    LOG_DIR="$DATA_DIR/logs"
else
    echo "[FEHLER] Nicht unterstuetzte Plattform: $OS"
    exit 1
fi

PID_DIR="/var/run"
if [ "$PLATFORM" = "macos" ]; then
    PID_DIR="$DATA_DIR"
fi
PID_FILE="$PID_DIR/signalreport.pid"

echo "[INFO] Plattform: $OS ($(uname -m))"
echo "[INFO] Installationsverzeichnis: $INSTALL_DIR"
echo "[INFO] Datenverzeichnis: $DATA_DIR"
echo "[INFO] Log-Verzeichnis: $LOG_DIR"
echo

# Benutzer erstellen (Linux)
if [ "$PLATFORM" = "linux" ]; then
    if ! id "$USER" &>/dev/null; then
        echo "[INFO] Erstelle Systembenutzer $USER..."
        useradd --system --no-create-home --shell /usr/sbin/nologin "$USER"
    fi
fi

# Verzeichnisse erstellen
mkdir -p "$INSTALL_DIR"
mkdir -p "$DATA_DIR"
mkdir -p "$LOG_DIR"
chown -R "$USER:$GROUP" "$INSTALL_DIR" "$DATA_DIR" "$LOG_DIR"
chmod -R 755 "$INSTALL_DIR"
chmod -R 700 "$DATA_DIR" "$LOG_DIR"

# JAR kopieren
if [ ! -f "signalreport.jar" ]; then
    echo "[FEHLER] signalreport.jar nicht gefunden!"
    echo "Bitte starte das Skript im selben Verzeichnis wie die JAR-Datei."
    exit 1
fi
cp signalreport.jar "$INSTALL_DIR/signalreport.jar"
chown "$USER:$GROUP" "$INSTALL_DIR/signalreport.jar"

# Java-Home ermitteln
if [ -z "$JAVA_HOME" ]; then
    if [ "$PLATFORM" = "macos" ]; then
        JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null)"
    else
        # Linux: verschiedene Distributionen unterstuetzen
        JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which java)")")")"
    fi
    export JAVA_HOME
fi
echo "[INFO] JAVA_HOME: $JAVA_HOME"

# jsvc-Kompilierung (optional - Java-Fallback funktioniert genauso gut)
JSVC_BIN=""
SKIP_JSVC=false

# Build-Tools pruefen
if [ "$PLATFORM" = "linux" ]; then
    if ! command -v make &> /dev/null || ! command -v gcc &> /dev/null; then
        echo "[INFO] Build-Tools (make/gcc) nicht gefunden."
        echo "       Verwende Java-Fallback (funktioniert einwandfrei)."
        SKIP_JSVC=true
    fi
elif [ "$PLATFORM" = "macos" ]; then
    if ! command -v xcode-select &> /dev/null; then
        echo "[INFO] Xcode Command Line Tools nicht gefunden."
        echo "       Verwende Java-Fallback (funktioniert einwandfrei)."
        SKIP_JSVC=true
    fi
fi

if [ "$SKIP_JSVC" = false ]; then
    echo "[INFO] Lade Apache Commons Daemon 1.5.1 (jsvc Source) herunter..."
    DAEMON_URL="https://archive.apache.org/dist/commons/daemon/source/commons-daemon-1.5.1-src.tar.gz"

    if curl -L --fail --connect-timeout 15 --max-time 60 -o /tmp/commons-daemon-src.tar.gz "$DAEMON_URL" 2>/dev/null; then
        tar -xzf /tmp/commons-daemon-src.tar.gz -C /tmp/
        JSVC_SRC="/tmp/commons-daemon-1.5.1-src/src/native/unix"

        if [ -d "$JSVC_SRC" ]; then
            echo "[INFO] Kompiliere jsvc (kann 1-2 Minuten dauern)..."
            COMPILE_OK=true

            cd "$JSVC_SRC"
            # configure und make mit sichtbarer Ausgabe bei Fehlern
            if ! ./configure --with-java="$JAVA_HOME" > /tmp/jsvc-configure.log 2>&1; then
                echo "[WARNUNG] jsvc configure fehlgeschlagen (siehe /tmp/jsvc-configure.log)"
                COMPILE_OK=false
            fi

            if [ "$COMPILE_OK" = true ]; then
                if ! make > /tmp/jsvc-make.log 2>&1; then
                    echo "[WARNUNG] jsvc make fehlgeschlagen (siehe /tmp/jsvc-make.log)"
                    COMPILE_OK=false
                fi
            fi

            if [ "$COMPILE_OK" = true ] && [ -f jsvc ]; then
                cp jsvc "$INSTALL_DIR/jsvc"
                chown "$USER:$GROUP" "$INSTALL_DIR/jsvc"
                chmod 755 "$INSTALL_DIR/jsvc"
                JSVC_BIN="$INSTALL_DIR/jsvc"
                echo "[OK] jsvc erfolgreich kompiliert."
            else
                echo "[INFO] jsvc-Kompilierung fehlgeschlagen."
                echo "       Verwende Java-Fallback (funktioniert einwandfrei)."
            fi
            cd - > /dev/null
        else
            echo "[WARNUNG] jsvc-Source-Verzeichnis nicht gefunden."
            echo "          Verwende Java-Fallback."
        fi
    else
        echo "[WARNUNG] Download von Apache Commons Daemon fehlgeschlagen."
        echo "          Verwende Java-Fallback (funktioniert einwandfrei)."
    fi

    # Temporaere Dateien aufraeumen
    rm -rf /tmp/commons-daemon-src.tar.gz /tmp/commons-daemon-1.5.1-src 2>/dev/null || true
fi

# Systemd-Service erstellen (Linux)
if [ "$PLATFORM" = "linux" ]; then
    if [ -n "$JSVC_BIN" ] && [ -f "$JSVC_BIN" ]; then
        # Mit jsvc (professionell)
        echo "[INFO] Erstelle Systemd-Service mit jsvc..."
        cat > /etc/systemd/system/signalreport.service <<EOF
[Unit]
Description=SignalReport Internet Monitoring
After=network-online.target
Wants=network-online.target

[Service]
Type=forking
User=$USER
Group=$GROUP
ExecStart=$JSVC_BIN \
    -home $JAVA_HOME \
    -cwd $DATA_DIR \
    -outfile $LOG_DIR/stdout.log \
    -errfile $LOG_DIR/stderr.log \
    -pidfile $PID_FILE \
    -cp $INSTALL_DIR/signalreport.jar \
    at.mafue.signalreport.SignalReportApp
ExecStop=$JSVC_BIN -stop \
    -home $JAVA_HOME \
    -cwd $DATA_DIR \
    -pidfile $PID_FILE \
    -cp $INSTALL_DIR/signalreport.jar \
    at.mafue.signalreport.SignalReportApp
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF
    else
        # Fallback: Java direkt (einfacher, aber genauso zuverlaessig)
        echo "[INFO] Erstelle Systemd-Service (Java-Modus)..."
        cat > /etc/systemd/system/signalreport.service <<EOF
[Unit]
Description=SignalReport Internet Monitoring
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$USER
Group=$GROUP
WorkingDirectory=$DATA_DIR
ExecStart=$JAVA_HOME/bin/java -jar $INSTALL_DIR/signalreport.jar
ExecStop=/bin/kill -SIGTERM \$MAINPID
Restart=always
RestartSec=10
StandardOutput=append:$LOG_DIR/stdout.log
StandardError=append:$LOG_DIR/stderr.log

[Install]
WantedBy=multi-user.target
EOF
    fi

    systemctl daemon-reload
    systemctl enable signalreport
    systemctl start signalreport

    echo "[OK] Systemd-Service erstellt und gestartet."
fi

# launchd-Service erstellen (macOS)
if [ "$PLATFORM" = "macos" ]; then
    echo "[INFO] Erstelle launchd-Service..."
    LAUNCHD_DIR="/Library/LaunchDaemons"
    mkdir -p "$LAUNCHD_DIR"

    if [ -n "$JSVC_BIN" ] && [ -f "$JSVC_BIN" ]; then
        # Mit jsvc
        cat > "$LAUNCHD_DIR/com.signalreport.service.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.signalreport.service</string>
    <key>ProgramArguments</key>
    <array>
        <string>$JSVC_BIN</string>
        <string>-home</string>
        <string>$JAVA_HOME</string>
        <string>-cwd</string>
        <string>$DATA_DIR</string>
        <string>-outfile</string>
        <string>$LOG_DIR/stdout.log</string>
        <string>-errfile</string>
        <string>$LOG_DIR/stderr.log</string>
        <string>-pidfile</string>
        <string>$PID_FILE</string>
        <string>-cp</string>
        <string>$INSTALL_DIR/signalreport.jar</string>
        <string>at.mafue.signalreport.SignalReportApp</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>UserName</key>
    <string>$USER</string>
</dict>
</plist>
EOF
    else
        # Fallback: Java direkt
        cat > "$LAUNCHD_DIR/com.signalreport.service.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.signalreport.service</string>
    <key>ProgramArguments</key>
    <array>
        <string>$JAVA_HOME/bin/java</string>
        <string>-jar</string>
        <string>$INSTALL_DIR/signalreport.jar</string>
    </array>
    <key>WorkingDirectory</key>
    <string>$DATA_DIR</string>
    <key>StandardOutPath</key>
    <string>$LOG_DIR/stdout.log</string>
    <key>StandardErrorPath</key>
    <string>$LOG_DIR/stderr.log</string>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>UserName</key>
    <string>$USER</string>
</dict>
</plist>
EOF
    fi

    chown root:wheel "$LAUNCHD_DIR/com.signalreport.service.plist"
    chmod 644 "$LAUNCHD_DIR/com.signalreport.service.plist"
    # Neuere macOS-Versionen (10.15+) verwenden bootstrap, aeltere load
    if launchctl bootstrap system "$LAUNCHD_DIR/com.signalreport.service.plist" 2>/dev/null; then
        launchctl kickstart system/com.signalreport.service 2>/dev/null || true
    else
        launchctl load "$LAUNCHD_DIR/com.signalreport.service.plist" 2>/dev/null || true
        launchctl start com.signalreport.service 2>/dev/null || true
    fi

    echo "[OK] launchd-Service erstellt."
fi

# Desktop-Verknuepfung (macOS)
if [ "$PLATFORM" = "macos" ]; then
    echo "[INFO] Erstelle Desktop-Verknuepfung..."
    cat > "/Users/$USER/Desktop/SignalReport - Verbindungsanalyse.webloc" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>URL</key>
    <string>http://localhost:4567</string>
</dict>
</plist>
EOF
    chmod 644 "/Users/$USER/Desktop/SignalReport - Verbindungsanalyse.webloc"
fi

echo
echo "============================================================"
echo "Installation abgeschlossen!"
echo "============================================================"
echo "- Web-Oberflaeche: http://localhost:4567"
echo "- Dienst laeuft im Hintergrund (auch ohne Login)"
echo "- Datenverzeichnis: $DATA_DIR"
echo "- Logs: $LOG_DIR"
if [ "$PLATFORM" = "macos" ]; then
    echo "- Desktop-Verknuepfung erstellt"
fi
if [ -n "$JSVC_BIN" ] && [ -f "$JSVC_BIN" ]; then
    echo "- jsvc verwendet (Daemon-Modus)"
else
    echo "- Java-Modus verwendet"
fi
echo
echo "Hinweis: Der Dienst startet automatisch nach jedem Neustart."
echo
