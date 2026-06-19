package at.mafue.signalreport.web.api;

import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.config.ServiceReachabilityConfig;
import at.mafue.signalreport.config.ServiceTarget;
import at.mafue.signalreport.i18n.I18n;
import at.mafue.signalreport.report.ServiceReachabilityAssessment;
import at.mafue.signalreport.report.ServiceReachabilityAssessment.Verdict;
import at.mafue.signalreport.report.ServiceReachabilityReport;
import at.mafue.signalreport.storage.H2MeasurementRepository;
import at.mafue.signalreport.storage.ServiceCheck;
import at.mafue.signalreport.web.ErrorResponse;
import io.javalin.Javalin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Routen der Dienst-Erreichbarkeit (Sperr-/Zensur-Erkennung). Registriert von
 * {@code WebServer}; der "Jetzt pruefen"-Ausloeser wird als {@link LongSupplier}
 * injiziert, damit das web-Paket nicht auf das Root-Paket (Scheduler) zeigt.
 */
public class ServiceReachabilityRoutes
{
    private ServiceReachabilityRoutes()
    {
    }

    public static void register(Javalin app, H2MeasurementRepository repository, LongSupplier manualTrigger)
    {
        // Aktueller Status je aktivem Dienst (fuer die Kacheln/Ampel)
        app.get("/api/services", ctx ->
        {
        try
            {
            int days = parseIntOr(ctx.queryParam("days"), 30);
            Instant since = Instant.now().minusSeconds(days * 86400L);
            ServiceReachabilityConfig cfg = Config.getInstance().getServiceReachability();

            List<Map<String, Object>> services = new ArrayList<>();
            for (ServiceTarget t : cfg.getServices())
                {
                if (!t.isEnabled())
                    {
                    continue;
                    }
                List<ServiceCheck> checks = repository.findServiceChecksSince(t.getId(), since);
                services.add(statusOf(t, checks));
                }

            ctx.json(Map.of(
                    "enabled", cfg.isEnabled(),
                    "intervalMinutes", cfg.getIntervalMinutes(),
                    "periodDays", days,
                    "services", services
            ));
            } catch (Exception e)
            {
            ctx.status(500).json(new ErrorResponse("Services-Fehler: " + e.getMessage()));
            }
        });

        // Verlauf (Episoden) eines Dienstes -- die "ab 1. Maerz gesperrt"-Erzaehlung
        app.get("/api/services/history", ctx ->
        {
        try
            {
            String id = ctx.queryParam("id");
            if (id == null || id.isBlank())
                {
                ctx.status(400).json(new ErrorResponse(I18n.get("api.invalidRequest")));
                return;
                }
            int days = parseIntOr(ctx.queryParam("days"), 90);
            Instant since = Instant.now().minusSeconds(days * 86400L);

            List<ServiceCheck> checks = repository.findServiceChecksSince(id, since);
            ServiceReachabilityReport report = ServiceReachabilityReport.compute(id, checks);

            List<Map<String, Object>> episodes = new ArrayList<>();
            for (ServiceReachabilityReport.Episode e : report.getEpisodes())
                {
                Verdict v = parseVerdict(e.getVerdict());
                episodes.add(Map.of(
                        "verdict", e.getVerdict(),
                        "verdictText", I18n.get(ServiceReachabilityAssessment.verdictKey(v)),
                        "blocked", ServiceReachabilityAssessment.isBlocked(v),
                        "startEpoch", e.getStart().getEpochSecond(),
                        "endEpoch", e.getEnd().getEpochSecond(),
                        "durationSeconds", e.getDurationSeconds(),
                        "sampleCount", e.getSampleCount()
                ));
                }

            ctx.json(Map.of(
                    "id", id,
                    "periodDays", days,
                    "totalChecks", report.getTotalChecks(),
                    "reachablePercent", report.getReachablePercent(),
                    "currentVerdict", report.getCurrentVerdict(),
                    "episodes", episodes
            ));
            } catch (Exception e)
            {
            ctx.status(500).json(new ErrorResponse("Verlauf-Fehler: " + e.getMessage()));
            }
        });

        // Einstellungen abrufen (alle Dienste, inkl. deaktivierter)
        app.get("/api/services/settings", ctx ->
        {
        try
            {
            ServiceReachabilityConfig cfg = Config.getInstance().getServiceReachability();
            List<Map<String, Object>> services = new ArrayList<>();
            for (ServiceTarget t : cfg.getServices())
                {
                services.add(Map.of(
                        "id", t.getId(),
                        "displayName", t.getDisplayName(),
                        "domain", t.getDomain(),
                        "kind", t.getKind().name(),
                        "enabled", t.isEnabled()
                ));
                }
            ctx.json(Map.of(
                    "enabled", cfg.isEnabled(),
                    "intervalMinutes", cfg.getIntervalMinutes(),
                    "useControlSni", cfg.isUseControlSni(),
                    "services", services
            ));
            } catch (Exception e)
            {
            ctx.status(500).json(new ErrorResponse("Einstellungen-Fehler: " + e.getMessage()));
            }
        });

        // Einstellungen speichern (Hauptschalter, Intervall, Kontroll-SNI, Pro-Dienst-Schalter)
        app.post("/api/services/settings", ctx ->
        {
        try
            {
            var body = ctx.bodyAsClass(java.util.Map.class);
            ServiceReachabilityConfig cfg = Config.getInstance().getServiceReachability();

            if (body.get("enabled") != null)
                {
                cfg.setEnabled(Boolean.parseBoolean(body.get("enabled").toString()));
                }
            if (body.get("intervalMinutes") != null)
                {
                try
                    {
                    cfg.setIntervalMinutes(Integer.parseInt(body.get("intervalMinutes").toString()));
                    } catch (NumberFormatException ignore)
                    {
                    }
                }
            if (body.get("useControlSni") != null)
                {
                cfg.setUseControlSni(Boolean.parseBoolean(body.get("useControlSni").toString()));
                }

            // Pro-Dienst: nur das enabled-Flag anhand der id uebernehmen (Domain/Kind bleiben).
            if (body.get("services") instanceof List<?> incoming)
                {
                for (Object o : incoming)
                    {
                    if (!(o instanceof Map<?, ?> sm) || sm.get("id") == null)
                        {
                        continue;
                        }
                    String id = sm.get("id").toString();
                    boolean enabled = sm.get("enabled") != null && Boolean.parseBoolean(sm.get("enabled").toString());
                    for (ServiceTarget t : cfg.getServices())
                        {
                        if (t.getId().equals(id))
                            {
                            t.setEnabled(enabled);
                            }
                        }
                    }
                }

            Config.save("config.json");
            ctx.status(200).result(I18n.get("api.servicesSaved"));
            } catch (Exception e)
            {
            ctx.status(500).json(new ErrorResponse("Einstellungen-Speicher-Fehler: " + e.getMessage()));
            }
        });

        // Sofortige Pruefung ausloesen (mit Abkuehlphase). cooldownRemainingSeconds=0 -> gestartet.
        app.post("/api/services/check-now", ctx ->
        {
        try
            {
            long cooldown = manualTrigger.getAsLong();
            ctx.json(Map.of(
                    "started", cooldown == 0L,
                    "cooldownRemainingSeconds", cooldown
            ));
            } catch (Exception e)
            {
            ctx.status(500).json(new ErrorResponse("Check-Now-Fehler: " + e.getMessage()));
            }
        });
    }

    private static Map<String, Object> statusOf(ServiceTarget t, List<ServiceCheck> checks)
    {
        ServiceReachabilityReport report = ServiceReachabilityReport.compute(t.getId(), checks);
        Verdict v = parseVerdict(report.getCurrentVerdict());
        ServiceCheck last = checks.isEmpty() ? null : checks.get(checks.size() - 1);

        // LinkedHashMap statt Map.of, weil einzelne Werte (sinceEpoch, lastCheckEpoch) null sein duerfen.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("displayName", t.getDisplayName());
        m.put("kind", t.getKind().name());
        m.put("verdict", report.getCurrentVerdict());
        m.put("verdictText", I18n.get(ServiceReachabilityAssessment.verdictKey(v)));
        m.put("blocked", ServiceReachabilityAssessment.isBlocked(v));
        m.put("reachablePercent", report.getReachablePercent());
        m.put("totalChecks", report.getTotalChecks());
        m.put("sinceEpoch", report.getCurrentSince() != null ? report.getCurrentSince().getEpochSecond() : null);
        m.put("lastMethod", last != null ? last.getMethod() : "");
        m.put("lastCheckEpoch", last != null ? last.getTimestamp().getEpochSecond() : null);
        return m;
    }

    private static Verdict parseVerdict(String name)
    {
        try
            {
            return Verdict.valueOf(name);
            } catch (Exception e)
            {
            return Verdict.UNKNOWN;
            }
    }

    private static int parseIntOr(String s, int def)
    {
        if (s == null)
            {
            return def;
            }
        try
            {
            return Integer.parseInt(s);
            } catch (NumberFormatException e)
            {
            return def;
            }
    }
}
