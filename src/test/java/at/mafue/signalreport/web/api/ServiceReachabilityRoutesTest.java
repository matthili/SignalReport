package at.mafue.signalreport.web.api;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolierter Integrationstest fuer den "Jetzt pruefen"-Endpoint: startet einen
 * echten Javalin auf einem Zufallsport mit nur diesen Routen und einem Stub-Ausloeser.
 * Beruehrt bewusst weder den Config-Singleton noch das Repository (nur check-now
 * wird aufgerufen, und der Endpoint nutzt ausschliesslich den injizierten Ausloeser).
 */
class ServiceReachabilityRoutesTest
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

    private int startWith(LongSupplier trigger)
    {
        app = Javalin.create(c -> c.showJavalinBanner = false);
        ServiceReachabilityRoutes.register(app, null, trigger);
        app.start(0);
        return app.port();
    }

    @Test
    void testCheckNowStartsWhenNoCooldown() throws Exception
    {
        int port = startWith(() -> 0L);
        HttpResponse<String> resp = post(port, "/api/services/check-now");

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"started\":true"), resp.body());
        assertTrue(resp.body().contains("\"cooldownRemainingSeconds\":0"), resp.body());
    }

    @Test
    void testCheckNowReportsRemainingCooldown() throws Exception
    {
        int port = startWith(() -> 120L);
        HttpResponse<String> resp = post(port, "/api/services/check-now");

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"started\":false"), resp.body());
        assertTrue(resp.body().contains("\"cooldownRemainingSeconds\":120"), resp.body());
    }

    private static HttpResponse<String> post(int port, String path) throws Exception
    {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }
}
