#!/bin/bash
set -e

echo "============================================================"
echo "SignalReport - Linux/macOS Installations-Tool"
echo "(Apache Commons Daemon 1.5.1 - jsvc)"
echo "============================================================"
echo

# Root-Rechte prüfen
if [ "$EUID" -ne 0 ]; then
    echo "[FEHLER] Root-Rechte erforderlich!"
    echo "Bitte mit 'sudo ./install.sh' ausführen."
    echo
    exit 1
fi

# Java prüfen
echo "[INFO] Prüfe Java-Installation..."
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
    echo "[FEHLER] Nicht unterstützte Plattform: $OS"
    exit 1
fi

echo "[INFO] Plattform: $OS"
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

# Java-Home ermitteln (MUSS vor jsvc-Kompilierung passieren)
if [ -z "$JAVA_HOME" ]; then
    JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || echo /usr/lib/jvm/default-java)"
    export JAVA_HOME
fi
echo "[INFO] JAVA_HOME: $JAVA_HOME"

# Apache Commons Daemon herunterladen (jsvc für Unix)
echo "[INFO] Lade Apache Commons Daemon 1.5.1 (jsvc) herunter..."
DAEMON_URL="https://archive.apache.org/dist/commons/daemon/binaries/unix/commons-daemon-1.5.1-bin-unix.tar.gz"
curl -L -o /tmp/commons-daemon.tar.gz "$DAEMON_URL"
tar -xzf /tmp/commons-daemon.tar.gz -C /tmp/
JSVC_SRC="/tmp/commons-daemon-1.5.1-bin-unix/jsvc"

# jsvc kompilieren (erfordert build-essential/make)
if [ "$PLATFORM" = "linux" ]; then
    if ! command -v make &> /dev/null; then
        echo "[WARNUNG] 'make' nicht gefunden. Bitte installieren:"
        echo "  Ubuntu/Debian: sudo apt install build-essential"
        echo "  CentOS/RHEL: sudo yum groupinstall 'Development Tools'"
        echo "Überspringe jsvc-Installation – verwende Java direkt als Fallback."
        JSVC_BIN=""
    else
        echo "[INFO] Kompiliere jsvc..."
        cd "$JSVC_SRC"
        ./configure --with-java="$JAVA_HOME" > /dev/null 2>&1
        make > /dev/null 2>&1
        cp jsvc "$INSTALL_DIR/jsvc"
        chown "$USER:$GROUP" "$INSTALL_DIR/jsvc"
        chmod 755 "$INSTALL_DIR/jsvc"
        JSVC_BIN="$INSTALL_DIR/jsvc"
        cd - > /dev/null
    fi
elif [ "$PLATFORM" = "macos" ]; then
    if ! command -v xcode-select &> /dev/null; then
        echo "[WARNUNG] Xcode Command Line Tools nicht gefunden."
        echo "Bitte installiere: xcode-select --install"
        echo "Überspringe jsvc-Installation – verwende Java direkt als Fallback."
        JSVC_BIN=""
    else
        echo "[INFO] Kompiliere jsvc..."
        cd "$JSVC_SRC"
        ./configure --with-java="$JAVA_HOME" > /dev/null 2>&1
        make > /dev/null 2>&1
        cp jsvc "$INSTALL_DIR/jsvc"
        chown "$USER:$GROUP" "$INSTALL_DIR/jsvc"
        chmod 755 "$INSTALL_DIR/jsvc"
        JSVC_BIN="$INSTALL_DIR/jsvc"
        cd - > /dev/null
    fi
fi

# Systemd-Service erstellen (Linux)
if [ "$PLATFORM" = "linux" ]; then
    if [ -n "$JSVC_BIN" ] && [ -f "$JSVC_BIN" ]; then
        # Mit jsvc (professionell)
        echo "[INFO] Erstelle Systemd-Service mit jsvc..."
        cat > /etc/systemd/system/signalreport.service <<EOF
[Unit]
Description=SignalReport Internet Monitoring
After=network.target

[Service]
Type=forking
User=$USER
Group=$GROUP
ExecStart=$JSVC_BIN \
    -home $JAVA_HOME \
    -cwd $INSTALL_DIR \
    -outfile $LOG_DIR/stdout.log \
    -errfile $LOG_DIR/stderr.log \
    -pidfile /var/run/signalreport.pid \
    -cp $INSTALL_DIR/signalreport.jar \
    at.mafue.signalreport.SignalReportApp
ExecStop=$JSVC_BIN -stop \
    -home $JAVA_HOME \
    -cwd $INSTALL_DIR \
    -pidfile /var/run/signalreport.pid \
    -cp $INSTALL_DIR/signalreport.jar \
    at.mafue.signalreport.SignalReportApp
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF
    else
        # Fallback: Java direkt (einfacher, aber weniger robust)
        echo "[INFO] Erstelle Systemd-Service (Java-Fallback)..."
        cat > /etc/systemd/system/signalreport.service <<EOF
[Unit]
Description=SignalReport Internet Monitoring
After=network.target

[Service]
Type=simple
User=$USER
Group=$GROUP
WorkingDirectory=$INSTALL_DIR
ExecStart=$JAVA_HOME/bin/java -jar signalreport.jar
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
        <string>$INSTALL_DIR</string>
        <string>-outfile</string>
        <string>$LOG_DIR/stdout.log</string>
        <string>-errfile</string>
        <string>$LOG_DIR/stderr.log</string>
        <string>-pidfile</string>
        <string>/var/run/signalreport.pid</string>
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
    <string>$INSTALL_DIR</string>
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
    launchctl load "$LAUNCHD_DIR/com.signalreport.service.plist" 2>/dev/null || true
    launchctl start com.signalreport.service 2>/dev/null || true

    echo "[OK] launchd-Service erstellt."
fi

# Desktop-Verknüpfung (macOS)
if [ "$PLATFORM" = "macos" ]; then
    echo "[INFO] Erstelle Desktop-Verknüpfung..."
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
echo "✅ Installation abgeschlossen!"
echo "============================================================"
echo "• Web-Oberfläche: http://localhost:4567"
echo "• Dienst läuft im Hintergrund (auch ohne Login)"
echo "• Datenverzeichnis: $DATA_DIR"
echo "• Logs: $LOG_DIR"
if [ "$PLATFORM" = "macos" ]; then
    echo "• Desktop-Verknüpfung erstellt"
fi
if [ -n "$JSVC_BIN" ] && [ -f "$JSVC_BIN" ]; then
    echo "• jsvc verwendet (professioneller Daemon-Modus)"
else
    echo "• Java-Fallback verwendet (einfacher Modus)"
fi
echo
echo "Hinweis: Der Dienst startet automatisch nach jedem Neustart."
echo