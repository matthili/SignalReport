# SignalReport – Architecture Overview

> 🌐 **English** | [Deutsch](Architecture_de.md)

```
┌─────────────────────────────────────────┐
│  Java backend (Javalin 5.6.3)           │
│  ├── Measurement engine                 │
│  │   ├── PingMeasurer (implements       │
│  │   ├── DnsMeasurer    Measurer)       │
│  │   ├── HttpMeasurer                   │
│  │   └── DnsBenchmark (virtual threads) │
│  ├── H2 twin database (embedded)        │
│  │   ├── Primary  (read + write)        │
│  │   └── Shadow   (synchronous mirror)  │
│  ├── WebServer (REST API + routing)     │
│  │   ├── HtmlPageRenderer               │
│  │   ├── SetupPageRenderer              │
│  │   └── LoginPageRenderer              │
│  ├── I18n (9 languages, extensible)     │
│  ├── Authentication                     │
│  │   └── SessionManager                 │
│  │       ├── Challenge-response (SHA-256)│
│  │       ├── Nonce management (60s TTL) │
│  │       └── Sessions (24h timeout)     │
│  ├── PdfReportGenerator (OpenPDF,       │
│  │     DejaVu font embedded)            │
│  ├── PushNotificationService            │
│  ├── Config (JSON, singleton)           │
│  └── NetworkInfo (120s cache) / HostID  │
└──────────────┬──────────────────────────┘
               │ HTTP (port 4567)
┌──────────────▼──────────────────────────┐
│  Browser (Chrome/Firefox/Safari/Edge)   │
│  ├── Live chart (Chart.js, 5s updates)  │
│  ├── Statistics panel + heatmap         │
│  ├── DNS benchmark UI                   │
│  ├── Configuration tabs                 │
│  ├── Language selector (9 languages)    │
│  ├── Dark mode (CSS custom properties)  │
│  ├── PDF/CSV export                     │
│  └── Web Crypto API (SHA-256 hashing)   │
└─────────────────────────────────────────┘
```

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
- **Three mechanisms**: `I18n.resolve()` replaces `{{key}}` placeholders in the HTML renderers, an embedded `const I18N` object feeds the front-end JavaScript, and `I18n.get()` supplies text for the PDF and the WebServer (with locale-aware number/date formatting)
- **Fallback chain**: selected language → German (reference) → the key name itself; the UI never shows a gap
- **Extensible without recompiling**: an external `./lang` folder next to the JAR is read in addition; files placed there appear in the dropdown automatically
- **PDF Unicode**: the free **DejaVu Sans** font is embedded (`IDENTITY_H`) so that Turkish, Polish and Cyrillic (Ukrainian) are rendered correctly too; the same font feeds the JFreeChart labels

The language is stored globally per installation in `config.json` and can be
changed in the setup wizard as well as in the settings tab.

---

Full documentation (in German): [`docs/latex/signalreport-dokumentation.pdf`](latex/signalreport-dokumentation.pdf)
Project structure: [`ProjectStructure.md`](ProjectStructure.md)
