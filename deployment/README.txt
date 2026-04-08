SignalReport – Internet-Qualitaets-Monitoring
=============================================

Dieses Verzeichnis enthaelt alles fuer die Installation von SignalReport
als Hintergrund-Dienst auf Windows, macOS und Linux.

Inhalt:
  signalreport.jar              Die Anwendung (Fat-JAR)
  signalreport_windows.zip      Windows-Installations-Paket
  signalreport_mac-linux.zip    macOS/Linux-Installations-Paket

Voraussetzung:
  Java 21 oder hoeher (https://adoptium.net/)

Installation:

  Windows:
  1. signalreport_windows.zip entpacken
  2. signalreport.jar in den entpackten Ordner kopieren
  3. install.bat mit Rechtsklick -> "Als Administrator ausfuehren" starten
  4. Fertig! Desktop-Verknuepfung oeffnet die Web-Oberflaeche
     Verwaltung: "%ProgramFiles%\SignalReport\prunmgr.exe"

  macOS/Linux:
  1. signalreport_mac-linux.zip entpacken
  2. signalreport.jar in den entpackten Ordner kopieren
  3. Terminal oeffnen: sudo bash install.sh
  4. Fertig! Oeffne http://localhost:4567 im Browser

Deinstallation:
  Windows:   uninstall.bat (als Administrator)
  macOS/Linux: sudo bash uninstall.sh

Hinweise:
  Der Dienst laeuft IMMER im Hintergrund – auch ohne Benutzer-Login.
  Daten werden gespeichert in:
    Windows:   C:\ProgramData\SignalReport
    Linux:     /var/lib/signalreport
    macOS:     ~/Library/Application Support/SignalReport
  Logs werden automatisch rotiert (taeglich, max. 50 MB gesamt).

Sicherheit:
  Authentifizierung erfolgt ueber Challenge-Response (SHA-256).
  Passwoerter werden nie im Klartext uebertragen.
  Fuer oeffentliche IPs unbedingt Authentifizierung aktivieren!

Technische Details:
  Windows:     Apache Commons Daemon 1.5.1 (prunsrv)
  Linux/macOS: Apache Commons Daemon 1.5.1 (jsvc) oder Java-Fallback
  Lizenz:      Apache License 2.0 (kostenlos, kommerziell nutzbar)
