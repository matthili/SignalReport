<p align="center">
  <img src="src/main/resources/web/logo_mit_schriftzug_dark_git.png" alt="SignalReport" width="500">
</p>

<p align="center">
  <a href="https://openjdk.org/"><img src="https://img.shields.io/badge/Java-21+-007396?logo=java" alt="Java 21+"></a>
  <a href="https://opensource.org/licenses/MIT"><img src="https://img.shields.io/badge/License-MIT-green.svg" alt="License: MIT"></a>
  <a href="https://junit.org/"><img src="https://img.shields.io/badge/Tests-156%20passing-brightgreen" alt="JUnit Tests"></a>
  <img src="https://img.shields.io/badge/version-2.0-blue" alt="Version 2.0">
</p>

<p align="center">
  <b>English</b> | <a href="README_de.md">Deutsch</a>
</p>

A professional, open-source monitoring tool for the continuous supervision of your internet quality – with PDF reports for ISP complaints, IP tracking and a DNS benchmark.

> 💡 **Why SignalReport?**  
> *"My internet is slow!"* is not enough for an ISP. With SignalReport you deliver **verifiable, quantified evidence** – not just a gut feeling.

> 📜 **Project history**  
> SignalReport was created between January and April 2026 as the final project of the diploma course *"Software Developer"* at WIFI Vienna (course 18195015) and has since been actively developed further as a personal open-source project. If you want to review the exact state at the time of the diploma examination, you can find it as release [`V1`](https://github.com/matthili/SignalReport/releases/tag/V1).

---

## 🌟 Features

| Category | Functions |
|----------|-----------|
| **Monitoring** | 🔁 Continuous measurement (ping/DNS/HTTP + gateways)<br>⏱️ Configurable interval (5s–1h, default 30s)<br>⏸️ Maintenance window (router updates)<br>🌐 IP tracking (detect external IP changes) |
| **Fault localisation & reliability** | 🛰️ Pinpoint router vs. internet gateway vs. ISP (traceroute gateway chain)<br>🐳 Virtual-gateway detection in VM/Docker<br>📈 Availability, coverage, MTBF & MTTR (gap-aware)<br>📉 Aggregated connection outages, individually excludable from the rating |
| **Service reachability** | 🚫 Detects whether services (Facebook, Instagram, X, YouTube, WhatsApp, …) are reachable or **blocked** — distinguishing DNS / TCP / SNI / block-page filtering<br>🕒 Separate slow schedule (default 6 h), line-gated, **off by default**<br>📅 Per-service block/outage timeline in the PDF report |
| **Visualisation** | 📊 Live charts with Chart.js<br>📋 Per-cycle measurement table (collapsible)<br>🌡️ Hourly heatmap<br>🖥️ Web interface (responsive)<br>🔔 Browser push on outages / high latency |
| **Reports** | 📄 PDF export (24h / 7 days / 12 months)<br>📈 3 charts (PING/DNS/HTTP) with target-change markers<br>🏆 Top 10 worst measurements<br>⚠️ Connection-outage analysis<br>📤 CSV export (complete or filtered) |
| **Security** | 🔐 Setup wizard (web-based, no CLI)<br>🔑 Challenge-response authentication (SHA-256)<br>👥 Admin/user roles with session management<br>🛡️ Password is never transmitted in plaintext |
| **Data safety** | 🛟 Twin database (synchronous mirroring)<br>🔄 Auto-recovery on startup (corruption → reconstruction from the intact copy)<br>⚡ Synchronous writes (`WRITE_DELAY=0`)<br>🛡️ Protection against crashes from update reboots / power failures |
| **Internationalisation** | 🌐 9 languages: Deutsch, English, Français, Italiano, Español, Português, Türkçe, Polski, Українська<br>🔤 Applies to web UI, PDF reports and CSV exports<br>🎛️ Language choice in the setup wizard and in the settings<br>📂 Extensible without recompiling: drop your own language file into `./lang/` |
| **Configuration** | ⚙️ Dynamic measurement targets (ping/DNS/HTTP)<br>🌍 DNS benchmark (servers worldwide)<br>👤 User info (provider/customer number for reports) |

---

## 🚀 Quick start

### Requirements
- Java 21 or higher ([download](https://adoptium.net/))
- (Optional) Maven to build from source

### Installation & start
```bash
# 1. Download the JAR (or build with Maven: mvn clean package)
java -jar signalreport.jar

# 2. Open the browser
http://localhost:4567

# 3. Run through the setup wizard (set an admin password)
```

✅ **Done!** Measurement starts automatically – by default ping, DNS and HTTP (and the gateways, if set) are tested every 30 seconds.

---

## 📦 Installation as a service

For continuous operation (even without a logged-in user) SignalReport can be installed as a background service.

### Windows
1. Download and unpack [`signalreport_windows.zip`](deployment/signalreport_windows.zip)
2. Right-click `install.bat` → *"Run as administrator"*
3. The script installs the service, creates a desktop shortcut and sets up the firewall

### macOS / Linux
1. Download and unpack [`signalreport_mac-linux.zip`](deployment/signalreport_mac-linux.zip)
2. Open a terminal and run: `sudo bash install.sh`
3. The service is set up as a systemd service (Linux) or launchd service (macOS)

> 💡 The [`signalreport.jar`](deployment/signalreport.jar) must be located in the same directory as the installation scripts.

### Uninstallation
- **Windows**: right-click `uninstall.bat` → run as administrator
- **macOS/Linux**: `sudo bash uninstall.sh` (in the terminal)

---

## 📸 Screenshots

| Web interface | PDF report (excerpt) | DNS benchmark |
|---------------|----------------------|---------------|
| ![Dashboard](docs/screenshots/dashboard.png) | ![PDF Report](docs/screenshots/pdf-report.png) | ![DNS Benchmark](docs/screenshots/dns-benchmark.png) |
| *Live charts, statistics, settings* | *Professional report for ISPs* | *Comparison of global DNS servers* |

---

## ⚙️ Configuration

After the first start a `config.json` is created. Important settings:

```json
{
  "language": "en",
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

💡 **Tip**: Changes made via the web interface (tab *⚙️ Settings*) are applied and persisted immediately!

### 🛰️ Fault localization & virtual gateways

For **fault localization** (your own router vs. provider), SignalReport runs a traceroute at startup to find the nearest router and the gateway to the internet. When SignalReport runs inside a **VM or container**, the detected "router" may be a virtual NAT device rather than your real hardware:

- The **Docker default bridge** (`172.17.0.0/16`) is skipped automatically when the real router is visible as the next hop behind it.
- **VirtualBox/QEMU NAT** (`10.0.2.0/24`) and Docker bridges inside a container are recognised as virtual → a **warning** appears in the *⚙️ Settings* tab under *Gateways*.
- **VMware NAT** (`192.168.x.2`) and similar NATs in real home-network ranges cannot be told apart from a genuine router by IP, so they are deliberately **not** flagged automatically (to avoid false alarms).

**Fix** in such cases: in the *⚙️ Settings* tab under *Gateways*, set the real router IP **manually**, or enable *"do not ping continuously"* for the internet gateway. Manually set IPs can optionally be kept across an IP change.

---

## 🌐 Internationalisation

SignalReport is fully multilingual – web interface, PDF reports and CSV exports. **9 languages** are bundled:

🇩🇪 Deutsch · 🇬🇧 English · 🇫🇷 Français · 🇮🇹 Italiano · 🇪🇸 Español · 🇵🇹 Português · 🇹🇷 Türkçe · 🇵🇱 Polski · 🇺🇦 Українська

- **Switching**: in the setup wizard (before setting the password) or any time in the *⚙️ Settings* tab
- **Default**: fresh installations adopt the system language (otherwise English); existing installations stay on German
- **Add your own language** (without recompiling): place a `<code>.json` file (e.g. `nl.json`) modelled on [`de.json`](src/main/resources/lang/de.json) into a `lang/` folder next to `signalreport.jar` – it appears in the language dropdown automatically
- **Unicode in the PDF**: the DejaVu font is embedded, so Turkish, Polish and Cyrillic script (Ukrainian) are rendered correctly too

---

## 📊 PDF report – perfect for ISP complaints

The 12-month report contains:
- ✅ Your customer data (name, provider, customer number)
- ✅ Host information (hostname, hash, IPs)
- ✅ 3 charts with red lines at target changes
- ✅ Chronological list of the measured targets
- ✅ Top 10 worst measurements (with timestamp)
- ✅ Top 10 longest connection outages
- ✅ Total number of outages

> 📌 **How to use the report**:  
> 1. Generate the PDF via "📄 PDF report (12 months)"  
> 2. Save it as `signalreport-providername-complaint-2026-03-25.pdf`  
> 3. Attach it to a support email with text such as:  
> *"Attached is the technical evidence of repeated connection problems in the period XX to YY. Please check the line to my connection."*

---

## 🏗️ Project structure

A compact overview of the directories and classes is available in [`docs/ProjectStructure.md`](docs/ProjectStructure.md); the architecture is described in [`docs/Architecture.md`](docs/Architecture.md).

```
signalreport/
├── src/main/java/at/mafue/signalreport/
│   ├── SignalReportApp.java              # Main class (entry point)
│   ├── ServiceReachabilityScheduler.java # Slow service-reachability loop + line-gate
│   ├── config/                           # Slim Config + one file per area (Measurement, Gateway, ServiceReachability, ServiceTarget, …)
│   ├── measurement/                      # Measurer interface + Ping/Dns/Http, Measurement, DnsBenchmark
│   ├── network/                          # GatewayDiscovery (traceroute), ServiceReachabilityProbe, NetworkInfo, HostIdentifier
│   ├── storage/                          # H2MeasurementRepository (twin DB) + DTOs (Statistics, ServiceCheck, IpChange, …)
│   ├── report/                           # ReliabilityReport, ConnectivityAssessment, ServiceReachabilityAssessment/Report, PdfReportGenerator
│   ├── web/                              # WebServer (Javalin orchestrator), SessionManager
│   │   ├── view/                         #   HtmlPageRenderer, SetupPageRenderer, LoginPageRenderer
│   │   └── api/                          #   10 route registrars (Measurement, Reliability, ServiceReachability, Settings, …)
│   ├── i18n/                             # I18n (9 languages, extensible)
│   └── notification/                     # PushNotificationService
├── src/test/java/at/mafue/signalreport/  # 20 test classes, 156 tests (mirror the packages above)
├── src/main/resources/web/               # Static assets: app.css, app.js, logos, favicons
├── src/main/resources/lang/              # Language files (de, en, fr, it, es, pt, tr, pl, uk)
├── src/main/resources/fonts/             # DejaVu fonts for the PDF (Unicode/Cyrillic)
├── docs/
│   ├── diagrams/                         # PlantUML diagrams (.puml + .png)
│   ├── latex/                            # Full LaTeX documentation
│   └── screenshots/                      # UI screenshots
├── deployment/                           # Installation scripts (Win/Linux/macOS)
├── config.json                           # Auto-generated configuration
├── data/                                 # H2 twin database: primary + shadow (gitignored)
│   └── quarantine/                       # Corrupt DB files kept for analysis
├── pom.xml                               # Maven build configuration
└── README.md                             # This file (German version: README_de.md)
```

---

## 📚 Documentation

- **Full LaTeX documentation as PDF**: [`signalreport-dokumentation.pdf`](docs/latex/signalreport-dokumentation.pdf)
  Contains UML diagrams, architecture description, implementation details and the test report. *(Document language: German.)*
- **Architecture overview**: [`docs/Architecture.md`](docs/Architecture.md)
- **Project structure**: [`docs/ProjectStructure.md`](docs/ProjectStructure.md)
- **UML diagrams**: all 7 diagrams as PNG and PlantUML in `docs/diagrams/`:
  - [`class-diagram.png`](docs/diagrams/class-diagram.png) – class structure
  - [`component-diagram.png`](docs/diagrams/component-diagram.png) – component overview
  - [`sequence-measurement.png`](docs/diagrams/sequence-measurement.png) – measurement flow
  - [`sequence-pdf.png`](docs/diagrams/sequence-pdf.png) – PDF export process
  - [`usecase-diagram.png`](docs/diagrams/usecase-diagram.png) – use cases
  - [`deployment-diagram.png`](docs/diagrams/deployment-diagram.png) – deployment scenarios
  - [`state-diagram-auth.png`](docs/diagrams/state-diagram-auth.png) – authentication states

---

## 🔒 Security notes

- **Challenge-response auth**: passwords are never transmitted in plaintext – SHA-256 with single-use nonces
- **Session management**: 24h session timeout, secure cookie-based authentication
- **Authentication**: for public IPs (e.g. a NAS with port forwarding) **make sure to enable it** (tab *🔐 Security*)
- **Setup password**: the admin password is set on first start – never leave default values!
- **Database**: all data stored locally in a crash-resistant twin database (primary + shadow). No cloud dependency, no external APIs (except ipify.org for the external IP)

---

## 🤝 Contributing

SignalReport is open source! You can:
- 🐛 Report bugs (issues)
- 💡 Suggest new features
- 📝 Improve the documentation
- 🌍 Contribute translations (a new `lang/<code>.json` modelled on [`de.json`](src/main/resources/lang/de.json))

---

## 📜 License

MIT License – see the [LICENSE](LICENSE) file.  
*Free to use for private and commercial purposes – with attribution.*

---

## 🙏 Acknowledgements

This project uses great open-source libraries and services:

**Backend libraries (Maven/Java):**
- [Javalin](https://javalin.io/) – lightweight web framework
- [H2 Database](https://www.h2database.com/) – embedded SQL database
- [Jackson](https://github.com/FasterXML/jackson) – JSON serialisation (config, API)
- [OpenPDF](https://github.com/LibrePDF/OpenPDF) – PDF creation
- [JFreeChart](https://www.jfree.org/jfreechart/) – chart generation for PDF reports
- [dnsjava](https://dnsjava.org/) – DNS queries for the benchmark
- [SLF4J](https://www.slf4j.org/) + [Logback](https://logback.qos.ch/) – logging
- [JUnit 5](https://junit.org/junit5/) – test framework

**Frontend (browser):**
- [Chart.js](https://www.chartjs.org/) – live charts in the web interface (via CDN)
- [Web Crypto API](https://developer.mozilla.org/en-US/docs/Web/API/Web_Crypto_API) – client-side SHA-256 hashing for challenge-response auth

**Fonts:**
- [DejaVu Fonts](https://dejavu-fonts.github.io/) – embedded Unicode font for PDF reports (Latin, Latin Extended, Cyrillic)

**External services:**
- [ipify.org](https://www.ipify.org/) – discovery of the external IP address

**Build & deployment:**
- [Apache Maven](https://maven.apache.org/) – build system
- [Apache Commons Daemon](https://commons.apache.org/proper/commons-daemon/) – service installation (procrun/jsvc)

**Documentation & development:**
- [PlantUML](https://plantuml.com/) – UML diagrams
- [LaTeX](https://www.latex-project.org/) – project documentation
- [EmojiTerra](https://emojiterra.com/) – emoji reference
- [StackOverflow](https://stackoverflow.com/) – Q&A platform for software developers
- [Hitchhiker’s Guide to PlantUML](https://crashedmind.github.io/PlantUMLHitchhikersGuide/) – nomen est omen

My thanks also go to:

- [Christian Schäfer](https://github.com/chris-cgsit/) – Java course and project coaching
- [Serap Kadam](https://github.com/serap) – support, friendship, everything around Java
- [Angelika Winder] – support in all situations of life (except with Java)


---

## 📮 Contact & support

For questions about the project or technical details, feel free to open an issue on GitHub.


---

> 🌐 **SignalReport**  
> *Built with ❤️ for everyone who wants to know how stable their internet connection really is.*
