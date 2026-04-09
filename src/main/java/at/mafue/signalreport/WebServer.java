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
    private final LoginPageRenderer loginPageRenderer = new LoginPageRenderer();
    private final SessionManager sessionManager = new SessionManager();
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

        // Setup-Seite, Login, Auth-Endpoints und statische Ressourcen sind immer erlaubt
        String path = ctx.path();
        if (path.equals("/setup") || path.equals("/api/setup/complete")
                || path.equals("/login") || path.startsWith("/api/auth/")
                || path.endsWith(".png") || path.endsWith(".ico") || path.endsWith(".css")
                || path.endsWith(".js") || path.endsWith(".jpg") || path.endsWith(".svg"))
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

        // Auth-Middleware (Session-basiert mit Challenge-Response Login)
        app.before(ctx ->
        {
        Config config = Config.getInstance();
        Config.AuthConfig auth = config.getAuth();

        if (!auth.isEnabled())
            {
            return; // Keine Authentifizierung erforderlich
            }

        // Login-Seite, Auth-Endpoints und statische Ressourcen sind ohne Session erlaubt
        String path = ctx.path();
        if (path.equals("/login") || path.startsWith("/api/auth/")
                || path.equals("/setup") || path.equals("/api/setup/complete")
                || path.endsWith(".png") || path.endsWith(".ico") || path.endsWith(".css")
                || path.endsWith(".js") || path.endsWith(".jpg") || path.endsWith(".svg"))
            {
            return;
            }

        // Session-Token aus Cookie lesen
        String token = ctx.cookie("SR_SESSION");
        SessionManager.Session session = sessionManager.getSession(token);

        if (session == null)
            {
            // Kein gueltiges Session-Token → zur Login-Seite umleiten
            if (path.startsWith("/api/"))
                {
                throw new io.javalin.http.UnauthorizedResponse("Nicht angemeldet");
                }
            ctx.redirect("/login");
            return;
            }

        // Admin-Rechte aus Session speichern
        ctx.attribute("isAdmin", session.isAdmin());
        });

        // Statische HTML-Seite (Root)
        app.get("/", ctx ->
        {
        ctx.html(htmlPageRenderer.render());
        });

        // Login-Seite
        app.get("/login", ctx ->
        {
        ctx.html(loginPageRenderer.render());
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
            String pdfFilename = "signalreport-" + HostIdentifier.getHostname() + "-"
                    + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH_mm_ss"))
                    + ".pdf";
            ctx.header("Content-Disposition", "attachment; filename=" + pdfFilename);
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

            for (Measurement m : measurements)
                {
                if (typeFilter != null && !typeFilter.equals(m.getType())) continue;

                csv.append(m.getTimestamp().atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
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
                }

            String timestamp = java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH_mm_ss"));
            String csvFilename = (exportAll ? "signalreport-complete" : "signalreport") + "-"
                    + HostIdentifier.getHostname() + "-" + timestamp + ".csv";
            ctx.contentType("text/csv");
            ctx.header("Content-Disposition", "attachment; filename=" + csvFilename);

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

        // Setup abschließen (empfängt vorgehashte Passwörter vom Client)
        app.post("/api/setup/complete", ctx ->
        {
        try
            {
            var body = ctx.bodyAsClass(java.util.Map.class);
            String adminPasswordHash = body.get("adminPasswordHash").toString();
            boolean enableAuth = Boolean.parseBoolean(body.get("enableAuth").toString());
            String userPasswordHash = body.get("userPasswordHash") != null ? body.get("userPasswordHash").toString() : "";

            if (adminPasswordHash.isEmpty())
                {
                ctx.status(400).result("Admin-Passwort-Hash fehlt!");
                return;
                }

            Config config = Config.getInstance();
            Config.SetupConfig setup = config.getSetup();
            Config.AuthConfig auth = config.getAuth();

            // Client sendet SHA-256(password) → direkt speichern
            setup.setAdminPasswordHash(adminPasswordHash);
            setup.setSetupCompleted(true);

            if (enableAuth)
                {
                auth.setUserPasswordHash(userPasswordHash);
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

        // Challenge-Response Auth-Endpoints
        app.get("/api/auth/nonce", ctx ->
        {
        String nonce = sessionManager.generateNonce();
        ctx.json(Map.of("nonce", nonce));
        });

        app.post("/api/auth/login", ctx ->
        {
        try
            {
            var body = ctx.bodyAsClass(java.util.Map.class);
            String username = body.get("username").toString().trim().toLowerCase();
            String nonce = body.get("nonce").toString();
            String challengeResponse = body.get("challengeResponse").toString();

            // Nonce validieren (Single-Use, 60s TTL)
            if (!sessionManager.validateNonce(nonce))
                {
                ctx.status(401).result("Ungueltige oder abgelaufene Anmeldung");
                return;
                }

            Config config = Config.getInstance();
            Config.AuthConfig auth = config.getAuth();

            String storedHash = null;
            String role = null;

            if ("admin".equals(username))
                {
                storedHash = config.getSetup().getAdminPasswordHash();
                role = "admin";
                }
            else if ("user".equals(username))
                {
                storedHash = auth.getUserPasswordHash();
                role = "user";
                }

            if (storedHash == null || storedHash.isEmpty())
                {
                ctx.status(401).result("Benutzername oder Passwort falsch");
                return;
                }

            // Challenge-Response verifizieren: SHA-256(storedHash + nonce) == challengeResponse
            if (!sessionManager.verifyChallengeResponse(storedHash, nonce, challengeResponse))
                {
                ctx.status(401).result("Benutzername oder Passwort falsch");
                return;
                }

            // Session erstellen
            String token = sessionManager.createSession(role);
            ctx.json(Map.of("token", token, "role", role));
            } catch (Exception e)
            {
            logger.error("Login-Fehler", e);
            ctx.status(500).result("Anmeldung fehlgeschlagen");
            }
        });

        app.post("/api/auth/logout", ctx ->
        {
        String token = ctx.cookie("SR_SESSION");
        sessionManager.invalidateSession(token);
        ctx.removeCookie("SR_SESSION", "/");
        ctx.status(200).result("Abgemeldet");
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
            String userPasswordHash = body.get("userPasswordHash").toString();
            String nonce = body.get("nonce").toString();
            String challengeResponse = body.get("challengeResponse").toString();

            // Admin-Identitaet per Challenge-Response verifizieren
            if (!sessionManager.validateNonce(nonce))
                {
                ctx.status(401).result("Ungueltige Anfrage");
                return;
                }

            Config config = Config.getInstance();
            String storedAdminHash = config.getSetup().getAdminPasswordHash();

            if (!sessionManager.verifyChallengeResponse(storedAdminHash, nonce, challengeResponse))
                {
                ctx.status(403).result("Falsches Admin-Passwort");
                return;
                }

            Config.AuthConfig auth = config.getAuth();

            // Client sendet SHA-256(userPassword) → direkt speichern
            auth.setUserPasswordHash(userPasswordHash);
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
            String nonce = body.get("nonce").toString();
            String challengeResponse = body.get("challengeResponse").toString();

            // Admin-Identitaet per Challenge-Response verifizieren
            if (!sessionManager.validateNonce(nonce))
                {
                ctx.status(401).result("Ungueltige Anfrage");
                return;
                }

            Config config = Config.getInstance();
            String storedAdminHash = config.getSetup().getAdminPasswordHash();

            if (!sessionManager.verifyChallengeResponse(storedAdminHash, nonce, challengeResponse))
                {
                ctx.status(403).result("Falsches Admin-Passwort");
                return;
                }

            Config.AuthConfig auth = config.getAuth();
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
            String nonce = body.get("nonce").toString();
            String challengeResponse = body.get("challengeResponse").toString();
            String newPasswordHash = body.get("newPasswordHash").toString();

            // Nonce und altes Passwort per Challenge-Response verifizieren
            if (!sessionManager.validateNonce(nonce))
                {
                ctx.status(401).result("Ungueltige Anfrage");
                return;
                }

            Config config = Config.getInstance();
            String storedHash = config.getSetup().getAdminPasswordHash();

            if (!sessionManager.verifyChallengeResponse(storedHash, nonce, challengeResponse))
                {
                ctx.status(403).result("Falsches aktuelles Admin-Passwort");
                return;
                }

            // Client sendet SHA-256(newPassword) → direkt speichern
            config.getSetup().setAdminPasswordHash(newPasswordHash);
            Config.save("config.json");
            ctx.status(200).result("Admin-Passwort geaendert");
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Admin-Passwort-Aenderungs-Fehler: " + e.getMessage()));
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