SignalReport – Internet-Qualitäts-Monitoring
=============================================

Voraussetzung:
• Java 21 oder höher (https://adoptium.net/)

Installation:

Windows:
1. Lade "signalreport.jar" und den Ordner "windows" herunter
2. Öffne "windows/install.bat" mit rechter Maustaste → "Als Administrator ausführen"
3. Fertig! Die Desktop-Verknüpfung "SignalReport - Verbindungsanalyse" öffnet die Web-Oberfläche
4. Verwaltung: "%ProgramFiles%\SignalReport\prunmgr.exe" öffnen

macOS/Linux:
1. Lade "signalreport.jar" und den Ordner "macos-linux" herunter
2. Terminal öffnen → zum Download-Verzeichnis wechseln
3. Ausführen: sudo ./macos-linux/install.sh
4. Fertig! Öffne http://localhost:4567 im Browser

Deinstallation:
• Windows: windows/uninstall.bat (als Administrator)
• macOS/Linux: sudo ./macos-linux/uninstall.sh

Hinweise:
• Der Dienst läuft IMMER im Hintergrund – auch ohne Benutzer-Login
• Daten werden in folgendem Verzeichnis gespeichert:
  Windows:   C:\ProgramData\SignalReport
  Linux:     /var/lib/signalreport
  macOS:     ~/Library/Application Support/SignalReport
• Logs werden automatisch rotiert (täglich)

Technische Details:
• Windows: Apache Commons Daemon 1.5.1 (prunsrv)
• Linux/macOS: Apache Commons Daemon 1.5.1 (jsvc) oder Java-Fallback
• Lizenz: Apache License 2.0 (kostenlos, kommerziell nutzbar)