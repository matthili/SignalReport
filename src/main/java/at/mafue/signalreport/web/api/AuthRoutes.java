package at.mafue.signalreport.web.api;

import at.mafue.signalreport.config.AuthConfig;
import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.i18n.I18n;
import at.mafue.signalreport.web.ErrorResponse;
import io.javalin.Javalin;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import at.mafue.signalreport.web.SessionManager;

public class AuthRoutes
{
    private static final Logger logger = LoggerFactory.getLogger(AuthRoutes.class);

    private AuthRoutes()
    {
    }

    public static void register(Javalin app, SessionManager sessionManager)
    {
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
                ctx.status(401).result(I18n.get("api.invalidLogin"));
                return;
                }

            Config config = Config.getInstance();
            AuthConfig auth = config.getAuth();

            String storedHash = null;
            String role = null;

            if ("admin".equals(username))
                {
                storedHash = config.getSetup().getAdminPasswordHash();
                role = "admin";
                } else if ("user".equals(username))
                {
                storedHash = auth.getUserPasswordHash();
                role = "user";
                }

            if (storedHash == null || storedHash.isEmpty())
                {
                ctx.status(401).result(I18n.get("api.wrongCredentials"));
                return;
                }

            // Challenge-Response verifizieren: SHA-256(storedHash + nonce) == challengeResponse
            if (!sessionManager.verifyChallengeResponse(storedHash, nonce, challengeResponse))
                {
                ctx.status(401).result(I18n.get("api.wrongCredentials"));
                return;
                }

            // Session erstellen
            String token = sessionManager.createSession(role);
            ctx.json(Map.of("token", token, "role", role));
            } catch (Exception e)
            {
            logger.error("Login-Fehler", e);
            ctx.status(500).result(I18n.get("api.loginFailed"));
            }
        });

        app.post("/api/auth/logout", ctx ->
        {
        String token = ctx.cookie("SR_SESSION");
        sessionManager.invalidateSession(token);
        ctx.removeCookie("SR_SESSION", "/");
        ctx.status(200).result(I18n.get("api.loggedOut"));
        });

        // Authentifizierungs-Einstellungen
        app.get("/api/auth/status", ctx ->
        {
        Config config = Config.getInstance();
        AuthConfig auth = config.getAuth();
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
                ctx.status(401).result(I18n.get("api.invalidRequest"));
                return;
                }

            Config config = Config.getInstance();
            String storedAdminHash = config.getSetup().getAdminPasswordHash();

            if (!sessionManager.verifyChallengeResponse(storedAdminHash, nonce, challengeResponse))
                {
                ctx.status(403).result(I18n.get("api.wrongAdminPassword"));
                return;
                }

            AuthConfig auth = config.getAuth();

            // Client sendet SHA-256(userPassword) → direkt speichern
            auth.setUserPasswordHash(userPasswordHash);
            auth.setEnabled(true);

            Config.save("config.json");
            ctx.status(200).result(I18n.get("api.authEnabled"));
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
                ctx.status(401).result(I18n.get("api.invalidRequest"));
                return;
                }

            Config config = Config.getInstance();
            String storedAdminHash = config.getSetup().getAdminPasswordHash();

            if (!sessionManager.verifyChallengeResponse(storedAdminHash, nonce, challengeResponse))
                {
                ctx.status(403).result(I18n.get("api.wrongAdminPassword"));
                return;
                }

            AuthConfig auth = config.getAuth();
            auth.setEnabled(false);
            Config.save("config.json");

            ctx.status(200).result(I18n.get("api.authDisabled"));
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
                ctx.status(401).result(I18n.get("api.invalidRequest"));
                return;
                }

            Config config = Config.getInstance();
            String storedHash = config.getSetup().getAdminPasswordHash();

            if (!sessionManager.verifyChallengeResponse(storedHash, nonce, challengeResponse))
                {
                ctx.status(403).result(I18n.get("api.wrongCurrentAdminPassword"));
                return;
                }

            // Client sendet SHA-256(newPassword) → direkt speichern
            config.getSetup().setAdminPasswordHash(newPasswordHash);
            Config.save("config.json");
            ctx.status(200).result(I18n.get("api.adminPasswordChanged"));
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("Admin-Passwort-Aenderungs-Fehler: " + e.getMessage()));
            }
        });
    }
}
