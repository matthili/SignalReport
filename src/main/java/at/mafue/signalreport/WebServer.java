package at.mafue.signalreport;

import io.javalin.Javalin;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class WebServer {
    private final H2MeasurementRepository repository;
    private Javalin app;

    public WebServer(H2MeasurementRepository repository) {
        this.repository = repository;
    }

    public void start(int port) {
        // Jackson mit JavaTimeModule konfigurieren
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.jsonMapper(new io.javalin.json.JavalinJackson(objectMapper));
        }).start(port);

        // Statische HTML-Seite (Root)
        app.get("/", ctx -> {
            ctx.html(createHtmlPage());
        });

        // REST-API für Messungen
        app.get("/api/measurements", ctx -> {
            try {
                int limit = ctx.queryParam("limit") != null
                    ? Integer.parseInt(ctx.queryParam("limit"))
                    : 10;

                List<Measurement> measurements = repository.findLastN(limit);
                ctx.json(measurements);
            } catch (NumberFormatException e) {
                ctx.status(400);
                ctx.json(new ErrorResponse("Ungültiger Limit-Parameter"));
            } catch (SQLException e) {
                ctx.status(500);
                ctx.json(new ErrorResponse("Datenbankfehler: " + e.getMessage()));
            }
        });

        // Statistik-API
        app.get("/api/statistics", ctx -> {
            try {
                int hours = ctx.queryParam("hours") != null
                    ? Integer.parseInt(ctx.queryParam("hours"))
                    : 24;

                var pingStats = repository.calculateStatistics("PING", hours);
                var dnsStats = repository.calculateStatistics("DNS", hours);
                var httpStats = repository.calculateStatistics("HTTP", hours);

                ctx.json(Map.of(
                    "ping", pingStats,
                    "dns", dnsStats,
                    "http", httpStats,
                    "periodHours", hours
                ));
            } catch (Exception e) {
                ctx.status(500);
                ctx.json(new ErrorResponse("Statistik-Fehler: " + e.getMessage()));
            }
        });

        // Stunden-basierte Durchschnittswerte (für Heatmap)
        app.get("/api/hourly-averages", ctx -> {
            try {
                int days = ctx.queryParam("days") != null
                    ? Integer.parseInt(ctx.queryParam("days"))
                    : 7;

                String type = ctx.queryParam("type") != null
                    ? ctx.queryParam("type")
                    : "PING";

                var averages = repository.calculateHourlyAverages(type, days);
                ctx.json(averages);
            } catch (NumberFormatException e) {
                ctx.status(400);
                ctx.json(new ErrorResponse("Ungültiger Parameter: " + e.getMessage()));
            } catch (Exception e) {
                ctx.status(500);
                ctx.json(new ErrorResponse("Stunden-Daten-Fehler: " + e.getMessage()));
            }
        });

        // PDF-Bericht generieren
        app.get("/api/report", ctx -> {
            try {
                int hours = ctx.queryParam("hours") != null
                    ? Integer.parseInt(ctx.queryParam("hours"))
                    : 24;

                PdfReportGenerator generator = new PdfReportGenerator(repository);
                byte[] pdfBytes = generator.generateReport(hours);

                ctx.contentType("application/pdf");
                ctx.header("Content-Disposition", "attachment; filename=signalreport-" +
                    java.time.LocalDate.now() + ".pdf");
                ctx.result(pdfBytes);
            } catch (NumberFormatException e) {
                ctx.status(400);
                ctx.json(new ErrorResponse("Ungültiger Stunden-Parameter"));
            } catch (Exception e) {
                ctx.status(500);
                ctx.json(new ErrorResponse("PDF-Generierungsfehler: " + e.getMessage()));
            }
        });

        // Host-Informationen API
        app.get("/api/hosts", ctx -> {
            try {
                List<H2MeasurementRepository.HostInfo> hosts = repository.getAllHosts();
                ctx.json(hosts);
            } catch (SQLException e) {
                ctx.status(500);
                ctx.json(new ErrorResponse("Host-Daten-Fehler: " + e.getMessage()));
            }
        });

        // Host-Details für aktuellen Host
        app.get("/api/host/current", ctx -> {
            try {
                String hostHash = HostIdentifier.getHostHash();
                String hostname = HostIdentifier.getHostname();
                String os = HostIdentifier.getOperatingSystem();

                ctx.json(Map.of(
                    "hostHash", hostHash,
                    "hostname", hostname,
                    "operatingSystem", os,
                    "localIPv4", NetworkInfo.getLocalIPv4(),
                    "localIPv6", NetworkInfo.getLocalIPv6(),
                    "externalIPv4", NetworkInfo.getExternalIPv4(),
                    "externalIPv6", NetworkInfo.getExternalIPv6()
                ));
            } catch (Exception e) {
                ctx.status(500);
                ctx.json(new ErrorResponse("Host-Info-Fehler: " + e.getMessage()));
            }
        });

        // Alle verfügbaren DNS-Server
        app.get("/api/dns/servers", ctx -> {
            try {
                Config config = Config.load("config.json");
                ctx.json(config.getDnsServers());
            } catch (Exception e) {
                ctx.status(500);
                ctx.json(new ErrorResponse("DNS-Server-Lade-Fehler: " + e.getMessage()));
            }
        });

        // DNS-Benchmark ausführen
        app.post("/api/dns/benchmark", ctx -> {
            try {
                Config config = Config.load("config.json");
                String hostname = ctx.queryParam("hostname") != null
                    ? ctx.queryParam("hostname")
                    : "google.com";

                DnsBenchmark benchmark = new DnsBenchmark(config.getDnsServers(), hostname, 5000);
                List<DnsBenchmark.DnsResult> results = benchmark.benchmark();

                // Ergebnisse nach Latenz sortieren
                results.sort((a, b) -> Double.compare(a.getLatencyMs(), b.getLatencyMs()));

                ctx.json(results);
            } catch (Exception e) {
                ctx.status(500);
                ctx.json(new ErrorResponse("DNS-Benchmark-Fehler: " + e.getMessage()));
            }
        });

        // DNS-Statistik pro Region
        app.get("/api/dns/statistics", ctx -> {
            try {
                Config config = Config.load("config.json");
                ctx.json(Map.of(
                    "totalServers", config.getDnsServers().size(),
                    "regions", config.getDnsServers().stream()
                        .map(Config.DnsServer::getRegion)
                        .distinct()
                        .toList(),
                    "providers", config.getDnsServers().stream()
                        .map(Config.DnsServer::getProvider)
                        .distinct()
                        .toList()
                ));
            } catch (Exception e) {
                ctx.status(500);
                ctx.json(new ErrorResponse("DNS-Statistik-Fehler: " + e.getMessage()));
            }
        });

        // Aktuelle Konfiguration abrufen
        app.get("/api/config/current", ctx -> {
            try {
                Config config = Config.getInstance();
                ctx.json(Map.of(
                    "ping", config.getMeasurement().getTargets().getPing(),
                    "dns", config.getMeasurement().getTargets().getDns(),
                    "http", config.getMeasurement().getTargets().getHttp(),
                    "intervalSeconds", config.getMeasurement().getIntervalSeconds(),
                    "maintenance", Map.of(
                        "enabled", config.getMaintenanceWindow().isEnabled(),
                        "startHour", config.getMaintenanceWindow().getStartHour(),
                        "startMinute", config.getMaintenanceWindow().getStartMinute(),
                        "endHour", config.getMaintenanceWindow().getEndHour(),
                        "endMinute", config.getMaintenanceWindow().getEndMinute()
                    ),
                    "userInfo", Map.of(
                        "provider", config.getUserInfo().getProvider(),
                        "customerId", config.getUserInfo().getCustomerId(),
                        "userName", config.getUserInfo().getUserName()
                    )
                ));
            } catch (Exception e) {
                ctx.status(500);
                ctx.json(new ErrorResponse("Config-Lade-Fehler: " + e.getMessage()));
            }
        });

        // Konfiguration aktualisieren + speichern
        app.post("/api/config/update", ctx -> {
            try {
                var body = ctx.bodyAsClass(java.util.Map.class);

                // Targets - mit Null-Checks
                String ping = "8.8.8.8";
                String dns = "google.com";
                String http = "https://example.com";
                int interval = 10;

                if (body.get("ping") != null) ping = body.get("ping").toString().trim();
                if (body.get("dns") != null) dns = body.get("dns").toString().trim();
                if (body.get("http") != null) http = body.get("http").toString().trim();
                if (body.get("intervalSeconds") != null) {
                    try {
                        interval = Integer.parseInt(body.get("intervalSeconds").toString());
                    } catch (NumberFormatException e) {
                        interval = 10;
                    }
                }

                // Maintenance - mit Null-Checks
                boolean maintenanceEnabled = false;
                int startHour = 4, startMinute = 0, endHour = 4, endMinute = 10;

                if (body.get("maintenance") != null) {
                    var maintenance = (java.util.Map<String, Object>) body.get("maintenance");
                    if (maintenance.get("enabled") != null) {
                        maintenanceEnabled = Boolean.parseBoolean(maintenance.get("enabled").toString());
                    }
                    if (maintenance.get("startHour") != null) {
                        try { startHour = Integer.parseInt(maintenance.get("startHour").toString()); } catch (Exception e) {}
                    }
                    if (maintenance.get("startMinute") != null) {
                        try { startMinute = Integer.parseInt(maintenance.get("startMinute").toString()); } catch (Exception e) {}
                    }
                    if (maintenance.get("endHour") != null) {
                        try { endHour = Integer.parseInt(maintenance.get("endHour").toString()); } catch (Exception e) {}
                    }
                    if (maintenance.get("endMinute") != null) {
                        try { endMinute = Integer.parseInt(maintenance.get("endMinute").toString()); } catch (Exception e) {}
                    }
                }

                // UserInfo - mit Null-Checks
                String provider = "";
                String customerId = "";
                String userName = "";

                if (body.get("userInfo") != null) {
                    var userInfo = (java.util.Map<String, Object>) body.get("userInfo");
                    if (userInfo.get("provider") != null) provider = userInfo.get("provider").toString().trim();
                    if (userInfo.get("customerId") != null) customerId = userInfo.get("customerId").toString().trim();
                    if (userInfo.get("userName") != null) userName = userInfo.get("userName").toString().trim();
                }

                // Update Config
                Config currentConfig = Config.getInstance();
                currentConfig.updateTargets(ping, dns, http, interval);
                currentConfig.updateMaintenanceWindow(maintenanceEnabled, startHour, startMinute, endHour, endMinute);
                currentConfig.updateUserInfo(provider, customerId, userName);

                // Persistieren
                Config.save("config.json");

                ctx.status(200);
                ctx.result("Konfiguration erfolgreich aktualisiert und gespeichert!");
            } catch (Exception e) {
                ctx.status(500);
                ctx.json(new ErrorResponse("Config-Speicher-Fehler: " + e.getMessage()));
            }
        });

        // IP-Änderungen abrufen
        app.get("/api/ip-changes", ctx -> {
            try {
                int limit = ctx.queryParam("limit") != null
                    ? Integer.parseInt(ctx.queryParam("limit"))
                    : 50;

                List<H2MeasurementRepository.IpChange> changes = repository.getIpChanges(limit);
                ctx.json(changes);
            } catch (NumberFormatException e) {
                ctx.status(400);
                ctx.json(new ErrorResponse("Ungültiger Limit-Parameter"));
            } catch (SQLException e) {
                ctx.status(500);
                ctx.json(new ErrorResponse("IP-Änderungen-Fehler: " + e.getMessage()));
            }
        });

        // IP-Wechsel-Statistik pro Host
        app.get("/api/ip-statistics", ctx -> {
            try {
                List<H2MeasurementRepository.IpChangeStats> stats = repository.getIpChangeStatistics();
                ctx.json(stats);
            } catch (SQLException e) {
                ctx.status(500);
                ctx.json(new ErrorResponse("IP-Statistik-Fehler: " + e.getMessage()));
            }
        });

        // 🔑 CSV-Export für Messungen
        app.get("/api/export/csv", ctx -> {
            try {
                // Parameter: hours (Standard: 24), type (Standard: alle)
                int hours = ctx.queryParam("hours") != null
                    ? Integer.parseInt(ctx.queryParam("hours"))
                    : 24;

                String typeFilter = ctx.queryParam("type"); // null = alle Typen

                // Messungen holen
                List<Measurement> measurements = repository.findLastN(10000); // Max. 10.000 Zeilen

                // CSV-Header
                StringBuilder csv = new StringBuilder();
                csv.append("timestamp;type;target;latency_ms;success;local_ipv4;local_ipv6;external_ipv4;external_ipv6;host_hash\n");

                // CSV-Zeilen (nur Messungen der letzten X Stunden)
                Instant cutoff = Instant.now().minusSeconds(hours * 3600L);
                int count = 0;

                for (Measurement m : measurements) {
                    if (m.getTimestamp().isBefore(cutoff)) break; // Frühere Messungen überspringen

                    if (typeFilter != null && !typeFilter.equals(m.getType())) continue; // Typ-Filter

                    // CSV-Zeile erstellen (RFC 4180-kompatibel mit Semikolon-Trenner)
                    csv.append(m.getTimestamp().toString().replace("T", " ").replace("Z", ""))
                       .append(";")
                       .append(escapeCsv(m.getType()))
                       .append(";")
                       .append(escapeCsv(m.getTarget()))
                       .append(";")
                       .append(String.format("%.3f", m.getLatencyMs()))
                       .append(";")
                       .append(m.isSuccess() ? "1" : "0")
                       .append(";")
                       .append(escapeCsv(m.getLocalIPv4()))
                       .append(";")
                       .append(escapeCsv(m.getLocalIPv6()))
                       .append(";")
                       .append(escapeCsv(m.getExternalIPv4()))
                       .append(";")
                       .append(escapeCsv(m.getExternalIPv6()))
                       .append(";")
                       .append(escapeCsv(m.getHostHash()))
                       .append("\n");

                    count++;
                    if (count >= 10000) break; // Sicherheitslimit
                }

                // HTTP-Header für Download
                ctx.contentType("text/csv");
                ctx.header("Content-Disposition",
                    "attachment; filename=signalreport-" +
                    java.time.LocalDate.now() + "-" +
                    java.time.LocalTime.now().toString().replace(":", "") +
                    ".csv");

                ctx.result(csv.toString());
            } catch (NumberFormatException e) {
                ctx.status(400);
                ctx.json(new ErrorResponse("Ungültiger Stunden-Parameter"));
            } catch (Exception e) {
                ctx.status(500);
                ctx.json(new ErrorResponse("CSV-Export-Fehler: " + e.getMessage()));
            }
        });



        System.out.println("🌍 Web-Interface läuft unter: http://localhost:" + port);
    }
    // Hilfsfunktion für CSV-Escaping (Semikolon, Zeilenumbruch, Anführungszeichen)
            private String escapeCsv(String value) {
                if (value == null || value.isEmpty() || value.equals("unknown")) {
                    return "";
                }
                // Semikolon, Zeilenumbruch oder Anführungszeichen → in Anführungszeichen setzen + verdoppeln
                if (value.contains(";") || value.contains("\n") || value.contains("\"")) {
                    return "\"" + value.replace("\"", "\"\"") + "\"";
                }
                return value;
            }
    // Hilfsklasse für JSON-Fehler – MUSS öffentlich sein mit öffentlichem Feld!
    public static class ErrorResponse {
        public final String error;
        public ErrorResponse(String error) { this.error = error; }
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }

    // HTML-Seite erstellen (komplett mit allen Tabs)
    private String createHtmlPage() {
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>SignalReport</title>
    <script src="https://cdn.jsdelivr.net/npm/chart.js@3.9.1/dist/chart.min.js"></script>
    <style>
        body { font-family: Arial, sans-serif; max-width: 1200px; margin: 40px auto; padding: 20px; background: #f8f9fa; }
        .header { text-align: center; margin-bottom: 30px; }
        .header h1 { color: #0d6efd; }
        .network-info { background: white; padding: 15px; border-radius: 8px; margin-bottom: 20px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .network-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 20px; }
        .network-item { text-align: center; }
        .network-label { font-size: 0.9em; color: #6c757d; margin-bottom: 5px; }
        .network-value { font-weight: bold; font-family: monospace; font-size: 1.1em; color: #0d6efd; }
        .tabs { display: flex; gap: 10px; margin-bottom: 20px; }
        .tab { padding: 10px 20px; background: #e9ecef; border: none; border-radius: 5px; cursor: pointer; }
        .tab.active { background: #0d6efd; color: white; }
        .tab-content { display: none; }
        .tab-content.active { display: block; }
        .stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 15px; margin-bottom: 25px; }
        .stat-card { background: white; padding: 15px; border-radius: 8px; text-align: center; box-shadow: 0 2px 4px rgba(0,0,0,0.05); }
        .stat-label { font-size: 0.9em; color: #6c757d; margin-bottom: 5px; }
        .stat-value { font-size: 1.8em; font-weight: bold; }
        .stat-avg { color: #0d6efd; }
        .stat-p95 { color: #fd7e14; }
        .stat-loss { color: #dc3545; }
        .stat-jitter { color: #198754; }
        .chart-container { width: 100%; height: 300px; margin-bottom: 30px; background: white; padding: 15px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .heatmap-container { width: 100%; height: 220px; margin-top: 30px; background: white; padding: 15px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        table { width: 100%; border-collapse: collapse; margin-top: 20px; background: white; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        th, td { padding: 12px 15px; text-align: left; border-bottom: 1px solid #ddd; font-size: 0.9em; }
        th { background: #0d6efd; color: white; }
        .excellent { color: #198754; font-weight: bold; }
        .good { color: #ffc107; font-weight: bold; }
        .poor { color: #dc3545; font-weight: bold; }
        .failure { color: #6c757d; text-decoration: line-through; }
        .footer { text-align: center; margin-top: 30px; color: #6c757d; font-size: 0.9em; }
        .button-group { display: flex; gap: 10px; margin: 25px 0; flex-wrap: wrap; }
        .btn { padding: 10px 15px; background: #0d6efd; color: white; border: none; border-radius: 5px; cursor: pointer; text-decoration: none; display: inline-block; }
        .btn-secondary { background: #6c757d; }
        .btn-success { background: #198754; }
        .region-europa { background: #cfe8ff; }
        .region-nordamerika { background: #ffe0b2; }
        .dns-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 15px; margin-top: 20px; }
        .dns-card { background: white; padding: 15px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .dns-card h4 { margin: 0 0 10px 0; font-size: 1.1em; }
        .dns-card .latency { font-size: 1.5em; font-weight: bold; color: #0d6efd; }
        .dns-card .region { font-size: 0.9em; color: #6c757d; }
        .dns-card .success { color: #198754; }
        .dns-card .failure { color: #dc3545; }
    </style>
</head>
<body>
    <div class="header">
        <h1>📡 SignalReport</h1>
        <p>Internet-Qualitäts-Monitoring</p>
    </div>
    
    <div class="network-info">
        <div class="network-grid">
            <div class="network-item">
                <div class="network-label">Lokale IPv4</div>
                <div class="network-value" id="local-ipv4">--</div>
            </div>
            <div class="network-item">
                <div class="network-label">Externe IPv4</div>
                <div class="network-value" id="external-ipv4">--</div>
            </div>
            <div class="network-item">
                <div class="network-label">Hostname</div>
                <div class="network-value" id="current-host">--</div>
            </div>
            <div class="network-item">
                <div class="network-label">Host-Hash</div>
                <div class="network-value" style="font-size:0.9em;" id="current-hash">--</div>
            </div>
        </div>
    </div>
    
    <div class="tabs">
        <button class="tab active" onclick="showTab('monitoring')">📊 Monitoring</button>
        <button class="tab" onclick="showTab('dns')">🌍 DNS-Benchmark</button>
        <button class="tab" onclick="showTab('hosts')">🖥️ Hosts</button>
        <button class="tab" onclick="showTab('ip-tracking')">🌐 IP-Tracking</button>
        <button class="tab" onclick="showTab('settings')">⚙️ Einstellungen</button>
    </div>
    
    <div id="monitoring" class="tab-content active">
        <div class="stat-grid">
            <div class="stat-card">
                <div class="stat-label">⌀ PING (24h)</div>
                <div class="stat-value stat-avg" id="stat-avg">-- ms</div>
            </div>
            <div class="stat-card">
                <div class="stat-label">95th Percentile</div>
                <div class="stat-value stat-p95" id="stat-p95">-- ms</div>
            </div>
            <div class="stat-card">
                <div class="stat-label">Paketverlust</div>
                <div class="stat-value stat-loss" id="stat-loss">-- %</div>
            </div>
            <div class="stat-card">
                <div class="stat-label">Jitter</div>
                <div class="stat-value stat-jitter" id="stat-jitter">-- ms</div>
            </div>
        </div>
        
        <div class="chart-container">
            <canvas id="latencyChart"></canvas>
        </div>
        
        <div class="heatmap-container">
            <canvas id="hourlyChart"></canvas>
        </div>
        
        <div class="button-group">
            <a href="#" class="btn" onclick="downloadReport(24)">📄 PDF-Bericht (24h)</a>
            <a href="#" class="btn btn-secondary" onclick="downloadReport(168)">📄 PDF-Bericht (7 Tage)</a>
            <a href="#" class="btn btn-secondary" onclick="downloadCsv(24)">📊 CSV-Export (24h)</a>
            <a href="#" class="btn btn-secondary" onclick="downloadCsv(168)">📊 CSV-Export (7 Tage)</a>
            <a href="#" class="btn btn-secondary" onclick="showTab('hosts')">🖥️ Host-Informationen</a>
        </div>
        
        <table>
            <thead>
                <tr>
                    <th>Zeit</th>
                    <th>Typ</th>
                    <th>Ziel</th>
                    <th>Latenz</th>
                    <th>Status</th>
                    <th>Host</th>
                    <th>LAN IP</th>
                </tr>
            </thead>
            <tbody id="measurementsTable">
                <tr><td colspan="7" style="text-align:center">Lade Daten...</td></tr>
            </tbody>
        </table>
    </div>
    
    <div id="dns" class="tab-content">
        <h2>🌍 DNS-Benchmark</h2>
        <p>Vergleiche die Performance verschiedener DNS-Server weltweit.</p>
        
        <div class="button-group">
            <button class="btn btn-success" onclick="runDnsBenchmark()">🚀 Benchmark ausführen</button>
            <select id="dnsHostname" style="padding: 10px; border-radius: 5px;">
                <option value="google.com">google.com</option>
                <option value="example.com">example.com</option>
                <option value="github.com">github.com</option>
                <option value="wikipedia.org">wikipedia.org</option>
            </select>
        </div>
        
        <div id="dnsResults" class="dns-grid">
            <p id="dnsLoading">Klicke auf "Benchmark ausführen"...</p>
        </div>
    </div>
    
    <div id="hosts" class="tab-content">
        <h2>🖥️ Host-Informationen</h2>
        
        <table id="hostsTable">
            <thead>
                <tr>
                    <th>Hostname</th>
                    <th>OS</th>
                    <th>Hash</th>
                    <th>Erstellt</th>
                    <th>Zuletzt gesehen</th>
                </tr>
            </thead>
            <tbody id="hostsTableBody"></tbody>
        </table>
    </div>
    
    <div id="ip-tracking" class="tab-content">
        <h2>🌐 IP-Änderungs-Tracking</h2>
        <p>Überwachung der externen IP-Adresse – erkennt automatisch, wann sich die IP ändert (z.B. nach Router-Neustart).</p>
        
        <div style="background:#e7f5ff; padding:15px; border-radius:8px; margin:20px 0; border-left:4px solid #0d6efd;">
            <strong>💡 Hinweis:</strong> 
            <ul style="margin:10px 0 0 20px;">
                <li>Die externe IPv4-Adresse wird bei jeder Messung überprüft</li>
                <li>Bei IP-Änderung wird automatisch ein Eintrag erstellt</li>
                <li>Perfekt für DSL-Anschlüsse mit dynamischer IP</li>
            </ul>
        </div>
        
        <h3>📊 IP-Wechsel-Statistik</h3>
        <table id="ip-stats-table">
            <thead>
                <tr>
                    <th>Host</th>
                    <th>IP-Wechsel</th>
                    <th>Erster Wechsel</th>
                    <th>Letzter Wechsel</th>
                </tr>
            </thead>
            <tbody id="ip-stats-body"></tbody>
        </table>
        
        <h3>📋 Letzte IP-Änderungen</h3>
        <table id="ip-changes-table">
            <thead>
                <tr>
                    <th>Zeit</th>
                    <th>Host</th>
                    <th>Alt</th>
                    <th>Neu</th>
                    <th>Typ</th>
                </tr>
            </thead>
            <tbody id="ip-changes-body">
                <tr><td colspan="5" style="text-align:center">Lade IP-Änderungen...</td></tr>
            </tbody>
        </table>
    </div>
    
    <div id="settings" class="tab-content">
        <h2>⚙️ Messkonfiguration</h2>
        <p>Ändere die Messziele, Intervall und Maintenance-Fenster. Einstellungen werden sofort gespeichert und beim nächsten Start wiederhergestellt.</p>
        
        <div style="background:white; padding:20px; border-radius:8px; margin:20px 0;">
            <h3>📍 Messziele & Intervall</h3>
            <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap:20px; margin-top:15px;">
                <div>
                    <label style="display:block; margin-bottom:5px; font-weight:bold;">Ping-Ziel (IP)</label>
                    <input type="text" id="config-ping" value="8.8.8.8" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                    <small style="color:#6c757d;">Beispiele: 8.8.8.8, 1.1.1.1, 192.168.1.1</small>
                </div>
                <div>
                    <label style="display:block; margin-bottom:5px; font-weight:bold;">DNS-Ziel (Hostname)</label>
                    <input type="text" id="config-dns" value="google.com" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                    <small style="color:#6c757d;">Beispiele: google.com, example.com</small>
                </div>
                <div>
                    <label style="display:block; margin-bottom:5px; font-weight:bold;">HTTP-Ziel (URL)</label>
                    <input type="text" id="config-http" value="https://example.com" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                    <small style="color:#6c757d;">Beispiele: https://heise.de, https://github.com</small>
                </div>
                <div>
                    <label style="display:block; margin-bottom:5px; font-weight:bold;">⏱️ Intervall (Sekunden)</label>
                    <input type="number" id="config-interval" value="10" min="5" max="3600" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                    <small style="color:#6c757d;">Min: 5s, Max: 1h</small>
                </div>
            </div>
        </div>
        
        <div style="background:#fff8e6; padding:20px; border-radius:8px; margin:20px 0; border-left:4px solid #ffc107;">
            <h3>⏸️ Maintenance-Fenster (Messungsunterbrechung)</h3>
            <p>Definiere ein Zeitfenster, in dem keine Messungen durchgeführt werden (z.B. für Router-Updates).</p>
            
            <div style="display:flex; align-items:center; gap:15px; margin-top:15px;">
                <input type="checkbox" id="maintenance-enabled" style="width:18px; height:18px;">
                <label for="maintenance-enabled" style="font-weight:bold;">Maintenance-Fenster aktivieren</label>
            </div>
            
            <div id="maintenance-fields" style="display:none; margin-top:15px; padding:15px; background:#fff3cd; border-radius:8px;">
                <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap:15px; align-items:end;">
                    <div>
                        <label style="display:block; margin-bottom:5px; font-weight:bold;">Von Stunde</label>
                        <select id="maintenance-start-hour" style="width:100%; padding:6px; border:1px solid #ddd; border-radius:4px;">
                            <!-- Stunden 0-23 werden per JS befüllt -->
                        </select>
                    </div>
                    <div>
                        <label style="display:block; margin-bottom:5px; font-weight:bold;">Von Minute</label>
                        <select id="maintenance-start-minute" style="width:100%; padding:6px; border:1px solid #ddd; border-radius:4px;">
                            <option value="0">00</option>
                            <option value="5">05</option>
                            <option value="10">10</option>
                            <option value="15">15</option>
                            <option value="20">20</option>
                            <option value="25">25</option>
                            <option value="30">30</option>
                            <option value="35">35</option>
                            <option value="40">40</option>
                            <option value="45">45</option>
                            <option value="50">50</option>
                            <option value="55">55</option>
                        </select>
                    </div>
                    <div>
                        <label style="display:block; margin-bottom:5px; font-weight:bold;">Bis Stunde</label>
                        <select id="maintenance-end-hour" style="width:100%; padding:6px; border:1px solid #ddd; border-radius:4px;">
                            <!-- Stunden 0-23 werden per JS befüllt -->
                        </select>
                    </div>
                    <div>
                        <label style="display:block; margin-bottom:5px; font-weight:bold;">Bis Minute</label>
                        <select id="maintenance-end-minute" style="width:100%; padding:6px; border:1px solid #ddd; border-radius:4px;">
                            <option value="0">00</option>
                            <option value="5">05</option>
                            <option value="10">10</option>
                            <option value="15">15</option>
                            <option value="20">20</option>
                            <option value="25">25</option>
                            <option value="30">30</option>
                            <option value="35">35</option>
                            <option value="40">40</option>
                            <option value="45">45</option>
                            <option value="50">50</option>
                            <option value="55">55</option>
                        </select>
                    </div>
                </div>
                <div style="margin-top:10px; font-size:0.9em; color:#856404;">
                    💡 Hinweis: Fenster kann über Mitternacht gehen (z.B. 23:00–01:00)
                </div>
            </div>
        </div>
        
        <div style="background:#e7f5ff; padding:20px; border-radius:8px; margin:20px 0; border-left:4px solid #0d6efd;">
            <h3>👤 Benutzer-Informationen</h3>
            <p>Diese Informationen werden später im PDF-Bericht angezeigt.</p>
            
            <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap:20px; margin-top:15px;">
                <div>
                    <label style="display:block; margin-bottom:5px; font-weight:bold;">Provider</label>
                    <input type="text" id="config-provider" value="" placeholder="z.B. Telekom, Vodafone" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                </div>
                <div>
                    <label style="display:block; margin-bottom:5px; font-weight:bold;">Kundennummer</label>
                    <input type="text" id="config-customer-id" value="" placeholder="z.B. 123456789" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                </div>
                <div>
                    <label style="display:block; margin-bottom:5px; font-weight:bold;">Name</label>
                    <input type="text" id="config-user-name" value="" placeholder="z.B. Max Mustermann" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                </div>
            </div>
        </div>
        
        <button onclick="saveConfig()" style="margin-top:20px; padding:12px 24px; background:#28a745; color:white; border:none; border-radius:5px; font-weight:bold; cursor:pointer;">
            💾 Konfiguration speichern
        </button>
        <div id="config-status" style="margin-top:10px; padding:10px; border-radius:4px; display:none;"></div>
        
        <div style="background:#e7f3ff; padding:15px; border-radius:8px; margin-top:30px;">
            <strong>💡 Hinweise:</strong>
            <ul style="margin:10px 0 0 20px;">
                <li>Änderungen werden <strong>sofort übernommen</strong> – die nächste Messrunde verwendet die neuen Einstellungen</li>
                <li>Konfiguration wird in <code>config.json</code> gespeichert und beim nächsten Start wiederhergestellt</li>
                <li>Während des Maintenance-Fensters werden <strong>keine Messungen</strong> durchgeführt (Terminal zeigt ⏸️ an)</li>
            </ul>
        </div>
    </div>
    
    <div class="footer">
        <p>SignalReport v1.0 • Daten aktualisieren sich automatisch</p>
    </div>
    
    <script>
        // Stunden-Dropdowns befüllen (0-23)
        function populateHourDropdowns() {
            const hours = Array.from({length: 24}, (_, i) => i);
            ['maintenance-start-hour', 'maintenance-end-hour'].forEach(id => {
                const select = document.getElementById(id);
                select.innerHTML = '';
                hours.forEach(hour => {
                    const option = document.createElement('option');
                    option.value = hour;
                    option.textContent = hour.toString().padStart(2, '0');
                    select.appendChild(option);
                });
            });
        }

        // Tab-Wechsel
        function showTab(tabId) {
            document.querySelectorAll('.tab-content').forEach(tab => tab.classList.remove('active'));
            document.querySelectorAll('.tab').forEach(tab => tab.classList.remove('active'));
            document.getElementById(tabId).classList.add('active');
            event.target.classList.add('active');
            
            if (tabId === 'hosts') {
                loadHosts();
            } else if (tabId === 'settings') {
                populateHourDropdowns();
                loadConfig();
            } else if (tabId === 'ip-tracking') {
                loadIpStatistics();
                loadIpChanges();
            }
        }
        
        // Netzwerk-Info laden
        function loadNetworkInfo() {
            fetch('/api/host/current')
                .then(response => response.json())
                .then(info => {
                    document.getElementById('local-ipv4').textContent = info.localIPv4 || 'unknown';
                    document.getElementById('external-ipv4').textContent = info.externalIPv4 || 'unknown';
                    document.getElementById('current-host').textContent = info.hostname || 'unknown';
                    document.getElementById('current-hash').textContent = info.hostHash ? info.hostHash.substring(0, 12) : 'unknown';
                })
                .catch(error => console.error('Netzwerk-Info-Fehler:', error));
        }
        
        // Statistik laden
        function loadStatistics() {
            fetch('/api/statistics?hours=24')
                .then(response => response.json())
                .then(stats => {
                    document.getElementById('stat-avg').textContent = stats.ping.avgLatency.toFixed(1) + ' ms';
                    document.getElementById('stat-p95').textContent = stats.ping.p95Latency.toFixed(1) + ' ms';
                    document.getElementById('stat-loss').textContent = stats.ping.packetLossPercent.toFixed(1) + ' %';
                    document.getElementById('stat-jitter').textContent = stats.ping.jitter.toFixed(1) + ' ms';
                })
                .catch(error => console.error('Statistik-Fehler:', error));
        }

        // Haupt-Chart laden
        function loadMeasurements() {
            fetch('/api/measurements?limit=20')
                .then(response => response.json())
                .then(data => {
                    const tableBody = document.getElementById('measurementsTable');
                    tableBody.innerHTML = '';
                    data.forEach(m => {
                        const row = document.createElement('tr');
                        const latencyClass = m.success 
                            ? (m.latencyMs < 50 ? 'excellent' : m.latencyMs < 100 ? 'good' : 'poor')
                            : 'failure';
                        const statusText = m.success ? '✅' : '❌';
                        row.innerHTML = `
                            <td>${new Date(m.timestamp * 1000).toLocaleTimeString('de-DE')}</td>
                            <td><strong>${m.type}</strong></td>
                            <td>${m.target}</td>
                            <td class="${latencyClass}"><strong>${m.latencyMs.toFixed(1)}</strong> ms</td>
                            <td>${statusText}</td>
                            <td style="font-family:monospace;font-size:0.8em;">${m.hostHash.substring(0,8)}</td>
                            <td style="font-family:monospace;font-size:0.8em;">${m.localIPv4}</td>
                        `;
                        tableBody.appendChild(row);
                    });
                    
                    const pingData = data.filter(m => m.type === 'PING').slice(0, 10).reverse();
                    const labels = pingData.map(m => new Date(m.timestamp * 1000).toLocaleTimeString('de-DE', {hour: '2-digit', minute:'2-digit'}));
                    const values = pingData.map(m => m.latencyMs);
                    
                    const ctx = document.getElementById('latencyChart').getContext('2d');
                    
                    if (window.latencyChart && typeof window.latencyChart.destroy === 'function') {
                        window.latencyChart.destroy();
                    }
                    
                    window.latencyChart = new Chart(ctx, {
                        type: 'line',
                         data: {
                            labels: labels,
                            datasets: [{
                                label: 'PING Latenz (ms)',
                                data: values,
                                borderColor: '#0d6efd',
                                backgroundColor: 'rgba(13, 110, 253, 0.1)',
                                borderWidth: 2,
                                tension: 0.3,
                                fill: true,
                                pointRadius: 4,
                                pointHoverRadius: 6
                            }]
                        },
                        options: {
                            responsive: true,
                            maintainAspectRatio: false,
                            plugins: {
                                title: {
                                    display: true,
                                    text: 'PING Latenz über Zeit'
                                }
                            },
                            scales: {
                                y: {
                                    beginAtZero: true,
                                    title: { display: true, text: 'Latenz (ms)' }
                                },
                                x: {
                                    title: { display: true, text: 'Uhrzeit' }
                                }
                            }
                        }
                    });
                })
                .catch(error => {
                    console.error('Fehler beim Laden:', error);
                    document.getElementById('measurementsTable').innerHTML = 
                        '<tr><td colspan="7" style="text-align:center;color:red">Fehler beim Laden der Daten</td></tr>';
                });
        }

        // Heatmap laden
        function loadHourlyChart() {
            fetch('/api/hourly-averages?days=7&type=PING')
                .then(response => response.json())
                .then(data => {
                    const hours = Array.from({length: 24}, (_, i) => i);
                    const latencies = hours.map(h => {
                        const entry = data.find(e => e.hourOfDay === h);
                        return entry ? entry.avgLatency : null;
                    });
                    
                    const ctx = document.getElementById('hourlyChart').getContext('2d');
                    
                    if (window.hourlyChart && typeof window.hourlyChart.destroy === 'function') {
                        window.hourlyChart.destroy();
                    }
                    
                    window.hourlyChart = new Chart(ctx, {
                        type: 'bar',
                         data: {
                            labels: hours.map(h => h + ':00'),
                            datasets: [{
                                label: '⌀ Latenz pro Stunde (letzte 7 Tage)',
                                data: latencies.map(l => l !== null ? parseFloat(l.toFixed(1)) : 0),
                                backgroundColor: latencies.map(l => {
                                    if (l === null) return '#e9ecef';
                                    if (l < 50) return '#198754';
                                    if (l < 100) return '#ffc107';
                                    return '#dc3545';
                                }),
                                borderWidth: 0
                            }]
                        },
                        options: {
                            responsive: true,
                            maintainAspectRatio: false,
                            plugins: {
                                legend: { display: false },
                                tooltip: {
                                    callbacks: {
                                        label: context => {
                                            const value = context.parsed.y;
                                            if (value === 0) return 'Keine Messungen';
                                            return `${value.toFixed(1)} ms`;
                                        }
                                    }
                                }
                            },
                            scales: {
                                y: {
                                    beginAtZero: true,
                                    title: { display: true, text: 'Latenz (ms)' }
                                },
                                x: {
                                    title: { display: true, text: 'Uhrzeit' }
                                }
                            }
                        }
                    });
                })
                .catch(error => console.error('Stunden-Chart-Fehler:', error));
        }
        
        // Hosts laden
        function loadHosts() {
            fetch('/api/hosts')
                .then(response => response.json())
                .then(hosts => {
                    const tbody = document.getElementById('hostsTableBody');
                    tbody.innerHTML = '';
                    hosts.forEach(host => {
                        const row = document.createElement('tr');
                        row.innerHTML = `
                            <td>${host.hostname}</td>
                            <td>${host.operatingSystem}</td>
                            <td style="font-family:monospace;font-size:0.8em;">${host.hostHash.substring(0,8)}</td>
                            <td>${new Date(host.firstSeen * 1000).toLocaleDateString('de-DE')}</td>
                            <td>${new Date(host.lastSeen * 1000).toLocaleDateString('de-DE')}</td>
                        `;
                        tbody.appendChild(row);
                    });
                })
                .catch(error => console.error('Host-Fehler:', error));
        }
        
        // DNS-Benchmark ausführen
        function runDnsBenchmark() {
            const hostname = document.getElementById('dnsHostname').value;
            document.getElementById('dnsLoading').textContent = 'Benchmark läuft...';
            
            fetch(`/api/dns/benchmark?hostname=${hostname}`, { method: 'POST' })
                .then(response => response.json())
                .then(results => {
                    const container = document.getElementById('dnsResults');
                    container.innerHTML = '';
                    
                    results.forEach(result => {
                        const card = document.createElement('div');
                        card.className = `dns-card region-${result.region.toLowerCase()}`;
                        
                        const statusClass = result.success ? 'success' : 'failure';
                        const statusIcon = result.success ? '✅' : '❌';
                        
                        card.innerHTML = `
                            <h4>${result.serverName}</h4>
                            <div class="latency ${statusClass}">${result.latencyMs.toFixed(1)} ms ${statusIcon}</div>
                            <div class="region">${result.region} • ${result.provider}</div>
                            <div style="font-size:0.8em;color:#6c757d;">${result.serverAddress}</div>
                        `;
                        container.appendChild(card);
                    });
                })
                .catch(error => {
                    console.error('DNS-Benchmark-Fehler:', error);
                    document.getElementById('dnsLoading').textContent = 'Fehler beim Benchmark!';
                });
        }

        // IP-Statistik laden
        function loadIpStatistics() {
            fetch('/api/ip-statistics')
                .then(response => response.json())
                .then(stats => {
                    const tbody = document.getElementById('ip-stats-body');
                    tbody.innerHTML = '';
                    
                    if (stats.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="4" style="text-align:center">Keine IP-Änderungen erfasst</td></tr>';
                        return;
                    }
                    
                    stats.forEach(stat => {
                        const row = document.createElement('tr');
                        row.innerHTML = `
                            <td style="font-family:monospace;font-size:0.9em;">${stat.hostHash.substring(0,8)}</td>
                            <td><strong>${stat.changeCount}</strong></td>
                            <td>${new Date(stat.firstChange * 1000).toLocaleDateString('de-DE')}</td>
                            <td>${new Date(stat.lastChange * 1000).toLocaleDateString('de-DE')}</td>
                        `;
                        tbody.appendChild(row);
                    });
                })
                .catch(error => console.error('IP-Statistik-Fehler:', error));
        }

        // IP-Änderungen laden
        function loadIpChanges() {
            fetch('/api/ip-changes?limit=50')
                .then(response => response.json())
                .then(changes => {
                    const tbody = document.getElementById('ip-changes-body');
                    tbody.innerHTML = '';
                    
                    if (changes.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="5" style="text-align:center">Keine IP-Änderungen erfasst</td></tr>';
                        return;
                    }
                    
                    changes.forEach(change => {
                        const row = document.createElement('tr');
                        const changeType = change.changeType === 'INITIAL' ? '🟢 Initial' : '🔄 Wechsel';
                        const changeColor = change.changeType === 'INITIAL' ? '#198754' : '#0d6efd';
                        
                        row.innerHTML = `
                            <td>${new Date(change.timestamp * 1000).toLocaleString('de-DE')}</td>
                            <td style="font-family:monospace;font-size:0.8em;">${change.hostHash.substring(0,8)}</td>
                            <td style="font-family:monospace;">${change.oldIp || '–'}</td>
                            <td style="font-family:monospace;font-weight:bold;color:${changeColor};">${change.newIp}</td>
                            <td style="color:${changeColor};">${changeType}</td>
                        `;
                        tbody.appendChild(row);
                    });
                })
                .catch(error => {
                    console.error('IP-Änderungen-Fehler:', error);
                    document.getElementById('ip-changes-body').innerHTML = 
                        '<tr><td colspan="5" style="text-align:center;color:red">Fehler beim Laden der IP-Änderungen</td></tr>';
                });
        }

        // Einstellungen laden
        function loadConfig() {
            fetch('/api/config/current')
                .then(response => response.json())
                .then(config => {
                    // Messziele
                    document.getElementById('config-ping').value = config.ping;
                    document.getElementById('config-dns').value = config.dns;
                    document.getElementById('config-http').value = config.http;
                    document.getElementById('config-interval').value = config.intervalSeconds;
                    
                    // Maintenance
                    const maint = config.maintenance;
                    document.getElementById('maintenance-enabled').checked = maint.enabled;
                    document.getElementById('maintenance-start-hour').value = maint.startHour;
                    document.getElementById('maintenance-start-minute').value = maint.startMinute;
                    document.getElementById('maintenance-end-hour').value = maint.endHour;
                    document.getElementById('maintenance-end-minute').value = maint.endMinute;
                    document.getElementById('maintenance-fields').style.display = maint.enabled ? 'block' : 'none';
                    
                    // UserInfo
                    const ui = config.userInfo;
                    document.getElementById('config-provider').value = ui.provider || '';
                    document.getElementById('config-customer-id').value = ui.customerId || '';
                    document.getElementById('config-user-name').value = ui.userName || '';
                })
                .catch(error => console.error('Config-Lade-Fehler:', error));
        }

        // Maintenance-Checkbox Toggle
        document.getElementById('maintenance-enabled').addEventListener('change', function() {
            document.getElementById('maintenance-fields').style.display = this.checked ? 'block' : 'none';
        });

        // Konfiguration speichern
        function saveConfig() {
            const statusDiv = document.getElementById('config-status');
            statusDiv.style.display = 'block';
            statusDiv.style.background = '#fff3cd';
            statusDiv.textContent = '💾 Speichere Konfiguration...';
            
            const config = {
                ping: document.getElementById('config-ping').value.trim(),
                dns: document.getElementById('config-dns').value.trim(),
                http: document.getElementById('config-http').value.trim(),
                intervalSeconds: parseInt(document.getElementById('config-interval').value),
                maintenance: {
                    enabled: document.getElementById('maintenance-enabled').checked,
                    startHour: parseInt(document.getElementById('maintenance-start-hour').value),
                    startMinute: parseInt(document.getElementById('maintenance-start-minute').value),
                    endHour: parseInt(document.getElementById('maintenance-end-hour').value),
                    endMinute: parseInt(document.getElementById('maintenance-end-minute').value)
                },
                userInfo: {
                    provider: document.getElementById('config-provider').value.trim(),
                    customerId: document.getElementById('config-customer-id').value.trim(),
                    userName: document.getElementById('config-user-name').value.trim()
                }
            };
            
            fetch('/api/config/update', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(config)
            })
            .then(response => response.text())
            .then(message => {
                statusDiv.style.background = '#d4edda';
                statusDiv.textContent = '✅ ' + message;
                setTimeout(() => { statusDiv.style.display = 'none'; }, 3000);
            })
            .catch(error => {
                statusDiv.style.background = '#f8d7da';
                statusDiv.textContent = '❌ Fehler: ' + error.message;
            });
        }

        // PDF-Download
        function downloadReport(hours) {
            window.location.href = '/api/report?hours=' + hours;
        }

        // Initial laden
        loadNetworkInfo();
        loadStatistics();
        loadMeasurements();
        loadHourlyChart();
        
        // Alle 5 Sekunden aktualisieren
        setInterval(loadMeasurements, 5000);
        setInterval(loadStatistics, 30000);
        setInterval(loadHourlyChart, 300000);
        setInterval(loadNetworkInfo, 60000); // Netzwerk-Info alle 60 Sekunden
        
        // IP-Tracking alle 30 Sekunden aktualisieren (wenn Tab aktiv)
        setInterval(() => {
            const activeTab = document.querySelector('.tab.active');
            if (activeTab && activeTab.textContent.includes('IP-Tracking')) {
                loadIpStatistics();
                loadIpChanges();
            }
        }, 30000);
        
        // CSV-Download
        function downloadCsv(hours) {
        window.location.href = '/api/export/csv?hours=' + hours;
        }
    </script>
</body>
</html>
""";
    }
}