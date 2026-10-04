package at.mafue.signalreport.web.api;

import at.mafue.signalreport.DataPrepScheduler;
import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.config.DataPrepConfig;
import at.mafue.signalreport.web.ErrorResponse;
import io.javalin.Javalin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Status und manueller Start der Daten-Aufbereitung (Einstellungs-Karte).
 * Die Einstellungen selbst werden ueber /api/config/update gespeichert (SettingsRoutes).
 */
public final class DataPrepRoutes
{
    private DataPrepRoutes()
    {
    }

    public static void register(Javalin app, DataPrepScheduler scheduler)
    {
        app.get("/api/dataprep/status", ctx ->
        {
        try
            {
            Map<String, Object> out = new LinkedHashMap<>();
            Config config = Config.getInstance();
            DataPrepConfig cfg = config != null ? config.getDataPrep() : new DataPrepConfig();
            out.put("enabled", cfg.isEnabled());
            out.put("retentionDays", cfg.getRetentionDays());
            out.put("useMaintenanceWindow", cfg.isUseMaintenanceWindow());
            out.put("windowStart", String.format("%02d:%02d", cfg.getStartHour(), cfg.getStartMinute()));
            out.put("windowEnd", String.format("%02d:%02d", cfg.getEndHour(), cfg.getEndMinute()));
            if (scheduler != null)
                {
                out.putAll(scheduler.status());
                } else
                {
                out.put("running", false);
                }
            ctx.json(out);
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Aufbereitungs-Status-Fehler: " + e.getMessage()));
            }
        });

        app.post("/api/dataprep/run-now", ctx ->
        {
        if (scheduler == null)
            {
            ctx.status(503);
            ctx.json(new ErrorResponse("Daten-Aufbereitung nicht verfuegbar"));
            return;
            }
        long cooldown = scheduler.triggerManualRun();
        ctx.json(Map.of("started", cooldown == 0L, "cooldownRemainingSeconds", cooldown));
        });
    }
}
