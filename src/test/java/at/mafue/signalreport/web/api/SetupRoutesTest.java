package at.mafue.signalreport.web.api;

import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.i18n.I18n;
import at.mafue.signalreport.web.view.HtmlPageRenderer;
import at.mafue.signalreport.web.view.LoginPageRenderer;
import at.mafue.signalreport.web.view.SetupPageRenderer;
import io.javalin.Javalin;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Die Einrichtung ist ohne Anmeldung erreichbar und darf deshalb nur einmal laufen: Nach dem
 * Abschluss lehnt {@code POST /api/setup/complete} ab (403, Admin-Passwort unveraendert) und
 * {@code /setup} leitet zur Hauptseite um, ohne die Sprache umzustellen. Echter Javalin auf
 * Zufallsport; der Config-Singleton wird gesichert und zurueckgesetzt. Kein Testfall erreicht
 * {@code Config.save()}, es entsteht also keine config.json.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SetupRoutesTest
{
    private final HttpClient client = HttpClient.newHttpClient();
    private Javalin app;
    private Config previousConfig;
    private String originalAdminHash;

    @BeforeAll
    void start() throws Exception
    {
        previousConfig = replaceConfigInstance(null);
        I18n.load("de");
        Config config = Config.createDefault();
        config.setLanguage("de");
        originalAdminHash = Config.hashPassword("echtes-admin-passwort");
        config.getSetup().setAdminPasswordHash(originalAdminHash);

        app = Javalin.create(c -> c.showJavalinBanner = false);
        SetupRoutes.register(app);
        PageRoutes.register(app, new HtmlPageRenderer(), new SetupPageRenderer(), new LoginPageRenderer());
        app.start(0);
    }

    @AfterAll
    void stop() throws Exception
    {
        if (app != null)
            {
            app.stop();
            }
        replaceConfigInstance(previousConfig);
    }

    @BeforeEach
    void setupIsCompleted()
    {
        Config.getInstance().getSetup().setSetupCompleted(true);
    }

    @Test
    void testCompletingTheSetupAgainIsRejected() throws Exception
    {
        String body = "{\"adminPasswordHash\":\"" + Config.hashPassword("angreifer") + "\","
                + "\"enableAuth\":\"true\",\"userPasswordHash\":\"" + Config.hashPassword("x") + "\"}";

        HttpResponse<String> resp = post("/api/setup/complete", body);

        assertEquals(403, resp.statusCode(), resp.body());
        assertEquals(I18n.get("api.setupAlreadyDone"), resp.body());
        Config config = Config.getInstance();
        assertEquals(originalAdminHash, config.getSetup().getAdminPasswordHash(), "Admin-Passwort darf sich nicht aendern");
        assertFalse(config.getAuth().isEnabled(), "Authentifizierung darf sich nicht aendern");
        assertTrue(config.getAuth().getUserPasswordHash().isEmpty(), "User-Passwort darf sich nicht aendern");
    }

    @Test
    void testRejectionDoesNotDependOnTheBody() throws Exception
    {
        assertEquals(403, post("/api/setup/complete", "").statusCode());
        assertEquals(403, post("/api/setup/complete", "{}").statusCode());
    }

    @Test
    void testBeforeCompletionTheSetupStillValidatesTheRequest() throws Exception
    {
        // Vor dem Abschluss arbeitet der Endpunkt wie bisher (hier: Pruefung des Admin-Hashes,
        // die vor dem Speichern abbricht)
        Config.getInstance().getSetup().setSetupCompleted(false);

        HttpResponse<String> resp = post("/api/setup/complete", "{\"adminPasswordHash\":\"\",\"enableAuth\":\"false\"}");

        assertEquals(400, resp.statusCode(), resp.body());
        assertEquals(I18n.get("api.adminHashMissing"), resp.body());
    }

    @Test
    void testSetupPageRedirectsAfterCompletionWithoutChangingTheLanguage() throws Exception
    {
        HttpResponse<String> resp = get("/setup?lang=en");

        assertEquals(302, resp.statusCode());
        assertEquals("/", resp.headers().firstValue("Location").orElse(""));
        assertEquals("de", Config.getInstance().getLanguage());
        assertEquals("de", I18n.current());
    }

    @Test
    void testSetupPageIsShownBeforeCompletion() throws Exception
    {
        Config.getInstance().getSetup().setSetupCompleted(false);

        HttpResponse<String> resp = get("/setup");

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("id=\"completeSetup\""), "Setup-Formular erwartet");
    }

    private HttpResponse<String> post(String path, String body) throws Exception
    {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + app.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception
    {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + app.port() + path))
                .GET()
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static Config replaceConfigInstance(Config replacement) throws Exception
    {
        Field field = Config.class.getDeclaredField("instance");
        field.setAccessible(true);
        Config old = (Config) field.get(null);
        field.set(null, replacement);
        return old;
    }
}
