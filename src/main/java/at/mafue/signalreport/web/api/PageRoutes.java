package at.mafue.signalreport.web.api;

import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.i18n.I18n;
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
        // Statische HTML-Seite (Root)
        app.get("/", ctx ->
        {
        ctx.html(htmlPageRenderer.render());
        });

        // Login-Seite
        app.get("/login", ctx ->
        {
        ctx.html(loginPageRenderer.render());
        });

        // Setup-Seite (mit optionalem Sprachwechsel via ?lang=xx)
        app.get("/setup", ctx ->
        {
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
