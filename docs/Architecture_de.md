# SignalReport – Architektur-Übersicht

> 🌐 [English](Architecture.md) | **Deutsch**

```
┌─────────────────────────────────────────┐
│  Java-Backend (Javalin 5.6.3)           │
│  ├── Mess-Engine                        │
│  │   ├── PingMeasurer (implements       │
│  │   ├── DnsMeasurer    Measurer)       │
│  │   ├── HttpMeasurer                   │
│  │   └── DnsBenchmark (Virtual Threads) │
│  ├── H2 Twin-Datenbank (embedded)       │
│  │   ├── Primary  (Lesen + Schreiben)   │
│  │   └── Shadow   (synchrone Spiegelung)│
│  ├── WebServer (REST-API + Routing)     │
│  │   ├── HtmlPageRenderer               │
│  │   ├── SetupPageRenderer              │
│  │   └── LoginPageRenderer              │
│  ├── I18n (9 Sprachen, erweiterbar)     │
│  ├── Authentifizierung                  │
│  │   └── SessionManager                 │
│  │       ├── Challenge-Response (SHA-256)│
│  │       ├── Nonce-Verwaltung (60s TTL) │
│  │       └── Sessions (24h Timeout)     │
│  ├── PdfReportGenerator (OpenPDF,       │
│  │     DejaVu-Schrift eingebettet)      │
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
│  ├── Sprachauswahl (9 Sprachen)         │
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

## Twin-Datenbank (Crash-Resistenz)

Statt einer einzelnen H2-Datei werden zwei parallel geführt: eine **Primary**
(Quelle für alle Lesezugriffe) und eine **Shadow** (synchrone Spiegelung aller
Schreibvorgänge). Drei Schutz-Stufen sichern die Daten gegen abrupte
Prozess-Terminierungen (Windows-Update-Neustart, Stromausfall):

1. **`WRITE_DELAY=0`** – jede Transaktion wird sofort auf die Platte geschrieben (statt Default 500 ms), wodurch das Korruptions-Fenster auf wenige Mikrosekunden schrumpft
2. **Twin-Spiegelung** – wird eine Datei mitten im Schreibvorgang zerstört, bleibt die andere konsistent
3. **Auto-Recovery beim Start** – eine als korrupt erkannte DB (H2-Fehlercode 90030) wird nach `data/quarantine/` verschoben und per Datei-Kopie aus der intakten DB rekonstruiert; der Betrieb läuft unterbrechungsfrei weiter

Bestehende Single-DB-Installationen erhalten beim ersten Start automatisch ihre
Shadow-Kopie – kein manueller Migrationsschritt nötig.

## Internationalisierung (i18n)

Die gesamte Präsentationsschicht – Web-Oberfläche, Login- und Setup-Seite,
PDF-Berichte, CSV-Spaltenköpfe und benutzerseitige API-Meldungen – ist
mehrsprachig (9 Sprachen: de, en, fr, it, es, pt, tr, pl, uk).

- **Sprachdateien**: flache JSON-Dateien (UTF-8) mit Punkt-Schlüsseln unter `resources/lang/`, z. B. `"nav.settings": "Einstellungen"`
- **Drei Mechanismen**: `I18n.resolve()` ersetzt `{{key}}`-Platzhalter in den HTML-Renderern, ein eingebettetes `const I18N`-Objekt versorgt das Frontend-JavaScript, und `I18n.get()` liefert Texte für PDF und WebServer (mit locale-bewusster Zahlen-/Datumsformatierung)
- **Fallback-Kette**: gewählte Sprache → Deutsch (Referenz) → Schlüsselname; die UI zeigt nie ein Loch
- **Erweiterbar ohne Neukompilieren**: ein externer Ordner `./lang` neben der JAR wird zusätzlich eingelesen; dort abgelegte Dateien erscheinen automatisch im Dropdown
- **PDF-Unicode**: die freie Schrift **DejaVu Sans** ist eingebettet (`IDENTITY_H`), damit auch Türkisch, Polnisch und Kyrillisch (Ukrainisch) korrekt dargestellt werden; dieselbe Schrift speist die JFreeChart-Beschriftungen

Die Sprache wird global pro Installation in `config.json` gespeichert und ist im
Setup-Wizard sowie im Einstellungen-Tab umstellbar.

---

Vollständige Dokumentation: [`docs/latex/signalreport-dokumentation.pdf`](latex/signalreport-dokumentation.pdf)
Projektstruktur: [`ProjectStructure_de.md`](ProjectStructure_de.md)
