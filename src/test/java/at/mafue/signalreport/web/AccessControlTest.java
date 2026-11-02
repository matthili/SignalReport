package at.mafue.signalreport.web;

import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.i18n.I18n;
import at.mafue.signalreport.web.api.AuthRoutes;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rollenpruefung gegen einen echten Javalin auf Zufallsport: Anmeldepflicht und Admin-Filter
 * aus {@link AccessControl}, Login ueber die echten {@code AuthRoutes} (Challenge-Response),
 * Sessions aus einem echten {@link SessionManager}.
 * <p>
 * Die geschuetzten Pfade bekommen Stub-Handler, die nur mitzaehlen: Die Pruefung haengt am Pfad,
 * nicht am Handler, und kein Testfall erreicht so einen Handler, der config.json schreibt. Dass
 * die echten Routen genau diese Pfade benutzen, prueft {@link #testEveryWritingRouteIsClassified()}.
 * Der Config-Singleton wird vorher gesichert und danach zurueckgesetzt.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessControlTest
{
    private static final String ADMIN_PASSWORD = "admin-test-pw";
    private static final String USER_PASSWORD = "user-test-pw";

    /** Schreibende Routen, die auch ein User aufrufen darf, mit Begruendung. */
    private static final Set<String> NOT_ADMIN_ONLY = Set.of(
            "POST /api/dns/benchmark",        // Messaktion, aendert nichts
            "POST /api/services/check-now",   // Messaktion mit Abkuehlphase
            "POST /api/theme/settings",       // Dark Mode, bewusst fuer alle
            "POST /api/auth/login",
            "POST /api/auth/logout",
            "POST /api/auth/enable",          // verlangt das Admin-Passwort selbst (Challenge-Response)
            "POST /api/auth/disable",         // dto.
            "POST /api/auth/change-admin",    // dto.
            "POST /api/setup/complete",       // nur bis zum Abschluss der Einrichtung (SetupRoutes)
            "POST /api/system/shutdown");     // nur von localhost (SystemRoutes)

    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger adminHandlerCalls = new AtomicInteger();
    private SessionManager sessionManager;
    private Javalin app;
    private Config previousConfig;

    @BeforeAll
    void start() throws Exception
    {
        previousConfig = replaceConfigInstance(null);
        I18n.load("de");
        Config config = Config.createDefault();
        config.getSetup().setSetupCompleted(true);
        config.getSetup().setAdminPasswordHash(Config.hashPassword(ADMIN_PASSWORD));
        config.getAuth().setUserPasswordHash(Config.hashPassword(USER_PASSWORD));

        sessionManager = new SessionManager();
        app = Javalin.create(c -> c.showJavalinBanner = false);
        AccessControl.register(app, sessionManager);
        AuthRoutes.register(app, sessionManager);
        for (AccessControl.Endpoint endpoint : AccessControl.ADMIN_ONLY)
            {
            app.addHandler(endpoint.method(), endpoint.path(), ctx ->
            {
            adminHandlerCalls.incrementAndGet();
            ctx.result("ok");
            });
            }
        // Lesen und die fuer User freigegebenen Aktionen (Stubs)
        app.get("/api/config/current", ctx -> ctx.result("ok"));
        app.post("/api/dns/benchmark", ctx -> ctx.result("ok"));
        app.post("/api/services/check-now", ctx -> ctx.result("ok"));
        app.post("/api/theme/settings", ctx -> ctx.result("ok"));
        // Zeigt, was AccessControl.hasAdminRights() fuer die Anfrage liefert
        app.get("/rights-probe.js", ctx -> ctx.result(String.valueOf(AccessControl.hasAdminRights(ctx))));
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
    void reset()
    {
        Config.getInstance().getAuth().setEnabled(true);
        adminHandlerCalls.set(0);
    }

    @Test
    void testUserSessionGets403OnEveryAdminEndpoint() throws Exception
    {
        String userToken = sessionManager.createSession("user");

        for (AccessControl.Endpoint endpoint : AccessControl.ADMIN_ONLY)
            {
            HttpResponse<String> resp = send(endpoint.method().name(), endpoint.path(), userToken, null);
            assertEquals(403, resp.statusCode(), endpoint + ": " + resp.body());
            assertEquals(I18n.get("api.adminOnly"), resp.body(), endpoint.toString());
            }
        assertEquals(0, adminHandlerCalls.get(), "Kein Admin-Handler darf fuer eine User-Session laufen");
    }

    @Test
    void testAdminSessionPassesEveryAdminEndpoint() throws Exception
    {
        String adminToken = sessionManager.createSession("admin");

        for (AccessControl.Endpoint endpoint : AccessControl.ADMIN_ONLY)
            {
            HttpResponse<String> resp = send(endpoint.method().name(), endpoint.path(), adminToken, null);
            assertEquals(200, resp.statusCode(), endpoint + ": " + resp.body());
            assertEquals("ok", resp.body());
            }
        assertEquals(AccessControl.ADMIN_ONLY.size(), adminHandlerCalls.get());
    }

    @Test
    void testWithoutAuthenticationEverythingIsAllowed() throws Exception
    {
        Config.getInstance().getAuth().setEnabled(false);

        for (AccessControl.Endpoint endpoint : AccessControl.ADMIN_ONLY)
            {
            HttpResponse<String> resp = send(endpoint.method().name(), endpoint.path(), null, null);
            assertEquals(200, resp.statusCode(), endpoint + ": " + resp.body());
            }
        assertEquals(AccessControl.ADMIN_ONLY.size(), adminHandlerCalls.get());
    }

    @Test
    void testUserMayReadAndUseTheAllowedActions() throws Exception
    {
        String userToken = sessionManager.createSession("user");

        assertEquals(200, send("GET", "/api/config/current", userToken, null).statusCode());
        assertEquals(200, send("POST", "/api/dns/benchmark", userToken, null).statusCode());
        assertEquals(200, send("POST", "/api/services/check-now", userToken, null).statusCode());
        assertEquals(200, send("POST", "/api/theme/settings", userToken, null).statusCode());
    }

    @Test
    void testWithoutSessionTheApiAnswers401() throws Exception
    {
        HttpResponse<String> resp = send("POST", "/api/config/update", null, null);

        assertEquals(401, resp.statusCode(), resp.body());
        assertEquals(0, adminHandlerCalls.get());
    }

    @Test
    void testTrailingSlashDoesNotBypassTheAdminCheck() throws Exception
    {
        // Javalin ignoriert den Schraegstrich am Ende: die Route wird getroffen (Admin: 200) ...
        assertEquals(200, send("POST", "/api/config/update/", sessionManager.createSession("admin"), null).statusCode());
        // ... und deshalb muss auch die Pruefung greifen
        HttpResponse<String> resp = send("POST", "/api/config/update/", sessionManager.createSession("user"), null);
        assertEquals(403, resp.statusCode(), resp.body());
        assertEquals(1, adminHandlerCalls.get(), "nur der Admin-Aufruf darf den Handler erreichen");
    }

    @Test
    void testMethodOverrideHeaderDoesNotBypassTheAdminCheck() throws Exception
    {
        // Javalin wertet X-HTTP-Method-Override aus: ein GET mit "POST" trifft die POST-Route ...
        assertEquals(200, sendWithMethodOverride(sessionManager.createSession("admin")).statusCode());
        // ... und die Pruefung sieht dieselbe Methode
        HttpResponse<String> resp = sendWithMethodOverride(sessionManager.createSession("user"));
        assertEquals(403, resp.statusCode(), resp.body());
        assertEquals(1, adminHandlerCalls.get());
    }

    @Test
    void testRoleComesFromTheRealLogin() throws Exception
    {
        String userToken = login("user", USER_PASSWORD, "user");
        assertEquals(403, send("POST", "/api/config/update", userToken, null).statusCode());

        String adminToken = login("admin", ADMIN_PASSWORD, "admin");
        assertEquals(200, send("POST", "/api/config/update", adminToken, null).statusCode());
        assertEquals(1, adminHandlerCalls.get());
    }

    @Test
    void testAdminRightsWithoutRememberedRoleAreDenied() throws Exception
    {
        // Statische Ressourcen (hier: Endung .js) laufen ohne Session-Pruefung; dort ist keine
        // Rolle gemerkt, und dann gilt auch ein Admin-Cookie nicht als Admin
        String adminToken = sessionManager.createSession("admin");
        assertEquals("false", send("GET", "/rights-probe.js", adminToken, null).body());

        Config.getInstance().getAuth().setEnabled(false);
        assertEquals("true", send("GET", "/rights-probe.js", null, null).body(),
                "ohne Authentifizierung hat jeder Admin-Rechte");
    }

    /**
     * Jede schreibende Route, die der WebServer registriert, ist entweder Admin-only oder bewusst
     * freigegeben, und jeder Admin-Pfad existiert als echte Route. Ein Tippfehler in
     * {@link AccessControl#ADMIN_ONLY} wuerde die Pruefung sonst stumm abschalten, und eine neue
     * Route ohne Einordnung waere fuer jeden User offen.
     */
    @Test
    void testEveryWritingRouteIsClassified()
    {
        Set<String> writing = new TreeSet<>();
        Javalin probe = Javalin.create(c -> c.showJavalinBanner = false);
        probe.events(events -> events.handlerAdded(meta ->
        {
        if (meta.getHttpMethod().isHttpMethod() && meta.getHttpMethod() != HandlerType.GET)
            {
            writing.add(meta.getHttpMethod() + " " + meta.getPath());
            }
        }));
        new WebServer(null, () -> 0L, () -> { }, null).registerRoutes(probe);

        Set<String> adminOnly = new TreeSet<>();
        for (AccessControl.Endpoint endpoint : AccessControl.ADMIN_ONLY)
            {
            adminOnly.add(endpoint.method() + " " + endpoint.path());
            }

        assertTrue(writing.containsAll(adminOnly), "Admin-Pfad ohne echte Route: " + adminOnly + " vs. " + writing);
        Set<String> rest = new TreeSet<>(writing);
        rest.removeAll(adminOnly);
        assertEquals(new TreeSet<>(NOT_ADMIN_ONLY), rest, "Schreibende Route ohne Einordnung (Admin-only oder freigegeben)");
    }

    // ------------------------------------------------------------------
    //  Hilfen
    // ------------------------------------------------------------------

    /** Login wie im Browser: Nonce holen, SHA-256(SHA-256(Passwort) + Nonce) senden. */
    private String login(String username, String password, String expectedRole) throws Exception
    {
        HttpResponse<String> nonceResp = send("GET", "/api/auth/nonce", null, null);
        assertEquals(200, nonceResp.statusCode(), nonceResp.body());
        String nonce = mapper.readTree(nonceResp.body()).get("nonce").asText();

        String challengeResponse = Config.hashPassword(Config.hashPassword(password) + nonce);
        String body = mapper.writeValueAsString(Map.of(
                "username", username, "nonce", nonce, "challengeResponse", challengeResponse));
        HttpResponse<String> loginResp = send("POST", "/api/auth/login", null, body);
        assertEquals(200, loginResp.statusCode(), loginResp.body());

        var json = mapper.readTree(loginResp.body());
        assertEquals(expectedRole, json.get("role").asText());
        return json.get("token").asText();
    }

    private HttpResponse<String> send(String method, String path, String token, String jsonBody) throws Exception
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + app.port() + path))
                .method(method, jsonBody == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(jsonBody));
        if (jsonBody != null)
            {
            builder.header("Content-Type", "application/json");
            }
        if (token != null)
            {
            builder.header("Cookie", AccessControl.SESSION_COOKIE + "=" + token);
            }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendWithMethodOverride(String token) throws Exception
    {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + app.port() + "/api/config/update"))
                .header("X-HTTP-Method-Override", "POST")
                .header("Cookie", AccessControl.SESSION_COOKIE + "=" + token)
                .GET()
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Setzt den Config-Singleton (Testhilfe, wie die Reflection-Zugriffe im SessionManagerTest). */
    static Config replaceConfigInstance(Config replacement) throws Exception
    {
        Field field = Config.class.getDeclaredField("instance");
        field.setAccessible(true);
        Config old = (Config) field.get(null);
        field.set(null, replacement);
        return old;
    }
}
