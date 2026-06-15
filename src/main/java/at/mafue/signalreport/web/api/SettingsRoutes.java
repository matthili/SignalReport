package at.mafue.signalreport.web.api;

import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.config.GatewayConfig;
import at.mafue.signalreport.config.PushConfig;
import at.mafue.signalreport.i18n.I18n;
import at.mafue.signalreport.network.GatewayDiscovery;
import at.mafue.signalreport.web.ErrorResponse;
import io.javalin.Javalin;
import java.util.Map;

public class SettingsRoutes
{
    private SettingsRoutes()
    {
    }

    public static void register(Javalin app)
    {
        // Aktuelle Konfiguration abrufen
        app.get("/api/config/current", ctx ->
        {
        try
            {
            Config config = Config.getInstance();
            GatewayConfig gw = config.getGateway();
            boolean gwContainer = GatewayDiscovery.runningInContainer();
            boolean gwVirtualSuspected =
                    (!gw.isNearManual() && (GatewayDiscovery.isVirtualGatewayRange(gw.getNear()) || (gwContainer && GatewayDiscovery.isDockerLikeRange(gw.getNear()))))
                 || (!gw.isFarManual() && (GatewayDiscovery.isVirtualGatewayRange(gw.getFar()) || (gwContainer && GatewayDiscovery.isDockerLikeRange(gw.getFar()))));
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
                    ),
                    "gateway", Map.of(
                            "near", gw.getNear(),
                            "far", gw.getFar(),
                            "nearManual", gw.isNearManual(),
                            "farManual", gw.isFarManual(),
                            "nearPersistent", gw.isNearPersistent(),
                            "farPersistent", gw.isFarPersistent(),
                            "farPingEnabled", gw.isFarPingEnabled(),
                            "virtualSuspected", gwVirtualSuspected
                    ),
                    "language", config.getLanguage()
            ));
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Config-Lade-Fehler: " + e.getMessage()));
            }
        });

        // Sprache der Benutzeroberflaeche aendern
        app.post("/api/config/language", ctx ->
        {
        try
            {
            var body = ctx.bodyAsClass(java.util.Map.class);
            String language = body.get("language") != null ? body.get("language").toString() : null;

            if (!I18n.isAvailable(language))
                {
                ctx.status(400).result(I18n.get("api.invalidRequest"));
                return;
                }

            Config config = Config.getInstance();
            config.setLanguage(language);
            I18n.load(language);
            Config.save("config.json");

            ctx.status(200).result(I18n.get("api.languageSaved"));
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Sprach-Speicher-Fehler: " + e.getMessage()));
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

            // Gateway-Einstellungen (Phase 3): manuelle IPs, Persistenz, Fern-Ping
            boolean gwNearManual = false, gwFarManual = false;
            boolean gwNearPersistent = false, gwFarPersistent = false;
            boolean gwFarPingEnabled = true;
            String gwNear = "", gwFar = "";
            if (body.get("gateway") != null)
                {
                var g = (java.util.Map<String, Object>) body.get("gateway");
                if (g.get("nearManual") != null) gwNearManual = Boolean.parseBoolean(g.get("nearManual").toString());
                if (g.get("farManual") != null) gwFarManual = Boolean.parseBoolean(g.get("farManual").toString());
                if (g.get("nearPersistent") != null) gwNearPersistent = Boolean.parseBoolean(g.get("nearPersistent").toString());
                if (g.get("farPersistent") != null) gwFarPersistent = Boolean.parseBoolean(g.get("farPersistent").toString());
                if (g.get("farPingEnabled") != null) gwFarPingEnabled = Boolean.parseBoolean(g.get("farPingEnabled").toString());
                if (g.get("near") != null) gwNear = g.get("near").toString().trim();
                if (g.get("far") != null) gwFar = g.get("far").toString().trim();
                }

            Config currentConfig = Config.getInstance();
            currentConfig.updateTargets(ping, dns, http, interval);
            currentConfig.updateMaintenanceWindow(maintenanceEnabled, startHour, startMinute, endHour, endMinute);
            currentConfig.updateUserInfo(provider, customerId, userName);
            currentConfig.updateGateway(gwNearManual, gwNear, gwNearPersistent, gwFarManual, gwFar, gwFarPersistent, gwFarPingEnabled);

            Config.save("config.json");

            ctx.status(200);
            ctx.result(I18n.get("api.configSaved"));
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Config-Speicher-Fehler: " + e.getMessage()));
            }
        });

        // Push-Einstellungen abrufen
        app.get("/api/push/settings", ctx ->
        {
        Config config = Config.getInstance();
        PushConfig push = config.getPush();
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
            PushConfig push = config.getPush();
            push.setEnabled(enabled);
            push.setLatencyThreshold(threshold);
            push.setConsecutiveBadMeasurements(consecutive);

            Config.save("config.json");
            ctx.status(200).result(I18n.get("api.pushSaved"));
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
            ctx.status(200).result(I18n.get("api.themeSaved"));
            } catch (Exception e)
            {
            ctx.status(500).result("Fehler beim Speichern: " + e.getMessage());
            }
        });
    }
}
