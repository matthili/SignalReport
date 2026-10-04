package at.mafue.signalreport;

import at.mafue.signalreport.web.api.SystemRoutes;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueft das Stopp-Kommando ("signalreport.jar stop") gegen einen echten Javalin auf
 * Zufallsport sowie die beiden Randfaelle "keine Instanz" und "keine Konfiguration".
 */
class StopCommandTest
{
    @Test
    void testStopsRunningInstanceViaLocalEndpoint() throws Exception
    {
        AtomicBoolean called = new AtomicBoolean(false);
        Javalin app = Javalin.create(c -> c.showJavalinBanner = false);
        // Der Stub tut, was SignalReportApp tut: Stopp merken und den Server herunterfahren
        SystemRoutes.register(app, () ->
        {
        called.set(true);
        new Thread(app::stop, "test-stop").start();
        });
        app.start(0);
        int port = app.port();

        boolean stopped = StopCommand.requestShutdown(port, Duration.ofSeconds(20));

        assertTrue(stopped, "Nach dem Stopp darf niemand mehr auf dem Port lauschen");
        assertTrue(called.get(), "Der Stopp-Ausloeser muss aufgerufen worden sein");
        assertFalse(StopCommand.isPortOpen(port));
    }

    @Test
    void testNoRunningInstanceCountsAsStopped() throws Exception
    {
        int freePort;
        try (ServerSocket s = new ServerSocket(0))
            {
            freePort = s.getLocalPort();
            }
        long t0 = System.nanoTime();

        assertTrue(StopCommand.requestShutdown(freePort, Duration.ofSeconds(5)),
                "Ohne laufende Instanz gibt es nichts zu stoppen -> Erfolg");
        assertTrue((System.nanoTime() - t0) < 5_000_000_000L, "Darf nicht auf das Zeitlimit warten");
    }

    @Test
    void testResolvePortFallsBackToDefaultWithoutConfig()
    {
        assertEquals(StopCommand.DEFAULT_PORT, StopCommand.resolvePort("definitiv-nicht-vorhanden.json"));
        assertEquals(StopCommand.DEFAULT_PORT, StopCommand.resolvePort(null));
    }
}
