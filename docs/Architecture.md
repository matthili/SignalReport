# SignalReport – Architektur-Übersicht

```
┌─────────────────────────────────────────┐
│  Java-Backend (Javalin 5.6.3)          │
│  ├── Mess-Engine                        │
│  │   ├── PingMeasurer (implements       │
│  │   ├── DnsMeasurer    Measurer)       │
│  │   ├── HttpMeasurer                   │
│  │   └── DnsBenchmark (Virtual Threads) │
│  ├── H2 Database (embedded)             │
│  ├── WebServer (REST-API + Routing)     │
│  │   ├── HtmlPageRenderer               │
│  │   ├── SetupPageRenderer              │
│  │   └── LoginPageRenderer              │
│  ├── Authentifizierung                  │
│  │   └── SessionManager                 │
│  │       ├── Challenge-Response (SHA-256)│
│  │       ├── Nonce-Verwaltung (60s TTL) │
│  │       └── Sessions (24h Timeout)     │
│  ├── PdfReportGenerator (OpenPDF)       │
│  ├── PushNotificationService            │
│  ├── Config (JSON, Singleton)           │
│  └── NetworkInfo (120s Cache) / HostID  │
└──────────────┬──────────────────────────┘
               │ HTTP (Port 4567)
┌──────────────▼──────────────────────────┐
│  Browser (Chrome/Firefox/Safari/Edge)   │
│  ├── Live-Chart (Chart.js, 5s Updates)  │
│  ├── Statistik-Panel + Heatmap          │
│  ├── DNS-Benchmark-UI                   │
│  ├── Konfigurations-Tabs                │
│  ├── Dark Mode (CSS Custom Properties)  │
│  ├── PDF/CSV-Export                     │
│  └── Web Crypto API (SHA-256 Hashing)   │
└─────────────────────────────────────────┘
```

## Authentifizierung

Die Authentifizierung verwendet ein Challenge-Response-Verfahren mit SHA-256.
Passwörter werden **niemals** im Klartext übertragen:

1. Client fordert eine Einmal-Nonce an (`GET /api/auth/nonce`)
2. Client berechnet `SHA-256(SHA-256(passwort) + nonce)` via Web Crypto API
3. Server verifiziert die Response gegen den gespeicherten Passwort-Hash
4. Bei Erfolg wird ein Session-Token (Cookie `SR_SESSION`) erstellt

## Ping-Messung

Der PingMeasurer verwendet eine plattformspezifische Strategie:

- **Windows**: `InetAddress.isReachable()` – sendet echtes ICMP auch ohne Admin-Rechte, liefert präzise Nachkommastellen
- **Linux/macOS**: System-`ping`-Befehl via `ProcessBuilder` – da `InetAddress.isReachable()` ohne Root auf einen TCP-Fallback (Port 7) zurückfällt

Vollständige Dokumentation: `docs/latex/signalreport-dokumentation.pdf`
