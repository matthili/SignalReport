# SignalReport – Project Structure

> 🌐 **English** | [Deutsch](ProjectStructure_de.md)

```
SignalReport/
├── src/
│   ├── main/java/at/mafue/signalreport/  # Layered packages (see below)
│   │   ├── SignalReportApp.java          # Main class (entry point + continuous measurement loop + orderly stop)
│   │   ├── StopCommand.java              # "signalreport.jar stop": asks the running instance to shut down cleanly (loopback endpoint)
│   │   ├── RebuildCommand.java           # "signalreport.jar rebuild-db [--no-swap]": database rebuild from the command line
│   │   ├── ServiceReachabilityScheduler.java  # Slow service-reachability loop + line-gate + manual trigger
│   │   ├── DataPrepScheduler.java        # Data preparation (daemon, 1-min tick): hourly condensation, backlog + retention inside the window, "run now" with cooldown
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
│   │   │   ├── ThemeConfig.java          # Theme (dark mode)
│   │   │   ├── ServiceReachabilityConfig.java  # Service-reachability settings (enabled, interval, service list)
│   │   │   ├── ServiceTarget.java        # One monitored service (domain, kind, enabled)
│   │   │   └── DataPrepConfig.java       # Data-preparation settings (time window, maintenance-window option, retention days)
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
│   │   │   ├── HostIdentifier.java       # Host hash (stable ID)
│   │   │   ├── ServiceReachabilityProbe.java   # Layered probe (DNS/TCP/TLS-SNI/HTTP), parallel via virtual threads
│   │   │   └── ServiceReachabilityResult.java  # Probe result DTO (verdict, method, IP, status, latency)
│   │   ├── storage/                      # Persistence + read DTOs
│   │   │   ├── H2MeasurementRepository.java  # Twin-database access (primary + shadow, read fallback to the shadow on corruption)
│   │   │   ├── DatabaseRebuilder.java    # Rebuild: unites primary + shadow into a fresh compact file, quarantines the old ones
│   │   │   ├── RebuildReport.java        # Rebuild result (sources, unreadable ranges, sizes), written as text report
│   │   │   ├── RollupService.java        # Hourly condensation (measurement_hourly, watermark) + retention rule (keeps failures, outage ends, markers)
│   │   │   ├── HourlyRollup.java         # One hourly value (count, ok/excluded, min/avg/median/P95/max, max time, jitter)
│   │   │   ├── RawRow.java               # Slim raw-row record for the condensation
│   │   │   ├── Statistics.java           # Aggregated statistics DTO
│   │   │   ├── IpChange.java             # Single IP change record
│   │   │   ├── IpChangeStats.java        # IP change statistics DTO
│   │   │   ├── HourlyAverage.java        # Hourly average DTO (heatmap)
│   │   │   ├── HostInfo.java             # Host metadata DTO
│   │   │   └── ServiceCheck.java         # One service-reachability check (service_checks row)
│   │   ├── report/                       # Reporting
│   │   │   ├── ReliabilityReport.java    # Gap-aware metrics (uptime, coverage, MTBF, MTTR, outages)
│   │   │   ├── ConnectivityAssessment.java  # "Who is to blame" verdict (router/gateway/internet)
│   │   │   ├── ServiceReachabilityAssessment.java  # Reachability verdict (reachable/blocked/down)
│   │   │   ├── ServiceReachabilityReport.java  # Episode aggregation (state-change timeline)
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
│   │   │       ├── ExportRoutes.java     # PDF/CSV export endpoints (CSV streamed; all data as ZIP; hourly-values CSV)
│   │   │       ├── HostRoutes.java       # Host info + IP-tracking endpoints
│   │   │       ├── DnsRoutes.java        # DNS benchmark endpoints
│   │   │       ├── SettingsRoutes.java   # Config/theme/push settings endpoints
│   │   │       ├── SetupRoutes.java      # Setup-wizard endpoints
│   │   │       ├── AuthRoutes.java       # Authentication endpoints (nonce/login/logout)
│   │   │       ├── ServiceReachabilityRoutes.java  # Service status/history/settings + check-now (cooldown)
│   │   │       ├── DataPrepRoutes.java   # Data-preparation status + "run now" (cooldown)
│   │   │       └── SystemRoutes.java     # Loopback-only system endpoint (orderly stop)
│   │   ├── i18n/
│   │   │   └── I18n.java                 # Internationalisation (9 languages, extensible)
│   │   └── notification/
│   │       └── PushNotificationService.java  # Browser notifications
│   ├── test/java/at/mafue/signalreport/  # JUnit 5 suite, packages mirror src (28 classes, 204 tests + 4 opt-in smoke)
│   │   ├── (root)         # ServiceReachabilitySchedulerTest (5, line-gate logic), StopCommandTest (3, stop command against a real Javalin), RebuildCommandTest (1), DataPrepSchedulerTest (4, once-per-day decision, full run + status, manual run cooldown)
│   │   ├── config/        # ConfigTest (18), MaintenanceWindowTest (7), ServiceReachabilityConfigTest (8), DataPrepConfigTest (10, window incl. midnight, maintenance option, retention clamp, JSON)
│   │   ├── measurement/   # MeasurementTest (5), MeasurerInterfaceTest (6)
│   │   ├── network/       # GatewayDiscoveryTest (15), HostIdentifierTest (4), ServiceReachabilityProbeSmokeTest (network, opt-in)
│   │   ├── storage/       # H2MeasurementRepositoryTest (12), StatisticsTest (8), ServiceCheckRepositoryTest (3), DatabaseRebuilderTest (4, union/fallback/swap/marker/hourly values), RollupServiceTest (11, hourly statistics, watermark, repeatability, retention rules)
│   │   ├── report/        # ReliabilityReportTest (13), ConnectivityAssessmentTest (8), ServiceReachabilityAssessmentTest (15), ServiceReachabilityReportTest (4), PdfReportSmokeTest (opt-in, 24 h + 12 months from hourly values)
│   │   ├── web/           # SessionManagerTest (19), api/ServiceReachabilityRoutesTest (2), api/SystemRoutesTest (2), api/ExportRoutesTest (7, streamed CSV, ZIP, hourly CSV against a real Javalin)
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
│   │   ├── install.bat                   # Install or update the Windows service
│   │   ├── uninstall.bat                 # Remove the Windows service (optionally keep config/database)
│   │   └── dbrebuild.bat                 # Stop service, rebuild the database (rebuild-db), start service
│   ├── macos-linux/
│   │   ├── install.sh                    # Install or update the Linux/macOS service
│   │   ├── uninstall.sh                  # Remove the Linux/macOS service (optionally keep config/database)
│   │   └── dbrebuild.sh                  # Stop service, rebuild the database (rebuild-db), start service
│   └── docker/                           # Docker deployment
├── data/                                 # H2 twin database (gitignored)
│   ├── signalreport.mv.db                # Primary database
│   ├── signalreport-shadow.mv.db         # Shadow database (mirror of all writes)
│   ├── signalreport.REBUILD_REQUIRED     # Marker (only after a corruption read error): rebuild at next start
│   └── quarantine/                       # Old/corrupt DB files kept for analysis (rebuild_<time>/ after a rebuild)
├── logs/                                 # Application logs
├── config.json                           # Configuration (auto-generated)
├── pom.xml                               # Maven build configuration
├── README.md / README_de.md             # Usage guide (EN/DE)
└── LICENSE                               # MIT license
```

See also the [architecture overview](Architecture.md).
