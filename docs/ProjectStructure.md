# SignalReport – Project Structure

> 🌐 **English** | [Deutsch](ProjectStructure_de.md)

```
SignalReport/
├── src/
│   ├── main/java/at/mafue/signalreport/  # Layered packages (see below)
│   │   ├── SignalReportApp.java          # Main class (entry point + continuous measurement loop)
│   │   ├── config/                       # Configuration (Config + one file per aspect)
│   │   │   ├── Config.java               # Singleton facade (load/save, password hashing, defaults)
│   │   │   ├── MeasurementConfig.java    # Measurement settings (interval, …)
│   │   │   ├── Targets.java              # Ping/DNS/HTTP targets
│   │   │   ├── GatewayConfig.java        # Gateway chain (near/far, manual IP, options)
│   │   │   ├── DatabaseConfig.java       # Twin-database settings
│   │   │   ├── WebserverConfig.java      # Web server settings (port, …)
│   │   │   ├── DnsServer.java            # DNS server entry (benchmark)
│   │   │   ├── MaintenanceWindow.java    # Scheduled maintenance window
│   │   │   ├── UserInfo.java             # User/account data
│   │   │   ├── AuthConfig.java           # Authentication settings
│   │   │   ├── PushConfig.java           # Push notification settings
│   │   │   ├── SetupConfig.java          # Setup-wizard state
│   │   │   └── ThemeConfig.java          # Theme (dark mode)
│   │   ├── measurement/                  # Measurement engine (strategy pattern)
│   │   │   ├── Measurer.java             # Interface (strategy pattern)
│   │   │   ├── Measurement.java          # Domain model (one cycle / single value)
│   │   │   ├── PingMeasurer.java         # ICMP ping (system ping on Linux/macOS)
│   │   │   ├── DnsMeasurer.java          # DNS resolution measurement
│   │   │   ├── HttpMeasurer.java         # HTTP GET measurement
│   │   │   └── DnsBenchmark.java         # DNS server comparison (virtual threads)
│   │   ├── network/                      # Network topology and identity
│   │   │   ├── GatewayDiscovery.java     # Traceroute-based gateway chain (near/far)
│   │   │   ├── NetworkInfo.java          # IP address discovery (120s cache)
│   │   │   └── HostIdentifier.java       # Host hash (stable ID)
│   │   ├── storage/                      # Persistence + read DTOs
│   │   │   ├── H2MeasurementRepository.java  # Twin-database access (primary + shadow)
│   │   │   ├── Statistics.java           # Aggregated statistics DTO
│   │   │   ├── IpChange.java             # Single IP change record
│   │   │   ├── IpChangeStats.java        # IP change statistics DTO
│   │   │   ├── HourlyAverage.java        # Hourly average DTO (heatmap)
│   │   │   └── HostInfo.java             # Host metadata DTO
│   │   ├── report/                       # Reporting
│   │   │   ├── ReliabilityReport.java    # Gap-aware metrics (uptime, coverage, MTBF, MTTR, outages)
│   │   │   ├── ConnectivityAssessment.java  # "Who is to blame" verdict (router/gateway/internet)
│   │   │   └── PdfReportGenerator.java   # PDF export (OpenPDF + JFreeChart)
│   │   ├── web/                          # HTTP layer (Javalin)
│   │   │   ├── WebServer.java            # Orchestrator (Javalin setup, gating filters, route registration)
│   │   │   ├── SessionManager.java       # Challenge-response auth (SHA-256)
│   │   │   ├── ErrorResponse.java        # JSON error payload
│   │   │   ├── view/                     # HTML renderers
│   │   │   │   ├── HtmlPageRenderer.java     # HTML rendering of the main page
│   │   │   │   ├── SetupPageRenderer.java    # HTML rendering of the setup wizard
│   │   │   │   └── LoginPageRenderer.java    # HTML rendering of the login page
│   │   │   └── api/                      # Route registrars (static register(app, …deps))
│   │   │       ├── PageRoutes.java       # Page routes (/, login, setup)
│   │   │       ├── MeasurementRoutes.java    # Live measurement + statistics endpoints
│   │   │       ├── ReliabilityRoutes.java    # Connectivity + reliability + outage exclusion
│   │   │       ├── ExportRoutes.java     # PDF/CSV export endpoints
│   │   │       ├── HostRoutes.java       # Host info + IP-tracking endpoints
│   │   │       ├── DnsRoutes.java        # DNS benchmark endpoints
│   │   │       ├── SettingsRoutes.java   # Config/theme/push settings endpoints
│   │   │       ├── SetupRoutes.java      # Setup-wizard endpoints
│   │   │       └── AuthRoutes.java       # Authentication endpoints (nonce/login/logout)
│   │   ├── i18n/
│   │   │   └── I18n.java                 # Internationalisation (9 languages, extensible)
│   │   └── notification/
│   │       └── PushNotificationService.java  # Browser notifications
│   ├── test/java/at/mafue/signalreport/  # JUnit 5 suite, packages mirror src (12 classes, 119 tests)
│   │   ├── config/        # ConfigTest (17), MaintenanceWindowTest (7)
│   │   ├── measurement/   # MeasurementTest (5), MeasurerInterfaceTest (6)
│   │   ├── network/       # GatewayDiscoveryTest (15), HostIdentifierTest (4)
│   │   ├── storage/       # H2MeasurementRepositoryTest (10), StatisticsTest (8)
│   │   ├── report/        # ReliabilityReportTest (10), ConnectivityAssessmentTest (8)
│   │   ├── web/           # SessionManagerTest (19)
│   │   └── i18n/          # I18nTest (10)
│   └── main/resources/
│       ├── web/                          # Static files: app.css, app.js, logos, favicons, service worker
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
