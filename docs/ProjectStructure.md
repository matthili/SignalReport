# SignalReport – Projektstruktur

```
SignalReport/
├── src/
│   ├── main/java/at/mafue/signalreport/
│   │   ├── SignalReportApp.java          # Hauptklasse (Entry Point)
│   │   ├── Config.java                   # Singleton-Konfiguration (JSON)
│   │   ├── Measurement.java              # Datenobjekt (POJO)
│   │   ├── Measurer.java                 # Interface (Strategy-Pattern)
│   │   ├── PingMeasurer.java             # ICMP-Ping-Messung
│   │   ├── DnsMeasurer.java              # DNS-Auflösungs-Messung
│   │   ├── HttpMeasurer.java             # HTTP-GET-Messung
│   │   ├── DnsBenchmark.java             # DNS-Server-Vergleich
│   │   ├── H2MeasurementRepository.java  # Datenbank-Zugriff (H2)
│   │   ├── WebServer.java                # Javalin REST-API + Routing
│   │   ├── HtmlPageRenderer.java         # HTML-Rendering Hauptseite
│   │   ├── SetupPageRenderer.java        # HTML-Rendering Setup-Wizard
│   │   ├── PdfReportGenerator.java       # PDF-Export (OpenPDF + JFreeChart)
│   │   ├── PushNotificationService.java  # Browser-Benachrichtigungen
│   │   ├── NetworkInfo.java              # IP-Adress-Ermittlung
│   │   └── HostIdentifier.java           # Host-Hash (stabile ID)
│   ├── test/java/at/mafue/signalreport/
│   │   ├── MeasurementTest.java          # Unit-Tests Measurement
│   │   ├── HostIdentifierTest.java       # Unit-Tests Host-Hash
│   │   ├── StatisticsTest.java           # Unit-Tests Statistik
│   │   ├── ConfigTest.java               # Unit-Tests Konfiguration
│   │   └── H2MeasurementRepositoryTest.java  # Integration-Tests DB
│   └── main/resources/
│       └── web/                          # Statische Dateien (Favicon, Logo)
├── docs/
│   ├── diagrams/                         # PlantUML-Diagramme (.puml + .png)
│   ├── latex/                            # LaTeX-Dokumentation
│   │   ├── signalreport-dokumentation.tex
│   │   └── kapitel/                      # Einzelne Kapitel
│   ├── Architecture.md                   # Architektur-Kurzübersicht
│   └── ProjectStructure.md              # Diese Datei
├── deployment/
│   ├── windows/
│   │   ├── install.bat                   # Windows-Dienst installieren
│   │   └── uninstall.bat                 # Windows-Dienst entfernen
│   ├── macos-linux/
│   │   ├── install.sh                    # Linux/macOS-Dienst installieren
│   │   └── uninstall.sh                  # Linux/macOS-Dienst entfernen
│   └── docker/                           # Docker-Deployment
├── data/                                 # H2-Datenbank (gitignored)
├── logs/                                 # Anwendungs-Logs
├── config.json                           # Konfiguration (auto-generiert)
├── pom.xml                               # Maven Build-Konfiguration
├── README.md                             # Nutzungsanleitung
└── LICENSE                               # MIT-Lizenz
```
