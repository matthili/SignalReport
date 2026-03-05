package at.mafue.signalreport;

import io.javalin.Javalin;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.sql.SQLException;
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

        //Host-Informationen API
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

        // 🔑 NEU: Alle verfügbaren DNS-Server
app.get("/api/dns/servers", ctx -> {
    try {
        Config config = Config.load("config.json");
        ctx.json(config.getDnsServers());
    } catch (IOException e) {
        ctx.status(500);
        ctx.json(new ErrorResponse("DNS-Server-Lade-Fehler: " + e.getMessage()));
    }
});

// 🔑 NEU: DNS-Benchmark ausführen
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

// 🔑 NEU: DNS-Statistik pro Region
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
    } catch (IOException e) {
        ctx.status(500);
        ctx.json(new ErrorResponse("DNS-Statistik-Fehler: " + e.getMessage()));
    }
});

        System.out.println("🌍 Web-Interface läuft unter: http://localhost:" + port);
    }

    // Hilfsklasse für JSON-Fehler
    private static class ErrorResponse {
        private final String error;
        ErrorResponse(String error) { this.error = error; }
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }

    // HTML-Seite erstellen
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
    
    <div class="footer">
        <p>SignalReport v1.0 • Daten aktualisieren sich automatisch</p>
    </div>
    
    <script>
        // Tab-Wechsel
        function showTab(tabId) {
            document.querySelectorAll('.tab-content').forEach(tab => tab.classList.remove('active'));
            document.querySelectorAll('.tab').forEach(tab => tab.classList.remove('active'));
            document.getElementById(tabId).classList.add('active');
            event.target.classList.add('active');
            
            if (tabId === 'hosts') {
                loadHosts();
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
    </script>
</body>
</html>
""";
}

}