<p align="center">
  <img src="src/main/resources/web/logo_mit_schriftzug_dark_git.png" alt="SignalReport" width="500">
</p>

<p align="center">
  <a href="https://openjdk.org/"><img src="https://img.shields.io/badge/Java-21+-007396?logo=java" alt="Java 21+"></a>
  <a href="https://opensource.org/licenses/MIT"><img src="https://img.shields.io/badge/License-MIT-green.svg" alt="License: MIT"></a>
  <a href="https://junit.org/"><img src="https://img.shields.io/badge/Tests-119%20passing-brightgreen" alt="JUnit Tests"></a>
</p>

<p align="center">
  <a href="README.md">English</a> | <b>Deutsch</b>
</p>

Ein professionelles, Open-Source Monitoring-Tool zur kontinuierlichen Überwachung deiner Internet-Qualität – mit PDF-Berichten für Provider-Beschwerden, IP-Tracking und DNS-Benchmark.

> 💡 **Warum SignalReport?**  
> *"Mein Internet ist langsam!"* reicht bei Providern nicht. Mit SignalReport lieferst du **nachweisbare, quantifizierte Belege** – nicht nur Bauchgefühl.

> 📜 **Projektgeschichte**  
> SignalReport entstand zwischen Jänner und April 2026 als Abschlussprojekt des Diplomlehrgangs *"Software Developer:in"* am WIFI Wien (Kurs 18195015) und wird seither als persönliches Open-Source-Projekt aktiv weiterentwickelt. Wer den exakten Stand zum Zeitpunkt der Diplomprüfung begutachten möchte, findet diesen als Release [`V1`](https://github.com/matthili/SignalReport/releases/tag/V1).

---

## 🌟 Features

| Kategorie | Funktionen |
|-----------|------------|
| **Monitoring** | 🔁 Kontinuierliche Messung (Ping/DNS/HTTP + Gateways)<br>⏱️ Konfigurierbares Intervall (5s–1h, Standard 30s)<br>⏸️ Maintenance-Fenster (Router-Updates)<br>🌐 IP-Tracking (externe IP-Änderungen erkennen) |
| **Störungs-Lokalisierung & Zuverlässigkeit** | 🛰️ Router vs. Internet-Gateway vs. Provider lokalisieren (Traceroute-Gateway-Kette)<br>🐳 virtuelle-Gateway-Erkennung in VM/Docker<br>📈 Verfügbarkeit, Abdeckung, MTBF & MTTR (lückenbewusst)<br>📉 aggregierte Verbindungsausfälle, einzeln aus der Wertung nehmbar |
| **Visualisierung** | 📊 Live-Charts mit Chart.js<br>📋 Mess-Tabelle pro Messzyklus (aufklappbar)<br>🌡️ Heatmap pro Stunde<br>🖥️ Web-Oberfläche (responsiv)<br>🔔 Browser-Push bei Ausfällen/Hoher Latenz |
| **Berichte** | 📄 PDF-Export (24h/7 Tage/12 Monate)<br>📈 3 Charts (PING/DNS/HTTP) mit Ziel-Änderungs-Markierung<br>🏆 Top 10 schlechteste Messungen<br>⚠️ Verbindungsausfall-Analyse<br>📤 CSV-Export (vollständig oder gefiltert) |
| **Sicherheit** | 🔐 Setup-Wizard (Web-basiert, keine CLI)<br>🔑 Challenge-Response-Authentifizierung (SHA-256)<br>👥 Admin/User-Rollen mit Session-Management<br>🛡️ Passwort wird nie im Klartext übertragen |
| **Datensicherheit** | 🛟 Twin-Datenbank (synchrone Spiegelung)<br>🔄 Auto-Recovery beim Start (Korruption → Rekonstruktion aus intakter Kopie)<br>⚡ Synchrone Schreibvorgänge (`WRITE_DELAY=0`)<br>🛡️ Schutz vor Crash durch Update-Reboots / Stromausfälle |
| **Mehrsprachigkeit** | 🌐 9 Sprachen: Deutsch, English, Français, Italiano, Español, Português, Türkçe, Polski, Українська<br>🔤 Gilt für Web-UI, PDF-Berichte und CSV-Exporte<br>🎛️ Sprachwahl im Setup-Wizard und in den Einstellungen<br>📂 Erweiterbar ohne Neukompilieren: eigene Sprachdatei in `./lang/` ablegen |
| **Konfiguration** | ⚙️ Dynamische Messziele (Ping/DNS/HTTP)<br>🌍 DNS-Benchmark (Server weltweit)<br>👤 Benutzer-Info (Provider/Kundennummer für Berichte) |

---

## 🚀 Schnellstart

### Voraussetzungen
- Java 21 oder höher ([Download](https://adoptium.net/))
- (Optional) Maven für Build aus Quelle

### Installation & Start
```bash
# 1. JAR herunterladen (oder mit Maven bauen: mvn clean package)
java -jar signalreport.jar

# 2. Browser öffnen
http://localhost:4567

# 3. Setup-Wizard durchlaufen (Admin-Passwort festlegen)
```

✅ **Fertig!** Die Messung beginnt automatisch – standardmäßig alle 30 Sekunden werden Ping, DNS und HTTP (und ggf. die Gateways) getestet.

---

## 📦 Installation als Dienst

Für den Dauerbetrieb (auch ohne angemeldeten Benutzer) kann SignalReport als Hintergrund-Dienst installiert werden.

### Windows
1. [`signalreport_windows.zip`](deployment/signalreport_windows.zip) herunterladen und entpacken
2. `install.bat` mit Rechtsklick → *"Als Administrator ausführen"* starten
3. Das Skript installiert den Dienst, erstellt eine Desktop-Verknüpfung und richtet die Firewall ein

### macOS / Linux
1. [`signalreport_mac-linux.zip`](deployment/signalreport_mac-linux.zip) herunterladen und entpacken
2. Terminal öffnen und ausführen: `sudo bash install.sh`
3. Der Dienst wird als systemd-Service (Linux) bzw. launchd-Service (macOS) eingerichtet

> 💡 Die [`signalreport.jar`](deployment/signalreport.jar) muss sich im selben Verzeichnis wie die Installations-Skripte befinden.

### Deinstallation
- **Windows**: `uninstall.bat` Rechtsklick → als Administrator ausführen
- **macOS/Linux**: `sudo bash uninstall.sh` (im Terminal)

---

## 📸 Screenshots

| Web-Oberfläche | PDF-Bericht (Auszug) | DNS-Benchmark |
|----------------|----------------------|---------------|
| ![Dashboard](docs/screenshots/dashboard.png) | ![PDF Report](docs/screenshots/pdf-report.png) | ![DNS Benchmark](docs/screenshots/dns-benchmark.png) |
| *Live-Charts, Statistiken, Einstellungen* | *Professioneller Bericht für Provider* | *Vergleich globaler DNS-Server* |

---

## ⚙️ Konfiguration

Nach dem ersten Start wird `config.json` erstellt. Wichtige Einstellungen:

```json
{
  "language": "de",
  "measurement": {
    "intervalSeconds": 30,
    "targets": {
      "ping": "8.8.8.8",
      "dns": "google.com",
      "http": "https://example.com"
    }
  },
  "maintenanceWindow": {
    "enabled": true,
    "startHour": 4,
    "startMinute": 0,
    "endHour": 4,
    "endMinute": 10
  },
  "userInfo": {
    "provider": "Oranga",
    "customerId": "08154711",
    "userName": "Matthias F."
  }
}
```

💡 **Tipp**: Änderungen über die Web-Oberfläche (Tab *⚙️ Einstellungen*) werden sofort übernommen und persistiert!

### 🛰️ Störungs-Lokalisierung & virtuelle Gateways

Für die **Störungs-Lokalisierung** (eigener Router vs. Provider) ermittelt SignalReport beim Start per Traceroute den nächsten Router und das Gateway ins Internet. Läuft SignalReport in einer **VM oder einem Container**, kann der erkannte „Router" allerdings ein virtuelles NAT-Gerät sein statt der echten Hardware:

- **Docker-Standard-Bridge** (`172.17.0.0/16`) wird automatisch übersprungen, sofern der echte Router als nächster Hop dahinter sichtbar ist.
- **VirtualBox-/QEMU-NAT** (`10.0.2.0/24`) sowie Docker-Bridges innerhalb eines Containers werden als virtuell erkannt → ein **Warnhinweis** erscheint im Tab *⚙️ Einstellungen* unter *Gateways*.
- **VMware-NAT** (`192.168.x.2`) und ähnliche NATs in echten Heimnetz-Bereichen lassen sich per IP nicht von einem echten Router unterscheiden und werden bewusst **nicht** automatisch markiert (sonst Fehlalarme).

**Lösung** in solchen Fällen: im Tab *⚙️ Einstellungen* unter *Gateways* die echte Router-IP **manuell** setzen, oder für das Internet-Gateway *„nicht kontinuierlich pingen"* aktivieren. Manuell gesetzte IPs lassen sich optional auch über einen IP-Wechsel hinweg beibehalten.

---

## 🌐 Mehrsprachigkeit

SignalReport ist vollständig mehrsprachig – Web-Oberfläche, PDF-Berichte und CSV-Exporte. Mitgeliefert werden **9 Sprachen**:

🇩🇪 Deutsch · 🇬🇧 English · 🇫🇷 Français · 🇮🇹 Italiano · 🇪🇸 Español · 🇵🇹 Português · 🇹🇷 Türkçe · 🇵🇱 Polski · 🇺🇦 Українська

- **Umstellen**: im Setup-Wizard (vor der Passwortvergabe) oder jederzeit im Tab *⚙️ Einstellungen*
- **Standard**: Neuinstallationen übernehmen die Systemsprache (sonst Englisch); bestehende Installationen bleiben auf Deutsch
- **Eigene Sprache hinzufügen** (ohne Neukompilieren): eine `<code>.json`-Datei (z.B. `nl.json`) nach dem Vorbild von [`de.json`](src/main/resources/lang/de.json) in einen Ordner `lang/` neben der `signalreport.jar` legen – sie erscheint automatisch im Sprach-Dropdown
- **Unicode im PDF**: Die DejaVu-Schrift ist eingebettet, daher werden auch Türkisch, Polnisch und kyrillische Schrift (Ukrainisch) korrekt dargestellt

---

## 📊 PDF-Bericht – Perfekt für Provider-Beschwerden

Der 12-Monats-Bericht enthält:
- ✅ Deine Kundendaten (Name, Provider, Kundennummer)
- ✅ Host-Informationen (Hostname, Hash, IPs)
- ✅ 3 Charts mit roten Linien bei Ziel-Änderungen
- ✅ Chronologische Liste der gemessenen Ziele
- ✅ Top 10 schlechteste Messungen (mit Zeitstempel)
- ✅ Top 10 längste Verbindungsausfälle
- ✅ Gesamtanzahl der Ausfälle

> 📌 **So nutzt du den Bericht**:  
> 1. PDF mit "📄 PDF-Bericht (12 Monate)" generieren  
> 2. Als `signalreport-providername-beschwerde-2026-03-25.pdf` speichern  
> 3. An Support-Mail anhängen mit Text:  
> *"Anbei der technische Nachweis für wiederholte Verbindungsprobleme im Zeitraum XX bis YY. Bitte prüfen Sie die Leitung zu meinem Anschluss."*

---

## 🏗️ Projektstruktur

Eine kompakte Übersicht der Verzeichnisse und Klassen findest du in [`docs/ProjectStructure_de.md`](docs/ProjectStructure_de.md), die Architektur in [`docs/Architecture_de.md`](docs/Architecture_de.md).

```
signalreport/
├── src/main/java/at/mafue/signalreport/
│   ├── SignalReportApp.java              # Hauptklasse (Entry Point)
│   ├── config/                           # Schlankes Config + eine Datei je Bereich (Measurement, Gateway, Maintenance, Auth, Theme, …)
│   ├── measurement/                      # Measurer-Interface + Ping/Dns/Http, Measurement, DnsBenchmark
│   ├── network/                          # GatewayDiscovery (Traceroute), NetworkInfo, HostIdentifier
│   ├── storage/                          # H2MeasurementRepository (Twin-DB) + DTOs (Statistics, IpChange, …)
│   ├── report/                           # ReliabilityReport, ConnectivityAssessment, PdfReportGenerator
│   ├── web/                              # WebServer (Javalin-Orchestrator), SessionManager
│   │   ├── view/                         #   HtmlPageRenderer, SetupPageRenderer, LoginPageRenderer
│   │   └── api/                          #   9 Routen-Registrare (Measurement, Reliability, Settings, …)
│   ├── i18n/                             # I18n (9 Sprachen, erweiterbar)
│   └── notification/                     # PushNotificationService
├── src/test/java/at/mafue/signalreport/  # 12 Testklassen, 119 Tests (spiegeln die Pakete oben)
├── src/main/resources/web/               # Statische Assets: app.css, app.js, Logos, Favicons
├── src/main/resources/lang/              # Sprachdateien (de, en, fr, it, es, pt, tr, pl, uk)
├── src/main/resources/fonts/             # DejaVu-Schriften für PDF (Unicode/Kyrillisch)
├── docs/
│   ├── diagrams/                         # PlantUML-Diagramme (.puml + .png)
│   ├── latex/                            # Vollständige LaTeX-Dokumentation
│   └── screenshots/                      # UI-Screenshots
├── deployment/                           # Installations-Skripte (Win/Linux/macOS)
├── config.json                           # Auto-generierte Konfiguration
├── data/                                 # H2-Twin-Datenbank: Primary + Shadow (gitignored)
│   └── quarantine/                       # Defekte DB-Dateien zur Nachanalyse
├── pom.xml                               # Maven-Build-Konfiguration
└── README.md                             # Englische Nutzungsanleitung (diese: README_de.md)
```

---

## 📚 Dokumentation

- **Vollständige LaTeX-Dokumentation als PDF**: [`signalreport-dokumentation.pdf`](docs/latex/signalreport-dokumentation.pdf)
  Enthält UML-Diagramme, Architekturbeschreibung, Implementierungsdetails und Testbericht.
- **Architektur-Kurzübersicht**: [`docs/Architecture_de.md`](docs/Architecture_de.md)
- **Projektstruktur**: [`docs/ProjectStructure_de.md`](docs/ProjectStructure_de.md)
- **UML-Diagramme**: Alle 7 Diagramme als PNG und PlantUML in `docs/diagrams/`:
  - [`class-diagram.png`](docs/diagrams/class-diagram.png) – Klassenstruktur
  - [`component-diagram.png`](docs/diagrams/component-diagram.png) – Komponentenübersicht
  - [`sequence-measurement.png`](docs/diagrams/sequence-measurement.png) – Messungsablauf
  - [`sequence-pdf.png`](docs/diagrams/sequence-pdf.png) – PDF-Export-Prozess
  - [`usecase-diagram.png`](docs/diagrams/usecase-diagram.png) – Use-Cases
  - [`deployment-diagram.png`](docs/diagrams/deployment-diagram.png) – Deployment-Szenarien
  - [`state-diagram-auth.png`](docs/diagrams/state-diagram-auth.png) – Authentifizierungs-Zustände

---

## 🔒 Sicherheitshinweise

- **Challenge-Response Auth**: Passwörter werden nie im Klartext übertragen – SHA-256 mit Einmal-Nonces
- **Session-Management**: 24h Session-Timeout, sichere Cookie-basierte Authentifizierung
- **Authentifizierung**: Für öffentliche IPs (z.B. NAS mit Port-Weiterleitung) **unbedingt aktivieren** (Tab *🔐 Sicherheit*)
- **Setup-Passwort**: Admin-Passwort wird beim ersten Start festgelegt – niemals Standardwerte belassen!
- **Datenbank**: Alle Daten lokal gespeichert in einer crash-resistenten Twin-Datenbank (Primary + Shadow). Keine Cloud-Abhängigkeit, keine externen APIs (außer ipify.org für externe IP)

---

## 🤝 Mitwirken

SignalReport ist Open Source! Du kannst:
- 🐛 Fehler melden (Issues)
- 💡 Neue Features vorschlagen
- 📝 Dokumentation verbessern
- 🌍 Übersetzungen beisteuern (eine neue `lang/<code>.json` nach Vorbild von [`de.json`](src/main/resources/lang/de.json))

---

## 📜 Lizenz

MIT License – siehe [LICENSE](LICENSE) Datei.  
*Frei nutzbar für private und kommerzielle Zwecke – mit Quellenangabe.*

---

## 🙏 Danksagung

Dieses Projekt nutzt großartige Open-Source-Bibliotheken und Dienste:

**Backend-Bibliotheken (Maven/Java):**
- [Javalin](https://javalin.io/) – Leichtgewichtiges Web-Framework
- [H2 Database](https://www.h2database.com/) – Embedded SQL-Datenbank
- [Jackson](https://github.com/FasterXML/jackson) – JSON-Serialisierung (Config, API)
- [OpenPDF](https://github.com/LibrePDF/OpenPDF) – PDF-Erstellung
- [JFreeChart](https://www.jfree.org/jfreechart/) – Chart-Generierung für PDF-Reports
- [dnsjava](https://dnsjava.org/) – DNS-Queries für Benchmark
- [SLF4J](https://www.slf4j.org/) + [Logback](https://logback.qos.ch/) – Logging
- [JUnit 5](https://junit.org/junit5/) – Test-Framework

**Frontend (Browser):**
- [Chart.js](https://www.chartjs.org/) – Live-Charts im Web-Interface (via CDN)
- [Web Crypto API](https://developer.mozilla.org/en-US/docs/Web/API/Web_Crypto_API) – Client-seitiges SHA-256 Hashing für Challenge-Response Auth

**Schriften:**
- [DejaVu Fonts](https://dejavu-fonts.github.io/) – Eingebettete Unicode-Schrift für PDF-Berichte (Latein, Latein-Erweitert, Kyrillisch)

**Externe Dienste:**
- [ipify.org](https://www.ipify.org/) – Ermittlung der externen IP-Adresse

**Build & Deployment:**
- [Apache Maven](https://maven.apache.org/) – Build-System
- [Apache Commons Daemon](https://commons.apache.org/proper/commons-daemon/) – Service-Installation (procrun/jsvc)

**Dokumentation & Entwicklung:**
- [PlantUML](https://plantuml.com/) – UML-Diagramme
- [LaTeX](https://www.latex-project.org/) – Projektdokumentation
- [EmojiTerra](https://emojiterra.com/) – Emoji-Übersicht
- [StackOverflow](https://stackoverflow.com/) – Fragen/Antworten-Plattform für Softwareentwickler
- [Hitchhiker’s Guide to PlantUML](https://crashedmind.github.io/PlantUMLHitchhikersGuide/) – Nomen est omen

Außerdem gilt mein Dank:

- [Christian Schäfer](https://github.com/chris-cgsit/) – Java-Lehrgang und Projekt-Coaching
- [Serap Kadam](https://github.com/serap) – Unterstützung, Freundschaft, alles rund um Java
- [Angelika Winder] – Unterstützung in allen Lebenslagen (außer bei Java)


---

## 📮 Kontakt & Support

Bei Fragen zur Abschlussarbeit oder technischen Details gerne ein Issue auf GitHub eröffnen.


---

> 🌐 **SignalReport**  
> *Entwickelt mit ❤️ für alle, die wissen wollen, wie stabil ihre Internet-Verbindung ist.*
