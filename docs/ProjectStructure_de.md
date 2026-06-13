# SignalReport – Projektstruktur

> 🌐 [English](ProjectStructure.md) | **Deutsch**

```
SignalReport/
├── src/
│   ├── main/java/at/mafue/signalreport/
│   │   ├── SignalReportApp.java          # Hauptklasse (Entry Point)
│   │   ├── Config.java                   # Singleton-Konfiguration (JSON)
│   │   ├── I18n.java                     # Mehrsprachigkeit (9 Sprachen, erweiterbar)
│   │   ├── Measurement.java              # Datenobjekt (POJO)
│   │   ├── Measurer.java                 # Interface (Strategy-Pattern)
│   │   ├── PingMeasurer.java             # ICMP-Ping (System-Ping auf Linux/macOS)
│   │   ├── DnsMeasurer.java              # DNS-Auflösungs-Messung
│   │   ├── HttpMeasurer.java             # HTTP-GET-Messung
│   │   ├── DnsBenchmark.java             # DNS-Server-Vergleich (Virtual Threads)
│   │   ├── H2MeasurementRepository.java  # Twin-Datenbank-Zugriff (Primary + Shadow)
│   │   ├── WebServer.java                # Javalin REST-API + Routing
│   │   ├── HtmlPageRenderer.java         # HTML-Rendering Hauptseite
│   │   ├── SetupPageRenderer.java        # HTML-Rendering Setup-Wizard
│   │   ├── LoginPageRenderer.java        # HTML-Rendering Login-Seite
│   │   ├── SessionManager.java           # Challenge-Response Auth (SHA-256)
│   │   ├── PdfReportGenerator.java       # PDF-Export (OpenPDF + JFreeChart)
│   │   ├── PushNotificationService.java  # Browser-Benachrichtigungen
│   │   ├── NetworkInfo.java              # IP-Adress-Ermittlung (120s Cache)
│   │   └── HostIdentifier.java           # Host-Hash (stabile ID)
│   ├── test/java/at/mafue/signalreport/  # 9 Testklassen, 86 Tests
│   │   ├── MeasurementTest.java          # Unit-Tests Measurement (5)
│   │   ├── HostIdentifierTest.java       # Unit-Tests Host-Hash (4)
│   │   ├── StatisticsTest.java           # Integrations-Tests Statistik (8)
│   │   ├── ConfigTest.java               # Unit-Tests Konfiguration (17)
│   │   ├── H2MeasurementRepositoryTest.java  # Integration-Tests DB (10)
│   │   ├── MeasurerInterfaceTest.java    # Unit-Tests Measurer-Interface (6)
│   │   ├── MaintenanceWindowTest.java    # Unit-Tests Wartungsfenster (7)
│   │   ├── SessionManagerTest.java       # Unit-Tests Auth/Sessions (19)
│   │   └── I18nTest.java                 # Unit-Tests Mehrsprachigkeit (10)
│   └── main/resources/
│       ├── web/                          # Statische Dateien (Logos, Favicons, Service Worker)
│       ├── lang/                         # Sprachdateien (de, en, fr, it, es, pt, tr, pl, uk)
│       └── fonts/                        # DejaVu-Schriften für PDF (Unicode/Kyrillisch)
├── docs/
│   ├── diagrams/                         # PlantUML-Diagramme (.puml + .png)
│   ├── latex/                            # LaTeX-Dokumentation
│   │   ├── signalreport-dokumentation.tex
│   │   └── kapitel/                      # Einzelne Kapitel
│   ├── Architecture.md / Architecture_de.md       # Architektur-Kurzübersicht (EN/DE)
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
