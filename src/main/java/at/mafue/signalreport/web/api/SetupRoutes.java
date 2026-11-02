package at.mafue.signalreport.web.api;

import at.mafue.signalreport.config.AuthConfig;
import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.config.SetupConfig;
import at.mafue.signalreport.i18n.I18n;
import at.mafue.signalreport.web.ErrorResponse;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SetupRoutes
{
    private static final Logger logger = LoggerFactory.getLogger(SetupRoutes.class);

    // Zwei gleichzeitige Setup-Anfragen duerfen nicht beide durchkommen
    private static final Object SETUP_LOCK = new Object();

    private SetupRoutes()
    {
    }

    public static void register(Javalin app)
    {
        // Setup-Status prüfen
        app.get("/api/setup/status", ctx ->
        {
        Config config = Config.getInstance();
        ctx.json(Map.of(
                "setupCompleted", config.getSetup().isSetupCompleted()
        ));
        });

        // Setup abschließen (empfängt vorgehashte Passwörter vom Client).
        // Ohne Anmeldung erreichbar, deshalb nur einmal: danach antwortet der Endpunkt mit 403,
        // sonst koennte jeder im Netz das Admin-Passwort neu setzen.
        app.post("/api/setup/complete", ctx ->
        {
        synchronized (SETUP_LOCK)
            {
            completeSetup(ctx);
            }
        });
    }

    private static void completeSetup(Context ctx)
    {
        Config config = Config.getInstance();
        SetupConfig setup = config.getSetup();
        AuthConfig auth = config.getAuth();

        if (setup.isSetupCompleted())
            {
            logger.warn("Setup-Anfrage von {} abgelehnt: Einrichtung ist bereits abgeschlossen", ctx.ip());
            ctx.status(403).result(I18n.get("api.setupAlreadyDone"));
            return;
            }

        try
            {
            var body = ctx.bodyAsClass(java.util.Map.class);
            String adminPasswordHash = body.get("adminPasswordHash").toString();
            boolean enableAuth = Boolean.parseBoolean(body.get("enableAuth").toString());
            String userPasswordHash = body.get("userPasswordHash") != null ? body.get("userPasswordHash").toString() : "";

            if (adminPasswordHash.isEmpty())
                {
                ctx.status(400).result(I18n.get("api.adminHashMissing"));
                return;
                }

            // Client sendet SHA-256(password) → direkt speichern
            setup.setAdminPasswordHash(adminPasswordHash);
            setup.setSetupCompleted(true);

            if (enableAuth)
                {
                auth.setUserPasswordHash(userPasswordHash);
                auth.setEnabled(true);
                }

            Config.save("config.json");
            ctx.status(200).result(I18n.get("api.setupDone"));
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Setup-Fehler: " + e.getMessage()));
            }
    }
}
