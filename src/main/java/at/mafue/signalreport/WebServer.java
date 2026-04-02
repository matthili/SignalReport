package at.mafue.signalreport;

import io.javalin.Javalin;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class WebServer
{
    private static final Logger logger = LoggerFactory.getLogger(WebServer.class);
    private final H2MeasurementRepository repository;
    private final HtmlPageRenderer htmlPageRenderer = new HtmlPageRenderer();
    private final SetupPageRenderer setupPageRenderer = new SetupPageRenderer();
    private Javalin app;

    public WebServer(H2MeasurementRepository repository)
    {
        this.repository = repository;
    }

    public void start(int port)
    {
        // Jackson mit JavaTimeModule konfigurieren
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        app = Javalin.create(config ->
        {
        config.showJavalinBanner = false;
        config.jsonMapper(new io.javalin.json.JavalinJackson(objectMapper));
        config.staticFiles.add("/web");
        }).start(port);

        // Setup-Middleware: Prüfen, ob Setup abgeschlossen ist
        app.before(ctx ->
        {
        Config config = Config.getInstance();

        // Setup-Seite und Login sind immer erlaubt
        String path = ctx.path();
        if (path.equals("/setup") || path.equals("/api/setup/complete"))
            {
            return;
            }

        // Wenn Setup nicht abgeschlossen → zur Setup-Seite umleiten
        if (!config.getSetup().isSetupCompleted())
            {
            ctx.redirect("/setup");
            return;
            }
        });

        // Auth-Middleware
        app.before(ctx ->
        {
        Config config = Config.getInstance();
        Config.AuthConfig auth = config.getAuth();

        if (!auth.isEnabled())
            {
            return; // Keine Authentifizierung erforderlich
            }

        String authHeader = ctx.header("Authorization");
        if (authHeader == null || !authHeader.startsWith("Basic "))
            {
            ctx.status(401).header("WWW-Authenticate", "Basic realm=\"SignalReport\"");
            throw new RuntimeException("Unauthorized");
            }

        // Decode Basic Auth
        String credentials = new String(java.util.Base64.getDecoder()
                .decode(authHeader.substring(6)));
        String[] parts = credentials.split(":", 2);

        if (parts.length != 2)
            {
            ctx.status(401);
            throw new RuntimeException("Unauthorized");
            }

        String username = parts[0];
        String password = parts[1];

        // Admin oder User?
        boolean isAdmin = username.equals("admin") && auth.verifyAdminPassword(password);
        boolean isUser = username.equals("user") && auth.verifyUserPassword(password);

        if (!isAdmin && !isUser)
            {
            ctx.status(401);
            throw new RuntimeException("Unauthorized");
            }

        // Admin-Rechte speichern für spätere API-Checks
        ctx.attribute("isAdmin", isAdmin);
        });

        // Statische HTML-Seite (Root)
        app.get("/", ctx ->
        {
        ctx.html(htmlPageRenderer.render());
        });

        // Setup-Seite
        app.get("/setup", ctx ->
        {
        ctx.html(setupPageRenderer.render());
        });

        // REST-API für Messungen
        app.get("/api/measurements", ctx ->
        {
        try
            {
            int limit = ctx.queryParam("limit") != null
                    ? Integer.parseInt(ctx.queryParam("limit"))
                    : 10;

            List<Measurement> measurements = repository.findLastN(limit);
            ctx.json(measurements);
            } catch (NumberFormatException e)
            {
            ctx.status(400);
            ctx.json(new ErrorResponse("Ungültiger Limit-Parameter"));
            } catch (SQLException e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Datenbankfehler: " + e.getMessage()));
            }
        });

        // Statistik-API
        app.get("/api/statistics", ctx ->
        {
        try
            {
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
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Statistik-Fehler: " + e.getMessage()));
            }
        });

        // Stunden-basierte Durchschnittswerte (für Heatmap)
        app.get("/api/hourly-averages", ctx ->
        {
        try
            {
            int days = ctx.queryParam("days") != null
                    ? Integer.parseInt(ctx.queryParam("days"))
                    : 7;

            String type = ctx.queryParam("type") != null
                    ? ctx.queryParam("type")
                    : "PING";

            var averages = repository.calculateHourlyAverages(type, days);
            ctx.json(averages);
            } catch (NumberFormatException e)
            {
            ctx.status(400);
            ctx.json(new ErrorResponse("Ungültiger Parameter: " + e.getMessage()));
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Stunden-Daten-Fehler: " + e.getMessage()));
            }
        });

        // PDF-Bericht generieren
        app.get("/api/report", ctx ->
        {
        try
            {
            int hours = ctx.queryParam("hours") != null
                    ? Integer.parseInt(ctx.queryParam("hours"))
                    : 24;

            PdfReportGenerator generator = new PdfReportGenerator(repository);
            byte[] pdfBytes = generator.generateReport(hours);

            ctx.contentType("application/pdf");
            ctx.header("Content-Disposition", "attachment; filename=signalreport-" +
                    java.time.LocalDate.now() + ".pdf");
            ctx.result(pdfBytes);
            } catch (NumberFormatException e)
            {
            ctx.status(400);
            ctx.json(new ErrorResponse("Ungültiger Stunden-Parameter"));
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("PDF-Generierungsfehler: " + e.getMessage()));
            }
        });

        // CSV-Export
        app.get("/api/export/csv", ctx ->
        {
        try
            {
            String allParam = ctx.queryParam("all");
            int hours = 24;
            boolean exportAll = "true".equalsIgnoreCase(allParam);

            if (!exportAll && ctx.queryParam("hours") != null)
                {
                hours = Integer.parseInt(ctx.queryParam("hours"));
                }

            String typeFilter = ctx.queryParam("type");

            List<Measurement> measurements;
            if (exportAll)
                {
                measurements = repository.findAll();
                } else
                {
                Instant cutoff = Instant.now().minusSeconds(hours * 3600L);
                measurements = repository.findSince(cutoff);
                }

            StringBuilder csv = new StringBuilder();
            csv.append("timestamp;type;target;latency_ms;success;local_ipv4;local_ipv6;external_ipv4;external_ipv6;host_hash\n");

            int count = 0;

            for (Measurement m : measurements)
                {
                if (typeFilter != null && !typeFilter.equals(m.getType())) continue;

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
                }

            String filenamePrefix = exportAll ? "signalreport-complete" : "signalreport-" + java.time.LocalDate.now();
            ctx.contentType("text/csv");
            ctx.header("Content-Disposition",
                    "attachment; filename=" + filenamePrefix + "-" +
                            java.time.LocalTime.now().toString().replace(":", "") + ".csv");

            ctx.result(csv.toString());
            } catch (NumberFormatException e)
            {
            ctx.status(400);
            ctx.json(new ErrorResponse("Ungültiger Parameter"));
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("CSV-Export-Fehler: " + e.getMessage()));
            }
        });

        // Host-Informationen API
        app.get("/api/hosts", ctx ->
        {
        try
            {
            List<H2MeasurementRepository.HostInfo> hosts = repository.getAllHosts();
            ctx.json(hosts);
            } catch (SQLException e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Host-Daten-Fehler: " + e.getMessage()));
            }
        });

        // Host-Details für aktuellen Host
        app.get("/api/host/current", ctx ->
        {
        try
            {
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
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Host-Info-Fehler: " + e.getMessage()));
            }
        });

        // Alle verfügbaren DNS-Server
        app.get("/api/dns/servers", ctx ->
        {
        try
            {
            Config config = Config.load("config.json");
            ctx.json(config.getDnsServers());
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("DNS-Server-Lade-Fehler: " + e.getMessage()));
            }
        });

        // DNS-Benchmark ausführen
        app.post("/api/dns/benchmark", ctx ->
        {
        try
            {
            Config config = Config.load("config.json");
            String hostname = ctx.queryParam("hostname") != null
                    ? ctx.queryParam("hostname")
                    : "google.com";

            DnsBenchmark benchmark = new DnsBenchmark(config.getDnsServers(), hostname, 5000);
            List<DnsBenchmark.DnsResult> results = benchmark.benchmark();

            results.sort((a, b) -> Double.compare(a.getLatencyMs(), b.getLatencyMs()));

            ctx.json(results);
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("DNS-Benchmark-Fehler: " + e.getMessage()));
            }
        });

        // DNS-Statistik pro Region
        app.get("/api/dns/statistics", ctx ->
        {
        try
            {
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
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("DNS-Statistik-Fehler: " + e.getMessage()));
            }
        });

        // Aktuelle Konfiguration abrufen
        app.get("/api/config/current", ctx ->
        {
        try
            {
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
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Config-Lade-Fehler: " + e.getMessage()));
            }
        });

        // Konfiguration aktualisieren + speichern
        app.post("/api/config/update", ctx ->
        {
        try
            {
            var body = ctx.bodyAsClass(java.util.Map.class);

            String ping = "8.8.8.8";
            String dns = "google.com";
            String http = "https://example.com";
            int interval = 10;

            if (body.get("ping") != null) ping = body.get("ping").toString().trim();
            if (body.get("dns") != null) dns = body.get("dns").toString().trim();
            if (body.get("http") != null) http = body.get("http").toString().trim();
            if (body.get("intervalSeconds") != null)
                {
                try
                    {
                    interval = Integer.parseInt(body.get("intervalSeconds").toString());
                    } catch (NumberFormatException e)
                    {
                    interval = 10;
                    }
                }

            boolean maintenanceEnabled = false;
            int startHour = 4, startMinute = 0, endHour = 4, endMinute = 10;

            if (body.get("maintenance") != null)
                {
                var maintenance = (java.util.Map<String, Object>) body.get("maintenance");
                if (maintenance.get("enabled") != null)
                    {
                    maintenanceEnabled = Boolean.parseBoolean(maintenance.get("enabled").toString());
                    }
                if (maintenance.get("startHour") != null)
                    {
                    try
                        {
                        startHour = Integer.parseInt(maintenance.get("startHour").toString());
                        } catch (Exception e)
                        {
                        }
                    }
                if (maintenance.get("startMinute") != null)
                    {
                    try
                        {
                        startMinute = Integer.parseInt(maintenance.get("startMinute").toString());
                        } catch (Exception e)
                        {
                        }
                    }
                if (maintenance.get("endHour") != null)
                    {
                    try
                        {
                        endHour = Integer.parseInt(maintenance.get("endHour").toString());
                        } catch (Exception e)
                        {
                        }
                    }
                if (maintenance.get("endMinute") != null)
                    {
                    try
                        {
                        endMinute = Integer.parseInt(maintenance.get("endMinute").toString());
                        } catch (Exception e)
                        {
                        }
                    }
                }

            String provider = "";
            String customerId = "";
            String userName = "";

            if (body.get("userInfo") != null)
                {
                var userInfo = (java.util.Map<String, Object>) body.get("userInfo");
                if (userInfo.get("provider") != null) provider = userInfo.get("provider").toString().trim();
                if (userInfo.get("customerId") != null) customerId = userInfo.get("customerId").toString().trim();
                if (userInfo.get("userName") != null) userName = userInfo.get("userName").toString().trim();
                }

            Config currentConfig = Config.getInstance();
            currentConfig.updateTargets(ping, dns, http, interval);
            currentConfig.updateMaintenanceWindow(maintenanceEnabled, startHour, startMinute, endHour, endMinute);
            currentConfig.updateUserInfo(provider, customerId, userName);

            Config.save("config.json");

            ctx.status(200);
            ctx.result("Konfiguration erfolgreich aktualisiert und gespeichert!");
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Config-Speicher-Fehler: " + e.getMessage()));
            }
        });

        // IP-Änderungen abrufen
        app.get("/api/ip-changes", ctx ->
        {
        try
            {
            int limit = ctx.queryParam("limit") != null
                    ? Integer.parseInt(ctx.queryParam("limit"))
                    : 50;

            List<H2MeasurementRepository.IpChange> changes = repository.getIpChanges(limit);
            ctx.json(changes);
            } catch (NumberFormatException e)
            {
            ctx.status(400);
            ctx.json(new ErrorResponse("Ungültiger Limit-Parameter"));
            } catch (SQLException e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("IP-Änderungen-Fehler: " + e.getMessage()));
            }
        });

        // IP-Wechsel-Statistik pro Host
        app.get("/api/ip-statistics", ctx ->
        {
        try
            {
            List<H2MeasurementRepository.IpChangeStats> stats = repository.getIpChangeStatistics();
            ctx.json(stats);
            } catch (SQLException e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("IP-Statistik-Fehler: " + e.getMessage()));
            }
        });

        // Setup-Status prüfen
        app.get("/api/setup/status", ctx ->
        {
        Config config = Config.getInstance();
        ctx.json(Map.of(
                "setupCompleted", config.getSetup().isSetupCompleted()
        ));
        });

        // Setup abschließen (Admin-Passwort setzen)
        app.post("/api/setup/complete", ctx ->
        {
        try
            {
            var body = ctx.bodyAsClass(java.util.Map.class);
            String adminPassword = body.get("adminPassword").toString();
            boolean enableAuth = Boolean.parseBoolean(body.get("enableAuth").toString());
            String userPassword = body.get("userPassword") != null ? body.get("userPassword").toString() : "";

            if (adminPassword.length() < 6)
                {
                ctx.status(400).result("Admin-Passwort muss mindestens 6 Zeichen lang sein!");
                return;
                }

            if (enableAuth && userPassword.length() < 6)
                {
                ctx.status(400).result("User-Passwort muss mindestens 6 Zeichen lang sein!");
                return;
                }

            Config config = Config.getInstance();
            Config.SetupConfig setup = config.getSetup();
            Config.AuthConfig auth = config.getAuth();

            setup.setAdminPasswordHash(Config.hashPassword(adminPassword));
            setup.setSetupCompleted(true);

            if (enableAuth)
                {
                auth.setUserPasswordHash(Config.hashPassword(userPassword));
                auth.setEnabled(true);
                }

            Config.save("config.json");
            ctx.status(200).result("Setup abgeschlossen!");
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Setup-Fehler: " + e.getMessage()));
            }
        });

        // Authentifizierungs-Einstellungen
        app.get("/api/auth/status", ctx ->
        {
        Config config = Config.getInstance();
        Config.AuthConfig auth = config.getAuth();
        ctx.json(Map.of(
                "enabled", auth.isEnabled(),
                "hasUserPassword", !auth.getUserPasswordHash().isEmpty()
        ));
        });

        app.post("/api/auth/enable", ctx ->
        {
        try
            {
            var body = ctx.bodyAsClass(java.util.Map.class);
            String adminPassword = body.get("adminPassword").toString();
            String userPassword = body.get("userPassword").toString();

            Config config = Config.getInstance();
            Config.AuthConfig auth = config.getAuth();

            if (!auth.verifyAdminPassword(adminPassword))
                {
                ctx.status(403).result("Falsches Admin-Passwort");
                return;
                }

            auth.setUserPasswordHash(Config.hashPassword(userPassword));
            auth.setEnabled(true);

            Config.save("config.json");
            ctx.status(200).result("Authentifizierung aktiviert");
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Auth-Aktivierungs-Fehler: " + e.getMessage()));
            }
        });

        app.post("/api/auth/disable", ctx ->
        {
        try
            {
            var body = ctx.bodyAsClass(java.util.Map.class);
            String adminPassword = body.get("adminPassword").toString();

            Config config = Config.getInstance();
            Config.AuthConfig auth = config.getAuth();

            if (!auth.verifyAdminPassword(adminPassword))
                {
                ctx.status(403).result("Falsches Admin-Passwort");
                return;
                }

            auth.setEnabled(false);
            Config.save("config.json");
            ctx.status(200).result("Authentifizierung deaktiviert");
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Auth-Deaktivierungs-Fehler: " + e.getMessage()));
            }
        });

        app.post("/api/auth/change-admin", ctx ->
        {
        try
            {
            var body = ctx.bodyAsClass(java.util.Map.class);
            String oldPassword = body.get("oldPassword").toString();
            String newPassword = body.get("newPassword").toString();

            Config config = Config.getInstance();
            Config.AuthConfig auth = config.getAuth();

            if (!auth.verifyAdminPassword(oldPassword))
                {
                ctx.status(403).result("Falsches aktuelles Admin-Passwort");
                return;
                }

            auth.setAdminPasswordHash(Config.hashPassword(newPassword));
            Config.save("config.json");
            ctx.status(200).result("Admin-Passwort geändert");
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Admin-Passwort-Änderungs-Fehler: " + e.getMessage()));
            }
        });

        // Push-Einstellungen abrufen
        app.get("/api/push/settings", ctx ->
        {
        Config config = Config.getInstance();
        Config.PushConfig push = config.getPush();
        ctx.json(Map.of(
                "enabled", push.isEnabled(),
                "latencyThreshold", push.getLatencyThreshold(),
                "consecutiveBadMeasurements", push.getConsecutiveBadMeasurements()
        ));
        });

        // Push-Einstellungen speichern
        app.post("/api/push/settings", ctx ->
        {
        try
            {
            var body = ctx.bodyAsClass(java.util.Map.class);
            boolean enabled = Boolean.parseBoolean(body.get("enabled").toString());
            double threshold = Double.parseDouble(body.get("latencyThreshold").toString());
            int consecutive = Integer.parseInt(body.get("consecutiveBadMeasurements").toString());

            Config config = Config.getInstance();
            Config.PushConfig push = config.getPush();
            push.setEnabled(enabled);
            push.setLatencyThreshold(threshold);
            push.setConsecutiveBadMeasurements(consecutive);

            Config.save("config.json");
            ctx.status(200).result("Push-Einstellungen gespeichert");
            } catch (Exception e)
            {
            ctx.status(500).result("Fehler beim Speichern: " + e.getMessage());
            }
        });

        // Theme-Einstellungen (Dark Mode)
        app.get("/api/theme/settings", ctx ->
        {
        Config config = Config.getInstance();
        ctx.json(Map.of("darkMode", config.getTheme().isDarkMode()));
        });

        app.post("/api/theme/settings", ctx ->
        {
        try
            {
            var body = ctx.bodyAsClass(java.util.Map.class);
            boolean darkMode = Boolean.parseBoolean(body.get("darkMode").toString());
            Config config = Config.getInstance();
            config.getTheme().setDarkMode(darkMode);
            Config.save("config.json");
            ctx.status(200).result("Theme gespeichert");
            } catch (Exception e)
            {
            ctx.status(500).result("Fehler beim Speichern: " + e.getMessage());
            }
        });

        logger.info("🌍 Web-Interface läuft unter: http://localhost:{}", port);
    }

    // Hilfsklasse für JSON-Fehler
    public static class ErrorResponse
    {
        public final String error;

        public ErrorResponse(String error)
        {
            this.error = error;
        }
    }

    // CSV-Escaping-Hilfsfunktion
    private String escapeCsv(String value)
    {
        if (value == null || value.isEmpty() || value.equals("unknown"))
            {
            return "";
            }
        if (value.contains(";") || value.contains("\n") || value.contains("\""))
            {
            return "\"" + value.replace("\"", "\"\"") + "\"";
            }
        return value;
    }


    public void stop()
    {
        if (app != null)
            {
            app.stop();
            }
    }
}