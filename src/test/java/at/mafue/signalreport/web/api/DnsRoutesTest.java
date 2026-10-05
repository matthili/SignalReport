package at.mafue.signalreport.web.api;

import at.mafue.signalreport.config.BuiltinCatalog;
import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.i18n.I18n;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DNS-Endpunkte gegen echten Javalin auf Zufallsport, mit einer eigenen Config-Instanz (nicht
 * dem Singleton) und einem zaehlenden Speicher-Ersatz. Es wird kein Benchmark gegen echte
 * DNS-Server ausgefuehrt (kein Netzverkehr).
 */
class DnsRoutesTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Javalin app;
    private Config config;
    private final AtomicInteger saves = new AtomicInteger();
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start()
    {
        I18n.load("de");
        config = new Config();
        saves.set(0);
        app = Javalin.create(c -> c.showJavalinBanner = false);
        DnsRoutes.register(app, () -> config, saves::incrementAndGet);
        app.start(0);
    }

    @AfterEach
    void stop()
    {
        if (app != null)
            {
            app.stop();
            }
    }

    private HttpResponse<String> get(String path) throws Exception
    {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws Exception
    {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void testCatalogListsBuiltinsAndNoCustomServers() throws Exception
    {
        HttpResponse<String> resp = get("/api/dns/catalog");
        assertEquals(200, resp.statusCode(), resp.body());
        JsonNode n = MAPPER.readTree(resp.body());
        assertEquals(BuiltinCatalog.dnsServers().size(), n.get("builtins").size());
        assertEquals(0, n.get("customServers").size());
        assertEquals(50, n.get("maxCustomServers").asInt());
        assertFalse(n.get("builtins").get(0).get("hidden").asBoolean());
    }

    @Test
    void testSaveOwnServerAndHideBuiltin() throws Exception
    {
        String body = """
                {"customServers":[{"name":"Router","address":"192.168.20.1","note":"Heimnetz","enabled":true}],
                 "hiddenBuiltins":["8.8.8.8","203.0.113.7"]}
                """;
        HttpResponse<String> resp = post("/api/dns/settings", body);

        assertEquals(200, resp.statusCode(), resp.body());
        assertEquals(1, saves.get(), "config.json wird genau einmal gespeichert");
        assertEquals(1, config.getDnsBenchmark().getCustomServers().size());
        assertEquals(List.of("8.8.8.8"), config.getDnsBenchmark().getHiddenBuiltins(), "unbekannte Adressen werden verworfen");

        JsonNode saved = MAPPER.readTree(resp.body());
        assertEquals("Router", saved.get("customServers").get(0).get("name").asText());
        boolean googleHidden = false;
        for (JsonNode b : saved.get("builtins"))
            {
            if ("8.8.8.8".equals(b.get("address").asText()))
                {
                googleHidden = b.get("hidden").asBoolean();
                }
            }
        assertTrue(googleHidden);

        JsonNode servers = MAPPER.readTree(get("/api/dns/servers").body());
        assertEquals(BuiltinCatalog.dnsServers().size() - 1 + 1, servers.size());
        int custom = 0;
        for (JsonNode s : servers)
            {
            assertNotEquals("8.8.8.8", s.get("address").asText());
            if (s.get("custom").asBoolean()) custom++;
            }
        assertEquals(1, custom);

        JsonNode stats = MAPPER.readTree(get("/api/dns/statistics").body());
        assertEquals(servers.size(), stats.get("totalServers").asInt());
        assertEquals(1, stats.get("customServers").asInt());
    }

    @Test
    void testInvalidEntryIsRejectedWithReadableMessage() throws Exception
    {
        String body = """
                {"customServers":[{"name":"Google","address":"dns.google","note":"","enabled":true}],"hiddenBuiltins":[]}
                """;
        HttpResponse<String> resp = post("/api/dns/settings", body);

        assertEquals(400, resp.statusCode(), resp.body());
        String error = MAPPER.readTree(resp.body()).get("error").asText();
        assertTrue(error.contains("dns.google") && error.contains("keine gültige IP-Adresse"), error);
        assertEquals(0, saves.get(), "bei Fehlern wird nichts gespeichert");
        assertTrue(config.getDnsBenchmark().getCustomServers().isEmpty());
    }

    @Test
    void testMalformedBodyIsRejected() throws Exception
    {
        HttpResponse<String> resp = post("/api/dns/settings", "kein json");
        assertEquals(400, resp.statusCode(), resp.body());
        assertEquals(0, saves.get());
    }

    @Test
    void testOwnOnlyBenchmarkWithoutOwnServersIsEmpty() throws Exception
    {
        HttpResponse<String> resp = post("/api/dns/benchmark?scope=custom&hostname=example.com", "");
        assertEquals(200, resp.statusCode(), resp.body());
        assertEquals(0, MAPPER.readTree(resp.body()).size());
    }
}
