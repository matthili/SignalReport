package at.mafue.signalreport.web;

import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.i18n.I18n;
import at.mafue.signalreport.web.api.SystemRoutes;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.HandlerType;
import io.javalin.http.UnauthorizedResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Zugriffskontrolle der Weboberflaeche. Greift nur bei aktivierter Authentifizierung;
 * ist sie aus, darf jeder alles.
 * <ol>
 *   <li><b>Anmeldepflicht</b>: Ohne gueltige Session (Cookie {@value #SESSION_COOKIE}) antworten
 *       API-Pfade mit 401, Seiten leiten zur Login-Seite um.</li>
 *   <li><b>Admin-Rechte</b>: Die Endpunkte in {@link #ADMIN_ONLY} (Einstellungen aendern,
 *       eigene DNS-Server verwalten, Ausfaelle aus der Wertung nehmen, Daten-Aufbereitung
 *       starten) darf nur eine Admin-Session aufrufen, eine User-Session bekommt 403. Lesen,
 *       Berichte, Exporte, DNS-Benchmark, "Dienste jetzt pruefen" und der Dark-Mode-Schalter
 *       bleiben fuer User erlaubt.</li>
 * </ol>
 * Die Admin-Pruefung haengt als eigener before-Filter an genau dem Pfad der Route. Javalin
 * vergleicht Filter- und Routenpfad mit derselben Logik (z. B. Schraegstrich am Ende), ein
 * anders geschriebener Pfad kann die Pruefung deshalb nicht umgehen. Die HTTP-Methode kommt
 * aus {@code ctx.method()}, das auch Javalin fuer die Routenwahl nutzt.
 * <p>
 * Die Admin-Aktionen im Sicherheits-Tab (Authentifizierung ein/aus, Admin-Passwort aendern)
 * stehen nicht in der Liste: Sie verlangen das Admin-Passwort selbst per Challenge-Response
 * (siehe {@code AuthRoutes}).
 */
public final class AccessControl
{
    private static final Logger logger = LoggerFactory.getLogger(AccessControl.class);

    public static final String SESSION_COOKIE = "SR_SESSION";
    private static final String ATTR_IS_ADMIN = "isAdmin";

    /** Ein Endpunkt aus HTTP-Methode und Routenpfad. */
    public record Endpoint(HandlerType method, String path)
    {
    }

    /** Endpunkte, die bei aktivierter Authentifizierung nur der Admin aufrufen darf. */
    public static final List<Endpoint> ADMIN_ONLY = List.of(
            // Messziele, Intervall, Maintenance-Fenster, Gateways, Kundendaten, Daten-Aufbereitung
            new Endpoint(HandlerType.POST, "/api/config/update"),
            // Sprache (gilt fuer alle, auch fuer PDF und CSV)
            new Endpoint(HandlerType.POST, "/api/config/language"),
            new Endpoint(HandlerType.POST, "/api/push/settings"),
            new Endpoint(HandlerType.POST, "/api/services/settings"),
            // Eigene DNS-Server und ausgeblendete mitgelieferte (der Benchmark selbst bleibt fuer User erlaubt)
            new Endpoint(HandlerType.POST, "/api/dns/settings"),
            new Endpoint(HandlerType.POST, "/api/outages/exclude"),
            new Endpoint(HandlerType.POST, "/api/dataprep/run-now"));

    private AccessControl()
    {
    }

    /**
     * Installiert Anmeldepflicht und Admin-Pruefung. Javalin fuehrt before-Filter in der
     * Reihenfolge ihrer Registrierung aus: nach dem Setup-Filter und vor den Routen aufrufen.
     */
    public static void register(Javalin app, SessionManager sessionManager)
    {
        // Anmeldepflicht (Session-basiert mit Challenge-Response Login)
        app.before(ctx ->
        {
        if (!authEnabled())
            {
            return; // Keine Authentifizierung erforderlich
            }

        String path = ctx.path();
        if (isPublic(path))
            {
            return;
            }

        // Session-Token aus Cookie lesen
        SessionManager.Session session = sessionManager.getSession(ctx.cookie(SESSION_COOKIE));
        if (session == null)
            {
            // Kein gueltiges Session-Token → zur Login-Seite umleiten
            if (path.startsWith("/api/"))
                {
                throw new UnauthorizedResponse(I18n.get("api.notLoggedIn"));
                }
            ctx.redirect("/login");
            return;
            }

        // Rolle fuer die Admin-Pruefung und die Darstellung der Hauptseite merken
        ctx.attribute(ATTR_IS_ADMIN, session.isAdmin());
        });

        // Admin-Pruefung: ein Filter je geschuetztem Pfad
        for (Endpoint endpoint : ADMIN_ONLY)
            {
            app.before(endpoint.path(), ctx ->
            {
            if (ctx.method() == endpoint.method() && !hasAdminRights(ctx))
                {
                logger.warn("{} {} von {} abgelehnt: nur der Admin darf das", ctx.method(), ctx.path(), ctx.ip());
                throw new ForbiddenResponse(I18n.get("api.adminOnly"));
                }
            });
            }
    }

    /**
     * true, wenn die Anfrage Admin-Rechte hat: Authentifizierung aus oder Admin-Session.
     * Fehlt die gemerkte Rolle (die Anfrage lief nicht durch die Anmeldepflicht), gilt sie
     * nicht als Admin.
     */
    public static boolean hasAdminRights(Context ctx)
    {
        return !authEnabled() || Boolean.TRUE.equals(ctx.attribute(ATTR_IS_ADMIN));
    }

    private static boolean authEnabled()
    {
        return Config.getInstance().getAuth().isEnabled();
    }

    // Login-Seite, Auth-Endpoints, Setup, der lokale Stopp-Endpunkt (nur Loopback, prueft
    // SystemRoutes selbst) und statische Ressourcen sind ohne Session erlaubt
    private static boolean isPublic(String path)
    {
        return path.equals("/login") || path.startsWith("/api/auth/")
                || path.equals("/setup") || path.equals("/api/setup/complete")
                || path.equals(SystemRoutes.SHUTDOWN_PATH)
                || path.endsWith(".png") || path.endsWith(".ico") || path.endsWith(".css")
                || path.endsWith(".js") || path.endsWith(".jpg") || path.endsWith(".svg");
    }
}
