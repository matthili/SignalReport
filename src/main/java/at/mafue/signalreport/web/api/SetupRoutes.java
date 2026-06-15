package at.mafue.signalreport.web.api;

import at.mafue.signalreport.config.AuthConfig;
import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.config.SetupConfig;
import at.mafue.signalreport.i18n.I18n;
import at.mafue.signalreport.web.ErrorResponse;
import io.javalin.Javalin;
import java.util.Map;

public class SetupRoutes
{
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
                ctx.status(400).result(I18n.get("api.adminHashMissing"));
                return;
                }

            Config config = Config.getInstance();
            SetupConfig setup = config.getSetup();
            AuthConfig auth = config.getAuth();

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
        });
    }
}
