# SignalReport – Architecture Overview

> 🌐 **English** | [Deutsch](Architecture_de.md)

```
┌─────────────────────────────────────────────┐
│  Java backend (Javalin 5.6.3)               │
│  ├── SignalReportApp (continuous loop)      │
│  ├── ServiceReachabilityScheduler (6h loop) │
│  ├── measurement/ (engine)                  │
│  │   ├── PingMeasurer (implements           │
│  │   ├── DnsMeasurer    Measurer)           │
│  │   ├── HttpMeasurer                       │
│  │   └── DnsBenchmark (virtual threads)     │
│  ├── network/                               │
│  │   ├── GatewayDiscovery (near/far chain)  │
│  │   ├── NetworkInfo (120s cache)           │
│  │   └── HostIdentifier                     │
│  ├── storage/ H2 twin database (embedded)   │
│  │   ├── Primary  (read + write)            │
│  │   └── Shadow   (synchronous mirror)      │
│  ├── report/                                │
│  │   ├── ReliabilityReport (gap-aware)      │
│  │   ├── ConnectivityAssessment (verdict)   │
│  │   ├── ServiceReachability (block detect) │
│  │   └── PdfReportGenerator (OpenPDF,       │
│  │         DejaVu font embedded)            │
│  ├── web/ WebServer (orchestrator)          │
│  │   ├── setup/auth gating filters          │
│  │   ├── api/ (10 route registrars)         │
│  │   ├── view/ (Html/Setup/Login renderer)  │
│  │   └── SessionManager                     │
│  │       ├── Challenge-response (SHA-256)   │
│  │       ├── Nonce management (60s TTL)     │
│  │       └── Sessions (24h timeout)         │
│  ├── i18n/ I18n (9 languages, extensible)   │
│  ├── notification/ PushNotificationService  │
│  └── config/ Config (JSON, singleton)       │
└──────────────┬──────────────────────────────┘
               │ HTTP (port 4567)
┌──────────────▼──────────────────────────────┐
│  Browser (Chrome/Firefox/Safari/Edge)       │
│  ├── Static assets: /app.css, /app.js       │
│  ├── Live chart (Chart.js, 5s refresh)      │
│  ├── Measurement table (1 row per cycle,    │
│  │     expandable to 5 single values)       │
│  ├── Statistics panel + heatmap             │
│  ├── DNS benchmark UI                        │
│  ├── Configuration tabs                      │
│  ├── Language selector (9 languages)        │
│  ├── Dark mode (CSS custom properties)      │
│  ├── PDF/CSV export                          │
│  └── Web Crypto API (SHA-256 hashing)       │
└─────────────────────────────────────────────┘
```

## Layering

The backend is split into layered packages under `at.mafue.signalreport`:
`config` (settings, one class per aspect), `measurement` (the strategy-based
engine plus the `Measurement` domain model), `network` (topology and host
identity), `storage` (the twin-database repository and its read DTOs), `report`
(reliability metrics, the connectivity verdict, the service-reachability
assessment and the PDF generator), `web`
(the Javalin layer with `view` renderers and `api` route registrars), `i18n`
and `notification`. `SignalReportApp` is the entry point and runs the continuous
measurement loop.

The `web.WebServer` acts as an orchestrator: it sets up Javalin, installs two
`before` filters (setup gating and authentication gating) and then calls the
static `register(app, …deps)` method of each of the ten route registrars in
`web.api` (`PageRoutes`, `MeasurementRoutes`, `ReliabilityRoutes`,
`ExportRoutes`, `HostRoutes`, `DnsRoutes`, `SettingsRoutes`, `SetupRoutes`,
`AuthRoutes`, `ServiceReachabilityRoutes`).

## Authentication

Authentication uses a challenge-response scheme with SHA-256. Passwords are
**never** transmitted in plaintext:

1. The client requests a single-use nonce (`GET /api/auth/nonce`)
2. The client computes `SHA-256(SHA-256(password) + nonce)` via the Web Crypto API
3. The server verifies the response against the stored password hash
4. On success a session token (cookie `SR_SESSION`) is created

## Ping measurement

The PingMeasurer uses a platform-specific strategy:

- **Windows**: `InetAddress.isReachable()` – sends real ICMP even without admin rights and yields precise sub-millisecond values
- **Linux/macOS**: the system `ping` command via `ProcessBuilder` – because without root `InetAddress.isReachable()` falls back to a TCP probe (port 7)

## Outage localisation

The measurement no longer asks only "is the internet up?" but "**who is to
blame?**". `network.GatewayDiscovery` determines the local gateway chain via
traceroute: it walks the RFC 1918 (private) hops and labels the **near** gateway
(the local router) and the **far** gateway (the internet-facing gateway); if the
traceroute yields nothing it falls back to the routing table. Virtual gateways
are handled specially: the Docker default bridge (172.17/16) is skipped, and for
VM/container NAT (10.0.2.x or a detected container) a warning is shown. Per
segment the user can pin a **manual IP**, opt out of continuously pinging the
internet gateway, and choose between keeping the persisted gateway or
re-discovering it when the local IP changes.

`report.ConnectivityAssessment` turns the per-segment results into a verdict that
names the culprit: the **router**, the **internet gateway** or the **internet**.

## Reliability report (gap-aware metrics)

`report.ReliabilityReport` computes metrics that respect measurement gaps:

- **Availability (uptime)** = successful / measured samples – **not** wall-clock time, so pauses never inflate or deflate the figure
- **Coverage** – how much of the period was actually sampled
- **MTBF / MTTR** – mean time between / to repair
- **Aggregated outages** – ≥2 consecutive failed measurements within one
  contiguous run count as **one** outage with start, end, duration and sample
  count; individual outages can be **excluded** from the rating (DB column
  `excluded`)

**Maintenance windows** write a maintenance marker (measurement type
`MAINTENANCE`) for every skipped cycle, so planned gaps are not counted as a
data outage. The default measurement interval is 30 s.

## Service reachability (block / censorship detection)

An optional, **off-by-default** feature checks whether selected online services
(Facebook, Instagram, X, YouTube, WhatsApp, …) are reachable or **blocked** — and
*how*. It runs on its own slow schedule (default every 6 h), separate from the
30 s measurement loop.

- **Layered probe** (`network.ServiceReachabilityProbe`, parallel via virtual
  threads): DNS via the system/ISP resolver **vs.** a public resolver (1.1.1.1),
  TCP connect, a TLS handshake with the real SNI **vs.** a benign control SNI, and
  the HTTP status / block-page check. The first broken layer wins.
- **Pure verdict** (`report.ServiceReachabilityAssessment`, sibling of
  `ConnectivityAssessment`): `REACHABLE` · `SERVICE_DOWN` · `DNS_BLOCKED` ·
  `CONNECTION_BLOCKED` · `SNI_BLOCKED` · `BLOCKPAGE` · `UNKNOWN`.
- **Line-gate**: before each run the scheduler asks the live 30 s monitor "is the
  line up?" (a recent successful PING/HTTP). If not, the run is skipped and a
  single `LINE_DOWN` marker is written — a real outage is never mislabelled as a
  block.
- **Storage & episodes**: results go to the `service_checks` table (twin-DB);
  `report.ServiceReachabilityReport` collapses consecutive same-verdict checks into
  **episodes** ("blocked from 1 Mar to 11 Mar"), shown as tiles + a traffic light
  in the UI and as a timeline in the PDF.
- **`ServiceReachabilityScheduler`** (root package) drives the slow loop and the
  manual "check now" trigger (5-minute cooldown); `web.api.ServiceReachabilityRoutes`
  exposes status, history, settings and check-now.

## Twin database (crash resistance)

Instead of a single H2 file, two are maintained in parallel: a **primary**
(source for all reads) and a **shadow** (synchronous mirror of all writes).
Three protection layers safeguard the data against abrupt process terminations
(Windows update reboot, power failure):

1. **`WRITE_DELAY=0`** – every transaction is flushed to disk immediately (instead of the default 500 ms), shrinking the corruption window to a few microseconds
2. **Twin mirroring** – if one file is destroyed mid-write, the other stays consistent
3. **Auto-recovery on startup** – a DB detected as corrupt (H2 error code 90030) is moved to `data/quarantine/` and reconstructed from the intact DB by a file copy; operation continues without interruption

Existing single-DB installations automatically receive their shadow copy on the
first start – no manual migration step required.

## Internationalisation (i18n)

The entire presentation layer – web interface, login and setup pages, PDF
reports, CSV column headers and user-facing API messages – is multilingual
(9 languages: de, en, fr, it, es, pt, tr, pl, uk).

- **Language files**: flat JSON files (UTF-8) with dotted keys under `resources/lang/`, e.g. `"nav.settings": "Settings"`
- **Three mechanisms**: `I18n.resolve()` replaces `{{key}}` placeholders in the HTML renderers, an embedded `const I18N` object feeds the front-end JavaScript (`I18N['key']` in `app.js`), and `I18n.get()` supplies text for the PDF and the WebServer (with locale-aware number/date formatting)
- **Fallback chain**: selected language → German (reference) → the key name itself; the UI never shows a gap
- **Extensible without recompiling**: an external `./lang` folder next to the JAR is read in addition; files placed there appear in the dropdown automatically
- **PDF Unicode**: the free **DejaVu Sans** font is embedded (`IDENTITY_H`) so that Turkish, Polish and Cyrillic (Ukrainian) are rendered correctly too; the same font feeds the JFreeChart labels

The language is stored globally per installation in `config.json` and can be
changed in the setup wizard as well as in the settings tab.

## Static web assets

The CSS and JavaScript have been extracted from `HtmlPageRenderer` into static
files under `src/main/resources/web/` (`app.css`, `app.js`), which Javalin serves
via `staticFiles` mapped to `/web` (delivered as `/app.css` and `/app.js`). The
renderer keeps only the HTML structure with `{{i18n}}` placeholders plus a small
inline `<script>` carrying server-injected globals (`I18N`, `LOCALE`,
`GW_LABELS`).

---

Full documentation (in German): [`docs/latex/signalreport-dokumentation.pdf`](latex/signalreport-dokumentation.pdf)
Project structure: [`ProjectStructure.md`](ProjectStructure.md)
