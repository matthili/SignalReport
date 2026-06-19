package at.mafue.signalreport;

import at.mafue.signalreport.measurement.Measurement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer die reine Leitungs-Gate-Entscheidung des Schedulers (ohne DB/Netz).
 */
class ServiceReachabilitySchedulerTest
{
    @Test
    void testSuccessfulPingMeansUp()
    {
        List<Measurement> recent = List.of(
                new Measurement("8.8.8.8", 5000, false, "PING"),
                new Measurement("8.8.8.8", 23.0, true, "PING"));
        assertTrue(ServiceReachabilityScheduler.internetConfirmedUp(recent));
    }

    @Test
    void testSuccessfulHttpMeansUp()
    {
        List<Measurement> recent = List.of(
                new Measurement("https://example.com", 80.0, true, "HTTP"));
        assertTrue(ServiceReachabilityScheduler.internetConfirmedUp(recent));
    }

    @Test
    void testOnlyFailuresMeansDown()
    {
        List<Measurement> recent = List.of(
                new Measurement("8.8.8.8", 5000, false, "PING"),
                new Measurement("https://example.com", 5000, false, "HTTP"));
        assertFalse(ServiceReachabilityScheduler.internetConfirmedUp(recent));
    }

    @Test
    void testGatewayMaintenanceAndDnsDoNotCount()
    {
        // Erfolgreiche Gateway-/Wartungs-/DNS-Eintraege belegen die Internet-Leitung NICHT.
        List<Measurement> recent = List.of(
                new Measurement("192.168.0.1", 1.0, true, "GATEWAY_NEAR"),
                new Measurement("maintenance", 0.0, true, "MAINTENANCE"),
                new Measurement("google.com", 10.0, true, "DNS"));
        assertFalse(ServiceReachabilityScheduler.internetConfirmedUp(recent));
    }

    @Test
    void testEmptyMeansDown()
    {
        assertFalse(ServiceReachabilityScheduler.internetConfirmedUp(List.of()));
    }
}
