package at.mafue.signalreport.web.api;

import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.config.GatewayConfig;
import at.mafue.signalreport.i18n.I18n;
import at.mafue.signalreport.measurement.Measurement;
import at.mafue.signalreport.network.GatewayDiscovery;
import at.mafue.signalreport.report.ConnectivityAssessment;
import at.mafue.signalreport.report.ReliabilityReport;
import at.mafue.signalreport.storage.Statistics;
import at.mafue.signalreport.web.ErrorResponse;
import io.javalin.Javalin;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import at.mafue.signalreport.storage.H2MeasurementRepository;

public class ReliabilityRoutes
{
    private ReliabilityRoutes()
    {
    }

    public static void register(Javalin app, H2MeasurementRepository repository)
    {
        // Stoerungs-Lokalisierung: vergleicht lokale Gateways mit dem Internet-Ping
        app.get("/api/connectivity", ctx ->
        {
        try
            {
            int hours = ctx.queryParam("hours") != null
                    ? Integer.parseInt(ctx.queryParam("hours"))
                    : 24;

            Config cfg = Config.getInstance();
            GatewayConfig gw = cfg.getGateway();

            Statistics nearStats = repository.calculateStatistics(GatewayDiscovery.TYPE_NEAR, hours);
            Statistics farStats = repository.calculateStatistics(GatewayDiscovery.TYPE_FAR, hours);
            Statistics netStats = repository.calculateStatistics("PING", hours);

            String nearIp = gw.getNear();
            String farIp = gw.getFar();
            boolean hasNearGw = nearIp != null && !nearIp.isBlank();
            boolean hasFarGw = farIp != null && !farIp.isBlank() && !farIp.equals(nearIp) && gw.isFarPingEnabled();

            ConnectivityAssessment.Verdict verdict = ConnectivityAssessment.assess(
                    hasNearGw ? nearStats : null,
                    hasFarGw ? farStats : null,
                    netStats);

            String nearLabel = !gw.getNearLabel().isBlank() ? gw.getNearLabel() : I18n.get("gateway.near");
            String farLabel = !gw.getFarLabel().isBlank() ? gw.getFarLabel() : I18n.get("gateway.far");

            java.util.List<java.util.Map<String, Object>> segments = new java.util.ArrayList<>();
            if (hasNearGw) segments.add(segment("near", nearLabel, nearIp, nearStats));
            if (hasFarGw) segments.add(segment("far", farLabel, farIp, farStats));
            segments.add(segment("internet", I18n.get("connectivity.internet"),
                    cfg.getMeasurement().getTargets().getPing(), netStats));

            ctx.json(Map.of(
                    "verdict", verdict.name(),
                    "verdictText", I18n.get(ConnectivityAssessment.verdictKey(verdict)),
                    "localChainKnown", hasNearGw,
                    "segments", segments
            ));
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Connectivity-Fehler: " + e.getMessage()));
            }
        });

        // Zuverlaessigkeit: Verfuegbarkeit, Abdeckung, Ausfaelle, MTBF, MTTR (lücken-sauber)
        app.get("/api/reliability", ctx ->
        {
        try
            {
            int hours = ctx.queryParam("hours") != null
                    ? Integer.parseInt(ctx.queryParam("hours"))
                    : 24;

            Config cfg = Config.getInstance();
            int interval = cfg.getMeasurement().getIntervalSeconds();
            long windowSeconds = hours * 3600L;
            Instant since = Instant.now().minusSeconds(windowSeconds);

            List<Measurement> all = repository.findSince(since);
            List<Measurement> ping = new java.util.ArrayList<>();
            int maintenanceSamples = 0;
            for (Measurement m : all)
                {
                if ("PING".equals(m.getType())) ping.add(m);
                else if (ReliabilityReport.TYPE_MAINTENANCE.equals(m.getType())) maintenanceSamples++;
                }

            ReliabilityReport r = ReliabilityReport.compute(ping, interval, windowSeconds, maintenanceSamples);

            // Ausfall-Liste (laengste zuerst) fuer die klickbare Detailansicht
            List<java.util.Map<String, Object>> outages = new java.util.ArrayList<>();
            r.getOutages().stream()
                    .sorted((a, b) -> Long.compare(b.getDurationSeconds(), a.getDurationSeconds()))
                    .forEach(o -> outages.add(Map.of(
                            "fromEpoch", o.getStart().getEpochSecond(),
                            "toEpoch", o.getEnd().getEpochSecond(),
                            "durationSeconds", o.getDurationSeconds(),
                            "sampleCount", o.getSampleCount(),
                            "excluded", o.isExcluded()
                    )));

            ctx.json(Map.of(
                    "hasData", r.hasData(),
                    "uptimePercent", r.getUptimePercent(),
                    "coveragePercent", r.getCoveragePercent(),
                    "outageCount", r.getOutageCount(),
                    "longestOutageSeconds", r.getLongestOutageSeconds(),
                    "mtbfSeconds", r.getMtbfSeconds(),
                    "mttrSeconds", r.getMttrSeconds(),
                    "periodHours", hours,
                    "outages", outages
            ));
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Reliability-Fehler: " + e.getMessage()));
            }
        });

        // Einen Ausfall (Zeitfenster) aus der Auswertung aus-/einschliessen
        app.post("/api/outages/exclude", ctx ->
        {
        try
            {
            var body = ctx.bodyAsClass(java.util.Map.class);
            long fromEpoch = Long.parseLong(body.get("fromEpoch").toString());
            long toEpoch = Long.parseLong(body.get("toEpoch").toString());
            boolean excluded = Boolean.parseBoolean(body.get("excluded").toString());

            // Die Grenzen kommen als ganze Sekunden; die letzte betroffene Messung kann
            // Sekundenbruchteile haben. Obergrenze um 1 s erweitern, damit sie sicher
            // mit erfasst wird (der naechste Messzyklus liegt mind. ein Intervall weiter).
            int affected = repository.excludeRange(
                    Instant.ofEpochSecond(fromEpoch),
                    Instant.ofEpochSecond(toEpoch).plusSeconds(1),
                    excluded);

            ctx.status(200).json(Map.of("affected", affected, "excluded", excluded));
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Ausschluss-Fehler: " + e.getMessage()));
            }
        });
    }

    // Baut einen Segment-Eintrag fuer die Connectivity-API
    private static java.util.Map<String, Object> segment(String key, String label, String ip,
                                                         Statistics s)
    {
        return Map.of(
                "key", key,
                "label", label,
                "ip", ip != null ? ip : "",
                "avgLatency", s.getAvgLatency(),
                "packetLoss", s.getPacketLossPercent(),
                "hasData", ConnectivityAssessment.hasData(s),
                "healthy", ConnectivityAssessment.isHealthy(s)
        );
    }
}
