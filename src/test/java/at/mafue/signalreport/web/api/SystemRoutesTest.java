package at.mafue.signalreport.web.api;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolierter Integrationstest fuer den lokalen Stopp-Endpunkt: echter Javalin auf
 * Zufallsport, nur diese Route, Stub-Ausloeser. Der Aufruf kommt im Test immer von
 * localhost und muss deshalb angenommen werden; die Loopback-Erkennung selbst wird
 * zusaetzlich direkt geprueft.
 */
class SystemRoutesTest
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
    void testShutdownFromLoopbackTriggersStop() throws Exception
    {
        AtomicBoolean called = new AtomicBoolean(false);
        app = Javalin.create(c -> c.showJavalinBanner = false);
        SystemRoutes.register(app, () -> called.set(true));
        app.start(0);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + app.port() + SystemRoutes.SHUTDOWN_PATH))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("stopping"), resp.body());
        assertTrue(called.get(), "Der Stopp-Ausloeser muss aufgerufen worden sein");
    }

    @Test
    void testLoopbackDetection()
    {
        assertTrue(SystemRoutes.isLoopback("127.0.0.1"));
        assertTrue(SystemRoutes.isLoopback("127.0.0.2"));
        assertTrue(SystemRoutes.isLoopback("::1"));
        assertTrue(SystemRoutes.isLoopback("0:0:0:0:0:0:0:1"));
        assertTrue(SystemRoutes.isLoopback("[::1]"));

        assertFalse(SystemRoutes.isLoopback("192.168.1.10"));
        assertFalse(SystemRoutes.isLoopback("10.0.0.1"));
        assertFalse(SystemRoutes.isLoopback("2001:db8::1"));
        assertFalse(SystemRoutes.isLoopback("localhost"), "Hostnamen werden nicht aufgeloest");
        assertFalse(SystemRoutes.isLoopback("tars"));
        assertFalse(SystemRoutes.isLoopback(""));
        assertFalse(SystemRoutes.isLoopback(null));
    }
}
