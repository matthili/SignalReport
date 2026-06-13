# SignalReport – Project Structure

> 🌐 **English** | [Deutsch](ProjectStructure_de.md)

```
SignalReport/
├── src/
│   ├── main/java/at/mafue/signalreport/
│   │   ├── SignalReportApp.java          # Main class (entry point)
│   │   ├── Config.java                   # Singleton configuration (JSON)
│   │   ├── I18n.java                     # Internationalisation (9 languages, extensible)
│   │   ├── Measurement.java              # Data object (POJO)
│   │   ├── Measurer.java                 # Interface (strategy pattern)
│   │   ├── PingMeasurer.java             # ICMP ping (system ping on Linux/macOS)
│   │   ├── DnsMeasurer.java              # DNS resolution measurement
│   │   ├── HttpMeasurer.java             # HTTP GET measurement
│   │   ├── DnsBenchmark.java             # DNS server comparison (virtual threads)
│   │   ├── H2MeasurementRepository.java  # Twin-database access (primary + shadow)
│   │   ├── WebServer.java                # Javalin REST API + routing
│   │   ├── HtmlPageRenderer.java         # HTML rendering of the main page
│   │   ├── SetupPageRenderer.java        # HTML rendering of the setup wizard
│   │   ├── LoginPageRenderer.java        # HTML rendering of the login page
│   │   ├── SessionManager.java           # Challenge-response auth (SHA-256)
│   │   ├── PdfReportGenerator.java       # PDF export (OpenPDF + JFreeChart)
│   │   ├── PushNotificationService.java  # Browser notifications
│   │   ├── NetworkInfo.java              # IP address discovery (120s cache)
│   │   └── HostIdentifier.java           # Host hash (stable ID)
│   ├── test/java/at/mafue/signalreport/  # 9 test classes, 86 tests
│   │   ├── MeasurementTest.java          # Unit tests Measurement (5)
│   │   ├── HostIdentifierTest.java       # Unit tests host hash (4)
│   │   ├── StatisticsTest.java           # Integration tests statistics (8)
│   │   ├── ConfigTest.java               # Unit tests configuration (17)
│   │   ├── H2MeasurementRepositoryTest.java  # Integration tests DB (10)
│   │   ├── MeasurerInterfaceTest.java    # Unit tests Measurer interface (6)
│   │   ├── MaintenanceWindowTest.java    # Unit tests maintenance window (7)
│   │   ├── SessionManagerTest.java       # Unit tests auth/sessions (19)
│   │   └── I18nTest.java                 # Unit tests internationalisation (10)
│   └── main/resources/
│       ├── web/                          # Static files (logos, favicons, service worker)
│       ├── lang/                         # Language files (de, en, fr, it, es, pt, tr, pl, uk)
│       └── fonts/                        # DejaVu fonts for the PDF (Unicode/Cyrillic)
├── docs/
│   ├── diagrams/                         # PlantUML diagrams (.puml + .png)
│   ├── latex/                            # LaTeX documentation
│   │   ├── signalreport-dokumentation.tex
│   │   └── kapitel/                      # Individual chapters
│   ├── Architecture.md / Architecture_de.md       # Architecture overview (EN/DE)
│   └── ProjectStructure.md / ProjectStructure_de.md  # Project structure (EN/DE, this file)
├── deployment/
│   ├── windows/
│   │   ├── install.bat                   # Install the Windows service
│   │   └── uninstall.bat                 # Remove the Windows service
│   ├── macos-linux/
│   │   ├── install.sh                    # Install the Linux/macOS service
│   │   └── uninstall.sh                  # Remove the Linux/macOS service
│   └── docker/                           # Docker deployment
├── data/                                 # H2 twin database (gitignored)
│   ├── signalreport.mv.db                # Primary database
│   ├── signalreport-shadow.mv.db         # Shadow database (synchronous mirror)
│   └── quarantine/                       # Corrupt DB files kept for analysis
├── logs/                                 # Application logs
├── config.json                           # Configuration (auto-generated)
├── pom.xml                               # Maven build configuration
├── README.md / README_de.md             # Usage guide (EN/DE)
└── LICENSE                               # MIT license
```

See also the [architecture overview](Architecture.md).
