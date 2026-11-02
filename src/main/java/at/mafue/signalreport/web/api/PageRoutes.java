package at.mafue.signalreport.web.api;

import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.i18n.I18n;
import at.mafue.signalreport.web.AccessControl;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import at.mafue.signalreport.web.view.HtmlPageRenderer;
import at.mafue.signalreport.web.view.LoginPageRenderer;
import at.mafue.signalreport.web.view.SetupPageRenderer;

public class PageRoutes
{
    private static final Logger logger = LoggerFactory.getLogger(PageRoutes.class);

    private PageRoutes()
    {
    }

    public static void register(Javalin app, HtmlPageRenderer htmlPageRenderer, SetupPageRenderer setupPageRenderer, LoginPageRenderer loginPageRenderer)
    {
        // Statische HTML-Seite (Root); User-Sessions sehen die Einstellungen nur lesend
        app.get("/", ctx ->
        {
        ctx.html(htmlPageRenderer.render(AccessControl.hasAdminRights(ctx)));
        });

        // Login-Seite
        app.get("/login", ctx ->
        {
        ctx.html(loginPageRenderer.render());
        });

        // Setup-Seite (mit optionalem Sprachwechsel via ?lang=xx). Nur bis zum Abschluss der
        // Einrichtung: die Seite ist ohne Anmeldung erreichbar, danach geht es zur Hauptseite.
        app.get("/setup", ctx ->
        {
        if (Config.getInstance().getSetup().isSetupCompleted())
            {
            ctx.redirect("/");
            return;
            }
        String lang = ctx.queryParam("lang");
        if (lang != null && I18n.isAvailable(lang))
            {
            Config config = Config.getInstance();
            config.setLanguage(lang);
            I18n.load(lang);
            try
                {
                Config.save("config.json");
                } catch (Exception e)
                {
                logger.error("Sprachwahl konnte nicht gespeichert werden: {}", e.getMessage());
                }
            }
        ctx.html(setupPageRenderer.render());
        });
    }
}
