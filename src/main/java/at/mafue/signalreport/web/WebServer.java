package at.mafue.signalreport.web;

import at.mafue.signalreport.config.AuthConfig;
import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.i18n.I18n;
import at.mafue.signalreport.storage.H2MeasurementRepository;
import at.mafue.signalreport.web.api.AuthRoutes;
import at.mafue.signalreport.web.api.DnsRoutes;
import at.mafue.signalreport.web.api.ExportRoutes;
import at.mafue.signalreport.web.api.HostRoutes;
import at.mafue.signalreport.web.api.MeasurementRoutes;
import at.mafue.signalreport.web.api.PageRoutes;
import at.mafue.signalreport.web.api.ReliabilityRoutes;
import at.mafue.signalreport.web.api.ServiceReachabilityRoutes;
import at.mafue.signalreport.web.api.SettingsRoutes;
import at.mafue.signalreport.web.api.SetupRoutes;
import at.mafue.signalreport.web.view.HtmlPageRenderer;
import at.mafue.signalreport.web.view.LoginPageRenderer;
import at.mafue.signalreport.web.view.SetupPageRenderer;

import io.javalin.Javalin;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.LongSupplier;

public class WebServer
{
    private static final Logger logger = LoggerFactory.getLogger(WebServer.class);
    private final H2MeasurementRepository repository;
    private final LongSupplier reachabilityTrigger;
    private final HtmlPageRenderer htmlPageRenderer = new HtmlPageRenderer();
    private final SetupPageRenderer setupPageRenderer = new SetupPageRenderer();
    private final LoginPageRenderer loginPageRenderer = new LoginPageRenderer();
    private final SessionManager sessionManager = new SessionManager();
    private Javalin app;

    public WebServer(H2MeasurementRepository repository, LongSupplier reachabilityTrigger)
    {
        this.repository = repository;
        this.reachabilityTrigger = reachabilityTrigger;
    }

    public void start(int port)
    {
        // Jackson mit JavaTimeModule konfigurieren
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        app = Javalin.create(config ->
        {
        config.showJavalinBanner = false;
        config.jsonMapper(new io.javalin.json.JavalinJackson(objectMapper));
        config.staticFiles.add("/web");
        }).start(port);

        // Setup-Middleware: Prüfen, ob Setup abgeschlossen ist
        app.before(ctx ->
        {
        Config config = Config.getInstance();

        // Setup-Seite, Login, Auth-Endpoints und statische Ressourcen sind immer erlaubt
        String path = ctx.path();
        if (path.equals("/setup") || path.equals("/api/setup/complete")
                || path.equals("/login") || path.startsWith("/api/auth/")
                || path.endsWith(".png") || path.endsWith(".ico") || path.endsWith(".css")
                || path.endsWith(".js") || path.endsWith(".jpg") || path.endsWith(".svg"))
            {
            return;
            }

        // Wenn Setup nicht abgeschlossen → zur Setup-Seite umleiten
        if (!config.getSetup().isSetupCompleted())
            {
            ctx.redirect("/setup");
            return;
            }
        });

        // Auth-Middleware (Session-basiert mit Challenge-Response Login)
        app.before(ctx ->
        {
        Config config = Config.getInstance();
        AuthConfig auth = config.getAuth();

        if (!auth.isEnabled())
            {
            return; // Keine Authentifizierung erforderlich
            }

        // Login-Seite, Auth-Endpoints und statische Ressourcen sind ohne Session erlaubt
        String path = ctx.path();
        if (path.equals("/login") || path.startsWith("/api/auth/")
                || path.equals("/setup") || path.equals("/api/setup/complete")
                || path.endsWith(".png") || path.endsWith(".ico") || path.endsWith(".css")
                || path.endsWith(".js") || path.endsWith(".jpg") || path.endsWith(".svg"))
            {
            return;
            }

        // Session-Token aus Cookie lesen
        String token = ctx.cookie("SR_SESSION");
        SessionManager.Session session = sessionManager.getSession(token);

        if (session == null)
            {
            // Kein gueltiges Session-Token → zur Login-Seite umleiten
            if (path.startsWith("/api/"))
                {
                throw new io.javalin.http.UnauthorizedResponse(I18n.get("api.notLoggedIn"));
                }
            ctx.redirect("/login");
            return;
            }

        // Admin-Rechte aus Session speichern
        ctx.attribute("isAdmin", session.isAdmin());
        });

        // Routen registrieren (nach Domaene gruppiert, siehe Paket web.api)
        PageRoutes.register(app, htmlPageRenderer, setupPageRenderer, loginPageRenderer);
        MeasurementRoutes.register(app, repository);
        ReliabilityRoutes.register(app, repository);
        ExportRoutes.register(app, repository);
        HostRoutes.register(app, repository);
        DnsRoutes.register(app);
        SettingsRoutes.register(app);
        ServiceReachabilityRoutes.register(app, repository, reachabilityTrigger);
        SetupRoutes.register(app);
        AuthRoutes.register(app, sessionManager);

        logger.info("Web-Interface läuft unter: http://localhost:{}", port);
    }

    public void stop()
    {
        if (app != null)
            {
            app.stop();
            }
    }
}