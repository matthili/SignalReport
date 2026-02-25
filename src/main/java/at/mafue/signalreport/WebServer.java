package at.mafue.signalreport;

import io.javalin.Javalin;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

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
            ctx.html("""
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>SignalReport</title>
    <script src="https://cdn.jsdelivr.net/npm/chart.js@3.9.1/dist/chart.min.js"></script>
    <style>
        body { font-family: Arial, sans-serif; max-width: 900px; margin: 40px auto; padding: 20px; background: #f8f9fa; }
        .header { text-align: center; margin-bottom: 30px; }
        .header h1 { color: #0d6efd; }
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
        th, td { padding: 12px 15px; text-align: left; border-bottom: 1px solid #ddd; }
        th { background: #0d6efd; color: white; }
        .excellent { color: #198754; font-weight: bold; }
        .good { color: #ffc107; font-weight: bold; }
        .poor { color: #dc3545; font-weight: bold; }
        .failure { color: #6c757d; text-decoration: line-through; }
        .footer { text-align: center; margin-top: 30px; color: #6c757d; font-size: 0.9em; }
    </style>
</head>
<body>
    <div class="header">
        <h1>📡 SignalReport</h1>
        <p>Letzte Messungen der Internet-Qualität</p>
    </div>
    
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
    
<div style="display: flex; gap: 10px; margin: 25px 0;">
    <button onclick="downloadReport(24)" style="padding: 10px 15px; background: #0d6efd; color: white; border: none; border-radius: 5px; cursor: pointer;">
        📄 PDF-Bericht (letzte 24h)
    </button>
    <button onclick="downloadReport(168)" style="padding: 10px 15px; background: #6c757d; color: white; border: none; border-radius: 5px; cursor: pointer;">
        📄 PDF-Bericht (letzte 7 Tage)
    </button>
</div>
    
    <table>
        <thead>
            <tr>
                <th>Zeit</th>
                <th>Typ</th>
                <th>Ziel</th>
                <th>Latenz</th>
                <th>Status</th>
            </tr>
        </thead>
        <tbody id="measurementsTable">
            <tr><td colspan="5" style="text-align:center">Lade Daten...</td></tr>
        </tbody>
    </table>
    
    <div class="footer">
        <p>SignalReport v1.0 • Daten aktualisieren sich automatisch</p>
    </div>
    
    <script>
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
                    // Tabelle füllen
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
                        `;
                        tableBody.appendChild(row);
                    });
                    
                    // Chart vorbereiten (nur PING)
                    const pingData = data.filter(m => m.type === 'PING').slice(0, 10).reverse();
                    const labels = pingData.map(m => new Date(m.timestamp * 1000).toLocaleTimeString('de-DE', {hour: '2-digit', minute:'2-digit'}));
                    const values = pingData.map(m => m.latencyMs);
                    
                    const ctx = document.getElementById('latencyChart').getContext('2d');
                    
                    // Chart sicher zerstören
                    if (window.latencyChart && typeof window.latencyChart.destroy === 'function') {
                        window.latencyChart.destroy();
                    }
                    
                    // Chart erstellen – MIT KORREKTEM "data: vor curly-bracket und values"
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
                        '<tr><td colspan="5" style="text-align:center;color:red">Fehler beim Laden der Daten</td></tr>';
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

        // Initial laden
        loadStatistics();
        loadMeasurements();
        loadHourlyChart();
        
        // Alle 5 Sekunden aktualisieren
        setInterval(loadMeasurements, 5000);
        setInterval(loadStatistics, 30000);
        setInterval(loadHourlyChart, 300000);
        
        function downloadReport(hours) {
                                window.location.href = '/api/report?hours=' + hours;
                            }
    </script>
</body>
</html>
""");
        });

        // REST-API für Messungen (Jackson serialisiert automatisch)
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


        System.out.println("Web-Interface läuft unter: http://localhost:" + port);
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


}
