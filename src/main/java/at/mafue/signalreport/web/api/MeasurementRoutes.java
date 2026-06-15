package at.mafue.signalreport.web.api;

import at.mafue.signalreport.measurement.Measurement;
import at.mafue.signalreport.web.ErrorResponse;
import io.javalin.Javalin;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import at.mafue.signalreport.storage.H2MeasurementRepository;

public class MeasurementRoutes
{
    private MeasurementRoutes()
    {
    }

    public static void register(Javalin app, H2MeasurementRepository repository)
    {
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
    }
}
