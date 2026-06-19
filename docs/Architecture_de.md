# SignalReport – Architektur-Übersicht

> 🌐 [English](Architecture.md) | **Deutsch**

```
┌─────────────────────────────────────────────┐
│  Java-Backend (Javalin 5.6.3)               │
│  ├── SignalReportApp (kontinuierliche Schleife)│
│  ├── ServiceReachabilityScheduler (6h-Lauf) │
│  ├── measurement/ (Engine)                  │
│  │   ├── PingMeasurer (implements           │
│  │   ├── DnsMeasurer    Measurer)           │
│  │   ├── HttpMeasurer                       │
│  │   └── DnsBenchmark (Virtual Threads)     │
│  ├── network/                               │
│  │   ├── GatewayDiscovery (Kette nah/fern)  │
│  │   ├── NetworkInfo (120s Cache)           │
│  │   └── HostIdentifier                     │
│  ├── storage/ H2 Twin-Datenbank (embedded)  │
│  │   ├── Primary  (Lesen + Schreiben)       │
│  │   └── Shadow   (synchrone Spiegelung)    │
│  ├── report/                                │
│  │   ├── ReliabilityReport (lückenbewusst)  │
│  │   ├── ConnectivityAssessment (Verdikt)   │
│  │   ├── ServiceReachability (Sperr-Check)  │
│  │   └── PdfReportGenerator (OpenPDF,       │
│  │         DejaVu-Schrift eingebettet)      │
│  ├── web/ WebServer (Orchestrator)          │
│  │   ├── Setup-/Auth-Gating-Filter          │
│  │   ├── api/ (10 Routen-Registrare)        │
│  │   ├── view/ (Html/Setup/Login-Renderer)  │
│  │   └── SessionManager                     │
│  │       ├── Challenge-Response (SHA-256)   │
│  │       ├── Nonce-Verwaltung (60s TTL)     │
│  │       └── Sessions (24h Timeout)         │
│  ├── i18n/ I18n (9 Sprachen, erweiterbar)   │
│  ├── notification/ PushNotificationService  │
│  └── config/ Config (JSON, Singleton)       │
└──────────────┬──────────────────────────────┘
               │ HTTP (Port 4567)
┌──────────────▼──────────────────────────────┐
│  Browser (Chrome/Firefox/Safari/Edge)       │
│  ├── Statische Assets: /app.css, /app.js    │
│  ├── Live-Chart (Chart.js, 5s Updates)      │
│  ├── Mess-Tabelle (1 Zeile pro Zyklus,      │
│  │     aufklappbar zu 5 Einzelwerten)       │
│  ├── Statistik-Panel + Heatmap              │
│  ├── DNS-Benchmark-UI                       │
│  ├── Konfigurations-Tabs                    │
│  ├── Sprachauswahl (9 Sprachen)             │
│  ├── Dark Mode (CSS Custom Properties)      │
│  ├── PDF/CSV-Export                         │
│  └── Web Crypto API (SHA-256 Hashing)       │
└─────────────────────────────────────────────┘
```

## Schichtung

Das Backend ist in geschichtete Pakete unter `at.mafue.signalreport` aufgeteilt:
`config` (Einstellungen, je eine Klasse pro Aspekt), `measurement` (die
Strategy-basierte Engine samt `Measurement`-Domänenmodell), `network`
(Topologie und Host-Identität), `storage` (das Twin-Datenbank-Repository und
seine Lese-DTOs), `report` (Zuverlässigkeits-Kennzahlen, das Schuld-Verdikt, die
Dienst-Erreichbarkeits-Bewertung und der PDF-Generator), `web` (die
Javalin-Schicht mit `view`-Renderern und
`api`-Routen-Registraren), `i18n` und `notification`. `SignalReportApp` ist der
Einstiegspunkt und führt die kontinuierliche Mess-Schleife aus.

`web.WebServer` fungiert als Orchestrator: Er richtet Javalin ein, installiert
zwei `before`-Filter (Setup-Gating und Auth-Gating) und ruft anschließend die
statische `register(app, …deps)`-Methode jeder der zehn Routen-Registrar-Klassen
in `web.api` auf (`PageRoutes`, `MeasurementRoutes`, `ReliabilityRoutes`,
`ExportRoutes`, `HostRoutes`, `DnsRoutes`, `SettingsRoutes`, `SetupRoutes`,
`AuthRoutes`, `ServiceReachabilityRoutes`).

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

## Störungs-Lokalisierung

Die Messung fragt nicht mehr nur „ist das Internet erreichbar?", sondern „**wer
ist schuld?**". `network.GatewayDiscovery` ermittelt per Traceroute die lokale
Gateway-Kette: Es verfolgt die RFC-1918-Hops (privat) und kennzeichnet das
**nahe** Gateway (den lokalen Router) und das **ferne** Gateway (das
Internet-Gateway); liefert der Traceroute nichts, greift ein Fallback auf die
Routing-Tabelle. Virtuelle Gateways werden gesondert behandelt: Die
Docker-Default-Bridge (172.17/16) wird übersprungen, und bei VM-/Container-NAT
(10.0.2.x oder erkannter Container) wird ein Warnhinweis angezeigt. Pro Segment
kann der Nutzer eine **manuelle IP** festlegen, das **Internet-Gateway nicht
kontinuierlich pingen** und bei lokalem IP-Wechsel zwischen persistentem Gateway
und Neu-Ermittlung wählen.

`report.ConnectivityAssessment` verdichtet die Segment-Ergebnisse zu einem
Verdikt, das den Schuldigen benennt: den **Router**, das **Internet-Gateway**
oder das **Internet**.

## Zuverlässigkeitsbericht (lückenbewusste Kennzahlen)

`report.ReliabilityReport` berechnet Kennzahlen, die Mess-Lücken berücksichtigen:

- **Verfügbarkeit (uptime)** = erfolgreiche / gemessene Stichproben – **nicht** die Wanduhrzeit, sodass Pausen die Zahl weder aufblähen noch drücken
- **Abdeckung (coverage)** – wie viel des Zeitraums tatsächlich beprobt wurde
- **MTBF / MTTR** – mittlere Zeit zwischen / bis zur Behebung
- **Aggregierte Ausfälle** – ≥2 aufeinanderfolgende Fehlmessungen in einem
  zusammenhängenden Lauf zählen als **ein** Ausfall mit Start, Ende, Dauer und
  sampleCount; einzelne Ausfälle lassen sich aus der Wertung **ausschließen**
  (DB-Spalte `excluded`)

**Wartungsfenster** schreiben pro übersprungenem Zyklus einen Wartungs-Marker
(Messungstyp `MAINTENANCE`), damit geplante Lücken nicht als Datenausfall
zählen. Das Standard-Messintervall beträgt 30 s.

## Dienst-Erreichbarkeit (Sperr-/Zensur-Erkennung)

Ein optionales, **standardmäßig deaktiviertes** Feature prüft, ob ausgewählte
Online-Dienste (Facebook, Instagram, X, YouTube, WhatsApp, …) erreichbar oder
**gesperrt** sind – und *wie*. Es läuft in einem eigenen langsamen Takt
(Standard alle 6 h), getrennt von der 30-s-Messschleife.

- **Schicht-Probe** (`network.ServiceReachabilityProbe`, parallel über Virtual
  Threads): DNS über den System-/ISP-Resolver **vs.** einen öffentlichen Resolver
  (1.1.1.1), TCP-Connect, ein TLS-Handshake mit echtem SNI **vs.** einem harmlosen
  Kontroll-SNI sowie HTTP-Status/Sperrseiten-Prüfung. Die erste gebrochene Schicht
  gewinnt.
- **Reines Verdikt** (`report.ServiceReachabilityAssessment`, Schwester von
  `ConnectivityAssessment`): `REACHABLE` · `SERVICE_DOWN` · `DNS_BLOCKED` ·
  `CONNECTION_BLOCKED` · `SNI_BLOCKED` · `BLOCKPAGE` · `UNKNOWN`.
- **Leitungs-Gate**: Vor jedem Lauf fragt der Scheduler den laufenden
  30-s-Monitor „steht die Leitung?" (jüngste erfolgreiche PING-/HTTP-Messung). Wenn
  nicht, wird der Lauf übersprungen und ein einzelner `LINE_DOWN`-Marker
  geschrieben – ein echter Ausfall wird nie als Sperre fehlinterpretiert.
- **Speicherung & Episoden**: Ergebnisse landen in der Tabelle `service_checks`
  (Twin-DB); `report.ServiceReachabilityReport` verdichtet aufeinanderfolgende
  gleiche Verdikte zu **Episoden** („gesperrt vom 1.–11. März"), in der UI als
  Kacheln + Ampel und im PDF als Timeline dargestellt.
- **`ServiceReachabilityScheduler`** (Root-Paket) steuert den langsamen Lauf und
  den manuellen „Jetzt prüfen"-Auslöser (5-Minuten-Abkühlphase);
  `web.api.ServiceReachabilityRoutes` stellt Status, Verlauf, Einstellungen und
  Jetzt-prüfen bereit.

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
- **Drei Mechanismen**: `I18n.resolve()` ersetzt `{{key}}`-Platzhalter in den HTML-Renderern, ein eingebettetes `const I18N`-Objekt versorgt das Frontend-JavaScript (`I18N['key']` in `app.js`), und `I18n.get()` liefert Texte für PDF und WebServer (mit locale-bewusster Zahlen-/Datumsformatierung)
- **Fallback-Kette**: gewählte Sprache → Deutsch (Referenz) → Schlüsselname; die UI zeigt nie ein Loch
- **Erweiterbar ohne Neukompilieren**: ein externer Ordner `./lang` neben der JAR wird zusätzlich eingelesen; dort abgelegte Dateien erscheinen automatisch im Dropdown
- **PDF-Unicode**: die freie Schrift **DejaVu Sans** ist eingebettet (`IDENTITY_H`), damit auch Türkisch, Polnisch und Kyrillisch (Ukrainisch) korrekt dargestellt werden; dieselbe Schrift speist die JFreeChart-Beschriftungen

Die Sprache wird global pro Installation in `config.json` gespeichert und ist im
Setup-Wizard sowie im Einstellungen-Tab umstellbar.

## Statische Web-Assets

CSS und JavaScript wurden aus `HtmlPageRenderer` in statische Dateien unter
`src/main/resources/web/` ausgelagert (`app.css`, `app.js`); Javalin liefert sie
über `staticFiles` (gemappt auf `/web`) unter `/app.css` bzw. `/app.js` aus. Im
Renderer bleibt nur die HTML-Struktur mit `{{i18n}}`-Platzhaltern plus ein
kleines Inline-`<script>` mit server-injizierten Globals (`I18N`, `LOCALE`,
`GW_LABELS`).

---

Vollständige Dokumentation: [`docs/latex/signalreport-dokumentation.pdf`](latex/signalreport-dokumentation.pdf)
Projektstruktur: [`ProjectStructure_de.md`](ProjectStructure_de.md)
