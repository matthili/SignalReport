package at.mafue.signalreport;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Testet, dass alle Measurer-Klassen das Measurer-Interface korrekt implementieren
 * und dass das Strategy-Pattern eingehalten wird.
 */
class MeasurerInterfaceTest {

    @Test
    void testPingMeasurerImplementsMeasurer() {
        PingMeasurer ping = new PingMeasurer();
        assertInstanceOf(Measurer.class, ping,
            "PingMeasurer muss das Measurer-Interface implementieren");
    }

    @Test
    void testDnsMeasurerImplementsMeasurer() {
        DnsMeasurer dns = new DnsMeasurer();
        assertInstanceOf(Measurer.class, dns,
            "DnsMeasurer muss das Measurer-Interface implementieren");
    }

    @Test
    void testHttpMeasurerImplementsMeasurer() {
        HttpMeasurer http = new HttpMeasurer();
        assertInstanceOf(Measurer.class, http,
            "HttpMeasurer muss das Measurer-Interface implementieren");
    }

    @Test
    void testAllMeasurersArePolymorphic() {
        // Alle drei Measurer muessen ueber das Interface ansprechbar sein
        Measurer[] measurers = {
            new PingMeasurer(),
            new DnsMeasurer(),
            new HttpMeasurer()
        };

        assertEquals(3, measurers.length, "Es muessen genau 3 Measurer existieren");

        for (Measurer m : measurers) {
            assertNotNull(m, "Kein Measurer darf null sein");
        }
    }

    @Test
    void testPingMeasurerReturnsValidMeasurement() throws Exception {
        // Messe gegen localhost (sollte immer erreichbar sein)
        Measurer ping = new PingMeasurer();
        Measurement result = ping.measure("127.0.0.1");

        assertNotNull(result, "Messergebnis darf nicht null sein");
        assertEquals("127.0.0.1", result.getTarget());
        assertEquals("PING", result.getType());
        assertTrue(result.getLatencyMs() >= 0, "Latenz muss >= 0 sein");
    }

    @Test
    void testDnsMeasurerReturnsValidMeasurement() throws Exception {
        // DNS-Aufloesung von localhost (sollte immer funktionieren)
        Measurer dns = new DnsMeasurer();
        Measurement result = dns.measure("localhost");

        assertNotNull(result, "Messergebnis darf nicht null sein");
        assertEquals("localhost", result.getTarget());
        assertEquals("DNS", result.getType());
        assertTrue(result.getLatencyMs() >= 0, "Latenz muss >= 0 sein");
    }
}
