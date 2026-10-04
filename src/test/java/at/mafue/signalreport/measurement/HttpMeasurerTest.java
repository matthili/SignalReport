package at.mafue.signalreport.measurement;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP-Messung der Leitung gegen einen echten lokalen Server: jede Antwort zaehlt als
 * erreichbar (auch 404/503/302), nur Verbindungsfehler und Timeouts sind Fehlschlaege.
 */
class HttpMeasurerTest
{
    private Javalin app;

    @AfterEach
    void stop()
    {
        if (app != null)
            {
            app.stop();
            }
    }

    @Test
    void testAnyHttpResponseCountsAsReachable() throws Exception
    {
        app = Javalin.create(c -> c.showJavalinBanner = false);
        app.get("/ok", ctx -> ctx.result("ok"));
        app.get("/missing", ctx -> ctx.status(404).result("nope"));
        app.get("/broken", ctx -> ctx.status(503).result("down"));
        app.get("/forbidden", ctx -> ctx.status(403).result("bot?"));
        app.get("/moved", ctx -> ctx.redirect("/ok"));
        app.start(0);
        String base = "http://127.0.0.1:" + app.port();

        HttpMeasurer http = new HttpMeasurer();
        for (String path : new String[]{"/ok", "/missing", "/broken", "/forbidden", "/moved"})
            {
            Measurement m = http.measure(base + path);
            assertTrue(m.isSuccess(), path + " muss als erreichbar zaehlen (der Server hat geantwortet)");
            assertEquals("HTTP", m.getType());
            assertEquals(base + path, m.getTarget());
            assertTrue(m.getLatencyMs() >= 0.0 && m.getLatencyMs() < 5000.0, "Latenz plausibel: " + m.getLatencyMs());
            }
    }

    @Test
    void testConnectionRefusedIsFailure() throws Exception
    {
        int freePort;
        try (ServerSocket s = new ServerSocket(0))
            {
            freePort = s.getLocalPort();
            }
        Measurement m = new HttpMeasurer().measure("http://127.0.0.1:" + freePort + "/");
        assertFalse(m.isSuccess(), "ohne Server gibt es keine Antwort");
        assertEquals("HTTP", m.getType());
        assertTrue(m.getLatencyMs() < 5000.0, "Verbindungsverweigerung ist kein Timeout: " + m.getLatencyMs());
    }

    @Test
    void testDescribeNamesTheReason()
    {
        assertTrue(HttpMeasurer.describe(new HttpConnectTimeoutException("x")).startsWith("Verbindungs-Timeout"));
        assertTrue(HttpMeasurer.describe(new HttpTimeoutException("x")).startsWith("Timeout"));
        assertEquals("ConnectException: refused", HttpMeasurer.describe(new ConnectException("refused")));
        assertEquals("ConnectException", HttpMeasurer.describe(new ConnectException()));
    }
}
