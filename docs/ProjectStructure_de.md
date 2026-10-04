# SignalReport – Projektstruktur

> 🌐 [English](ProjectStructure.md) | **Deutsch**

```
SignalReport/
├── src/
│   ├── main/java/at/mafue/signalreport/  # Geschichtete Pakete (siehe unten)
│   │   ├── SignalReportApp.java          # Hauptklasse (Entry Point + kontinuierliche Mess-Schleife + geordneter Stopp)
│   │   ├── StopCommand.java              # "signalreport.jar stop": bittet die laufende Instanz um sauberes Herunterfahren (Loopback-Endpunkt)
│   │   ├── RebuildCommand.java           # "signalreport.jar rebuild-db [--no-swap]": Datenbank-Neuaufbau von der Kommandozeile
│   │   ├── ServiceReachabilityScheduler.java  # Langsamer Dienst-Erreichbarkeits-Lauf + Leitungs-Gate + manueller Auslöser
│   │   ├── DataPrepScheduler.java        # Daten-Aufbereitung (Daemon, Minutentakt): Stundenverdichtung, Rückstand + Aufbewahrung im Fenster, „Jetzt ausführen" mit Abkühlphase
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
│   │   │   ├── ThemeConfig.java          # Design (Dark Mode)
│   │   │   ├── ServiceReachabilityConfig.java  # Dienst-Erreichbarkeits-Einstellungen (aktiv, Intervall, Dienstliste)
│   │   │   ├── ServiceTarget.java        # Ein überwachter Dienst (Domain, Art, aktiv)
│   │   │   └── DataPrepConfig.java       # Einstellungen der Daten-Aufbereitung (Zeitfenster, Option Maintenance-Fenster, Aufbewahrungstage)
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
│   │   │   ├── HostIdentifier.java       # Host-Hash (stabile ID)
│   │   │   ├── ServiceReachabilityProbe.java   # Schicht-Probe (DNS/TCP/TLS-SNI/HTTP), parallel über Virtual Threads
│   │   │   └── ServiceReachabilityResult.java  # Probe-Ergebnis-DTO (Verdikt, Methode, IP, Status, Latenz)
│   │   ├── storage/                      # Persistenz + Lese-DTOs
│   │   │   ├── H2MeasurementRepository.java  # Twin-Datenbank-Zugriff (Primary + Shadow, Lese-Fallback auf die Shadow bei Korruption)
│   │   │   ├── DatabaseRebuilder.java    # Neuaufbau: vereinigt Primary + Shadow in eine frische kompakte Datei, alte in Quarantäne
│   │   │   ├── RebuildReport.java        # Ergebnis des Neuaufbaus (Quellen, unlesbare Bereiche, Größen), als Textbericht geschrieben
│   │   │   ├── RollupService.java        # Stundenverdichtung (measurement_hourly, Wasserstandsmarke) + Aufbewahrungsregel (Funde, Ausfall-Enden, Marker bleiben)
│   │   │   ├── HourlyRollup.java         # Ein Stundenwert (Anzahl, ok/ausgenommen, Min/Ø/Median/P95/Max, Max-Zeitpunkt, Jitter)
│   │   │   ├── RawRow.java               # Schlanker Rohzeilen-Record für die Verdichtung
│   │   │   ├── Statistics.java           # Aggregierte Statistik-DTO
│   │   │   ├── IpChange.java             # Einzelner IP-Wechsel-Datensatz
│   │   │   ├── IpChangeStats.java        # IP-Wechsel-Statistik-DTO
│   │   │   ├── HourlyAverage.java        # Stunden-Mittelwert-DTO (Heatmap)
│   │   │   ├── HostInfo.java             # Host-Metadaten-DTO
│   │   │   └── ServiceCheck.java         # Eine Dienst-Erreichbarkeits-Prüfung (service_checks-Zeile)
│   │   ├── report/                       # Berichtswesen
│   │   │   ├── ReliabilityReport.java    # Lückenbewusste Kennzahlen (Verfügbarkeit, Abdeckung, MTBF, MTTR, Ausfälle)
│   │   │   ├── ConnectivityAssessment.java  # „Wer ist schuld“-Verdikt (Router/Gateway/Internet)
│   │   │   ├── ServiceReachabilityAssessment.java  # Erreichbarkeits-Verdikt (erreichbar/gesperrt/gestört)
│   │   │   ├── ServiceReachabilityReport.java  # Episoden-Verdichtung (Zustandswechsel-Timeline)
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
│   │   │       ├── ExportRoutes.java     # PDF-/CSV-Export-Endpunkte (CSV gestreamt; alle Daten als ZIP; Stundenwerte-CSV)
│   │   │       ├── HostRoutes.java       # Host-Info- + IP-Tracking-Endpunkte
│   │   │       ├── DnsRoutes.java        # DNS-Benchmark-Endpunkte
│   │   │       ├── SettingsRoutes.java   # Config-/Theme-/Push-Einstellungs-Endpunkte
│   │   │       ├── SetupRoutes.java      # Setup-Wizard-Endpunkte
│   │   │       ├── AuthRoutes.java       # Authentifizierungs-Endpunkte (Nonce/Login/Logout)
│   │   │       ├── ServiceReachabilityRoutes.java  # Dienst-Status/-Verlauf/-Einstellungen + Jetzt-prüfen (Cooldown)
│   │   │       ├── DataPrepRoutes.java   # Status der Daten-Aufbereitung + „Jetzt ausführen" (Cooldown)
│   │   │       └── SystemRoutes.java     # Nur per Loopback erreichbarer System-Endpunkt (geordneter Stopp)
│   │   ├── i18n/
│   │   │   └── I18n.java                 # Mehrsprachigkeit (9 Sprachen, erweiterbar)
│   │   └── notification/
│   │       └── PushNotificationService.java  # Browser-Benachrichtigungen
│   ├── test/java/at/mafue/signalreport/  # JUnit-5-Suite, Pakete spiegeln src (29 Klassen, 210 Tests + 4 opt-in Smoke)
│   │   ├── (root)         # ServiceReachabilitySchedulerTest (5, Leitungs-Gate-Logik), StopCommandTest (3, Stopp-Kommando gegen echten Javalin), RebuildCommandTest (1), DataPrepSchedulerTest (5, Einmal-pro-Tag-Entscheidung, voller Lauf + Fortschritt, Abkühlphase des manuellen Starts, keine Blockade während eines Laufs)
│   │   ├── config/        # ConfigTest (18), MaintenanceWindowTest (7), ServiceReachabilityConfigTest (8), DataPrepConfigTest (10, Fenster inkl. Mitternacht, Maintenance-Option, Begrenzung der Aufbewahrung, JSON)
│   │   ├── measurement/   # MeasurementTest (5), MeasurerInterfaceTest (6), HttpMeasurerTest (3, jede Antwort zählt als erreichbar, verweigerte Verbindung schlägt fehl, Fehlergründe)
│   │   ├── network/       # GatewayDiscoveryTest (15), HostIdentifierTest (4), ServiceReachabilityProbeSmokeTest (Netz, opt-in)
│   │   ├── storage/       # H2MeasurementRepositoryTest (13, inkl. Nebenkanal), StatisticsTest (8), ServiceCheckRepositoryTest (3), DatabaseRebuilderTest (4, Vereinigung/Fallback/Tausch/Marker/Stundenwerte), RollupServiceTest (12, Stundenstatistik, Wasserstandsmarke, Fortschritt, Wiederholbarkeit, Aufbewahrungsregeln)
│   │   ├── report/        # ReliabilityReportTest (13), ConnectivityAssessmentTest (8), ServiceReachabilityAssessmentTest (15), ServiceReachabilityReportTest (4), PdfReportSmokeTest (opt-in, 24 h + 12 Monate aus Stundenwerten)
│   │   ├── web/           # SessionManagerTest (19), api/ServiceReachabilityRoutesTest (2), api/SystemRoutesTest (2), api/ExportRoutesTest (7, gestreamtes CSV, ZIP, Stunden-CSV gegen echten Javalin)
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
│   │   ├── install.bat                   # Windows-Dienst installieren oder aktualisieren
│   │   ├── uninstall.bat                 # Windows-Dienst entfernen (Konfiguration/Datenbank optional behalten)
│   │   └── dbrebuild.bat                 # Dienst stoppen, Datenbank neu aufbauen (rebuild-db), Dienst starten
│   ├── macos-linux/
│   │   ├── install.sh                    # Linux/macOS-Dienst installieren oder aktualisieren
│   │   ├── uninstall.sh                  # Linux/macOS-Dienst entfernen (Konfiguration/Datenbank optional behalten)
│   │   └── dbrebuild.sh                  # Dienst stoppen, Datenbank neu aufbauen (rebuild-db), Dienst starten
│   └── docker/                           # Docker-Deployment
├── data/                                 # H2-Twin-Datenbank (gitignored)
│   ├── signalreport.mv.db                # Primary-Datenbank
│   ├── signalreport-shadow.mv.db         # Shadow-Datenbank (Spiegelung aller Schreibvorgänge)
│   ├── signalreport.REBUILD_REQUIRED     # Marker (nur nach einem Korruptions-Lesefehler): Neuaufbau beim nächsten Start
│   └── quarantine/                       # Alte/defekte DB-Dateien zur Nachanalyse (rebuild_<Zeit>/ nach einem Neuaufbau)
├── logs/                                 # Anwendungs-Logs
├── config.json                           # Konfiguration (auto-generiert)
├── pom.xml                               # Maven Build-Konfiguration
├── README.md / README_de.md             # Nutzungsanleitung (EN/DE)
└── LICENSE                               # MIT-Lizenz
```

Siehe auch die [Architektur-Übersicht](Architecture_de.md).
