# SignalReport – Projektstruktur

> 🌐 [English](ProjectStructure.md) | **Deutsch**

```
SignalReport/
├── src/
│   ├── main/java/at/mafue/signalreport/  # Geschichtete Pakete (siehe unten)
│   │   ├── SignalReportApp.java          # Hauptklasse (Entry Point + kontinuierliche Mess-Schleife)
│   │   ├── config/                       # Konfiguration (Config + je eine Datei pro Aspekt)
│   │   │   ├── Config.java               # Singleton-Fassade (Laden/Speichern, Passwort-Hash, Defaults)
│   │   │   ├── MeasurementConfig.java    # Mess-Einstellungen (Intervall, …)
│   │   │   ├── Targets.java              # Ping-/DNS-/HTTP-Ziele
│   │   │   ├── GatewayConfig.java        # Gateway-Kette (nah/fern, manuelle IP, Optionen)
│   │   │   ├── DatabaseConfig.java       # Twin-Datenbank-Einstellungen
│   │   │   ├── WebserverConfig.java      # Webserver-Einstellungen (Port, …)
│   │   │   ├── DnsServer.java            # DNS-Server-Eintrag (Benchmark)
│   │   │   ├── MaintenanceWindow.java    # Geplantes Wartungsfenster
│   │   │   ├── UserInfo.java             # Benutzer-/Kontodaten
│   │   │   ├── AuthConfig.java           # Authentifizierungs-Einstellungen
│   │   │   ├── PushConfig.java           # Push-Benachrichtigungs-Einstellungen
│   │   │   ├── SetupConfig.java          # Zustand des Setup-Wizards
│   │   │   └── ThemeConfig.java          # Design (Dark Mode)
│   │   ├── measurement/                  # Mess-Engine (Strategy-Pattern)
│   │   │   ├── Measurer.java             # Interface (Strategy-Pattern)
│   │   │   ├── Measurement.java          # Domänenmodell (ein Zyklus / Einzelwert)
│   │   │   ├── PingMeasurer.java         # ICMP-Ping (System-Ping auf Linux/macOS)
│   │   │   ├── DnsMeasurer.java          # DNS-Auflösungs-Messung
│   │   │   ├── HttpMeasurer.java         # HTTP-GET-Messung
│   │   │   └── DnsBenchmark.java         # DNS-Server-Vergleich (Virtual Threads)
│   │   ├── network/                      # Netzwerk-Topologie und -Identität
│   │   │   ├── GatewayDiscovery.java     # Traceroute-basierte Gateway-Kette (nah/fern)
│   │   │   ├── NetworkInfo.java          # IP-Adress-Ermittlung (120s Cache)
│   │   │   └── HostIdentifier.java       # Host-Hash (stabile ID)
│   │   ├── storage/                      # Persistenz + Lese-DTOs
│   │   │   ├── H2MeasurementRepository.java  # Twin-Datenbank-Zugriff (Primary + Shadow)
│   │   │   ├── Statistics.java           # Aggregierte Statistik-DTO
│   │   │   ├── IpChange.java             # Einzelner IP-Wechsel-Datensatz
│   │   │   ├── IpChangeStats.java        # IP-Wechsel-Statistik-DTO
│   │   │   ├── HourlyAverage.java        # Stunden-Mittelwert-DTO (Heatmap)
│   │   │   └── HostInfo.java             # Host-Metadaten-DTO
│   │   ├── report/                       # Berichtswesen
│   │   │   ├── ReliabilityReport.java    # Lückenbewusste Kennzahlen (Verfügbarkeit, Abdeckung, MTBF, MTTR, Ausfälle)
│   │   │   ├── ConnectivityAssessment.java  # „Wer ist schuld“-Verdikt (Router/Gateway/Internet)
│   │   │   └── PdfReportGenerator.java   # PDF-Export (OpenPDF + JFreeChart)
│   │   ├── web/                          # HTTP-Schicht (Javalin)
│   │   │   ├── WebServer.java            # Orchestrator (Javalin-Setup, Gating-Filter, Routen-Registrierung)
│   │   │   ├── SessionManager.java       # Challenge-Response Auth (SHA-256)
│   │   │   ├── ErrorResponse.java        # JSON-Fehlerobjekt
│   │   │   ├── view/                     # HTML-Renderer
│   │   │   │   ├── HtmlPageRenderer.java     # HTML-Rendering Hauptseite
│   │   │   │   ├── SetupPageRenderer.java    # HTML-Rendering Setup-Wizard
│   │   │   │   └── LoginPageRenderer.java    # HTML-Rendering Login-Seite
│   │   │   └── api/                      # Routen-Registrar-Klassen (static register(app, …deps))
│   │   │       ├── PageRoutes.java       # Seiten-Routen (/, Login, Setup)
│   │   │       ├── MeasurementRoutes.java    # Live-Mess- + Statistik-Endpunkte
│   │   │       ├── ReliabilityRoutes.java    # Connectivity + Zuverlässigkeit + Ausfall-Ausschluss
│   │   │       ├── ExportRoutes.java     # PDF-/CSV-Export-Endpunkte
│   │   │       ├── HostRoutes.java       # Host-Info- + IP-Tracking-Endpunkte
│   │   │       ├── DnsRoutes.java        # DNS-Benchmark-Endpunkte
│   │   │       ├── SettingsRoutes.java   # Config-/Theme-/Push-Einstellungs-Endpunkte
│   │   │       ├── SetupRoutes.java      # Setup-Wizard-Endpunkte
│   │   │       └── AuthRoutes.java       # Authentifizierungs-Endpunkte (Nonce/Login/Logout)
│   │   ├── i18n/
│   │   │   └── I18n.java                 # Mehrsprachigkeit (9 Sprachen, erweiterbar)
│   │   └── notification/
│   │       └── PushNotificationService.java  # Browser-Benachrichtigungen
│   ├── test/java/at/mafue/signalreport/  # JUnit-5-Suite, Pakete spiegeln src (12 Klassen, 119 Tests)
│   │   ├── config/        # ConfigTest (17), MaintenanceWindowTest (7)
│   │   ├── measurement/   # MeasurementTest (5), MeasurerInterfaceTest (6)
│   │   ├── network/       # GatewayDiscoveryTest (15), HostIdentifierTest (4)
│   │   ├── storage/       # H2MeasurementRepositoryTest (10), StatisticsTest (8)
│   │   ├── report/        # ReliabilityReportTest (10), ConnectivityAssessmentTest (8)
│   │   ├── web/           # SessionManagerTest (19)
│   │   └── i18n/          # I18nTest (10)
│   └── main/resources/
│       ├── web/                          # Statische Dateien: app.css, app.js, Logos, Favicons, Service Worker
│       ├── lang/                         # Sprachdateien (de, en, fr, it, es, pt, tr, pl, uk)
│       └── fonts/                        # DejaVu-Schriften für PDF (Unicode/Kyrillisch)
├── docs/
│   ├── diagrams/                         # PlantUML-Diagramme (.puml + .png)
│   ├── latex/                            # LaTeX-Dokumentation
│   │   ├── signalreport-dokumentation.tex
│   │   └── kapitel/                      # Einzelne Kapitel
│   ├── Architecture.md / Architecture_de.md       # Architektur-Übersicht (EN/DE)
│   └── ProjectStructure.md / ProjectStructure_de.md  # Projektstruktur (EN/DE, diese Datei)
├── deployment/
│   ├── windows/
│   │   ├── install.bat                   # Windows-Dienst installieren
│   │   └── uninstall.bat                 # Windows-Dienst entfernen
│   ├── macos-linux/
│   │   ├── install.sh                    # Linux/macOS-Dienst installieren
│   │   └── uninstall.sh                  # Linux/macOS-Dienst entfernen
│   └── docker/                           # Docker-Deployment
├── data/                                 # H2-Twin-Datenbank (gitignored)
│   ├── signalreport.mv.db                # Primary-Datenbank
│   ├── signalreport-shadow.mv.db         # Shadow-Datenbank (synchrone Spiegelung)
│   └── quarantine/                       # Defekte DB-Dateien zur Nachanalyse
├── logs/                                 # Anwendungs-Logs
├── config.json                           # Konfiguration (auto-generiert)
├── pom.xml                               # Maven Build-Konfiguration
├── README.md / README_de.md             # Nutzungsanleitung (EN/DE)
└── LICENSE                               # MIT-Lizenz
```

Siehe auch die [Architektur-Übersicht](Architecture_de.md).
