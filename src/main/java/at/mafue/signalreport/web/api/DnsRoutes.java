package at.mafue.signalreport.web.api;

import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.config.DnsServer;
import at.mafue.signalreport.measurement.DnsBenchmark;
import at.mafue.signalreport.web.ErrorResponse;
import io.javalin.Javalin;
import java.util.List;
import java.util.Map;

public class DnsRoutes
{
    private DnsRoutes()
    {
    }

    public static void register(Javalin app)
    {
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
                            .map(DnsServer::getRegion)
                            .distinct()
                            .toList(),
                    "providers", config.getDnsServers().stream()
                            .map(DnsServer::getProvider)
                            .distinct()
                            .toList()
            ));
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("DNS-Statistik-Fehler: " + e.getMessage()));
            }
        });
    }
}
