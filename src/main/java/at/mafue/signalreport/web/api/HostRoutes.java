package at.mafue.signalreport.web.api;

import at.mafue.signalreport.network.HostIdentifier;
import at.mafue.signalreport.network.NetworkInfo;
import at.mafue.signalreport.storage.HostInfo;
import at.mafue.signalreport.storage.IpChange;
import at.mafue.signalreport.storage.IpChangeStats;
import at.mafue.signalreport.web.ErrorResponse;
import io.javalin.Javalin;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import at.mafue.signalreport.storage.H2MeasurementRepository;

public class HostRoutes
{
    private HostRoutes()
    {
    }

    public static void register(Javalin app, H2MeasurementRepository repository)
    {
        // Host-Informationen API
        app.get("/api/hosts", ctx ->
        {
        try
            {
            List<HostInfo> hosts = repository.getAllHosts();
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

        // IP-Änderungen abrufen
        app.get("/api/ip-changes", ctx ->
        {
        try
            {
            int limit = ctx.queryParam("limit") != null
                    ? Integer.parseInt(ctx.queryParam("limit"))
                    : 50;

            List<IpChange> changes = repository.getIpChanges(limit);
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
            List<IpChangeStats> stats = repository.getIpChangeStatistics();
            ctx.json(stats);
            } catch (SQLException e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("IP-Statistik-Fehler: " + e.getMessage()));
            }
        });
    }
}
