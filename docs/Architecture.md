# SignalReport – Architektur-Übersicht

```
┌─────────────────────────────────────────┐
│  Java-Backend (Javalin 5.6.3)          │
│  ├── Mess-Engine                        │
│  │   ├── PingMeasurer (implements       │
│  │   ├── DnsMeasurer    Measurer)       │
│  │   ├── HttpMeasurer                   │
│  │   └── DnsBenchmark                   │
│  ├── H2 Database (embedded)             │
│  ├── WebServer (REST-API + Routing)     │
│  │   ├── HtmlPageRenderer               │
│  │   └── SetupPageRenderer              │
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
│  └── PDF/CSV-Export                     │
└─────────────────────────────────────────┘
```

Vollständige Dokumentation: `docs/latex/signalreport-dokumentation.pdf`
