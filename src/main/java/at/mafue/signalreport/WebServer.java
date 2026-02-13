package at.mafue.signalreport;

import io.javalin.Javalin;
import java.sql.SQLException;
import java.util.List;
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
                <script src="https://cdn.jsdelivr.net/npm/chart.js@3.9.1"></script>
                <style>
                    body { font-family: Arial, sans-serif; max-width: 900px; margin: 40px auto; padding: 20px; background: #f8f9fa; }
                    .header { text-align: center; margin-bottom: 30px; }
                    .header h1 { color: #0d6efd; }
                    .chart-container { width: 100%; height: 300px; margin-bottom: 30px; background: white; padding: 15px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
                    table { width: 100%; border-collapse: collapse; margin-top: 20px; background: white; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
                    th, td { padding: 12px 15px; text-align: left; border-bottom: 1px solid #ddd; }
                    th { background: #0d6efd; color: white; }
                    .success { color: #28a745; font-weight: bold; }
                    .failure { color: #dc3545; font-weight: bold; }
                    .footer { text-align: center; margin-top: 30px; color: #6c757d; font-size: 0.9em; }
                </style>
            </head>
            <body>
                <div class="header">
                    <h1>SignalReport</h1>
                    <p>Letzte Messungen der Internet-Qualität</p>
                </div>
                
                <div class="chart-container">
                    <canvas id="latencyChart"></canvas>
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
                        function loadMeasurements() {
                            fetch('/api/measurements?limit=20')
                                .then(response => response.json())
                                .then(data => {
                                    // Tabelle füllen
                                    const tableBody = document.getElementById('measurementsTable');
                                    tableBody.innerHTML = '';
                                    data.forEach(m => {
                                        const row = document.createElement('tr');
                                        const statusClass = m.success ? 'success' : 'failure';
                                        const statusText = m.success ? '+' : '-';
                                        row.innerHTML = `
                                            <td>${new Date(m.timestamp * 1000).toLocaleTimeString('de-DE')}</td>
                                            <td><strong>${m.type}</strong></td>
                                            <td>${m.target}</td>
                                            <td><strong>${m.latencyMs.toFixed(1)}</strong> ms</td>
                                            <td class="${statusClass}">${statusText}</td>
                                        `;
                                        tableBody.appendChild(row);
                                    });
                    
                                    // Chart neu erstellen (Chart.js v3)
                                    const pingData = data.filter(m => m.type === 'PING').slice(0, 10).reverse();
                                    const labels = pingData.map(m => new Date(m.timestamp * 1000).toLocaleTimeString('de-DE', {hour: '2-digit', minute:'2-digit'}));
                                    const values = pingData.map(m => m.latencyMs);
                    
                                    const ctx = document.getElementById('latencyChart').getContext('2d');
                    
                                    // Chart zerstören falls existiert und gültig
                                    if (window.latencyChart && typeof window.latencyChart.destroy === 'function') {
                                        window.latencyChart.destroy();
                                    }
                    
                                    // Neuen Chart erstellen
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
                                    document.getElementById('measurementsTable').innerHTML =\s
                                        '<tr><td colspan="5" style="text-align:center;color:red">Fehler beim Laden der Daten</td></tr>';
                                });
                        }
                    
                        // Initial laden
                        loadMeasurements();
                    
                        // Alle 5 Sekunden aktualisieren
                        setInterval(loadMeasurements, 5000);
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