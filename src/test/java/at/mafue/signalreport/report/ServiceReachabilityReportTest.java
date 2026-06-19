package at.mafue.signalreport.report;

import at.mafue.signalreport.report.ServiceReachabilityReport.Episode;
import at.mafue.signalreport.storage.ServiceCheck;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline-Tests fuer die Episoden-Verdichtung der Dienst-Erreichbarkeit.
 */
class ServiceReachabilityReportTest
{
    private static final Instant BASE = Instant.parse("2026-03-01T00:00:00Z");
    private static final long SIX_HOURS = 6 * 3600L;

    private static ServiceCheck check(long offsetSeconds, String verdict)
    {
        return new ServiceCheck(BASE.plusSeconds(offsetSeconds), "facebook", verdict,
                "test", 200, "1.2.3.4", 12.3);
    }

    @Test
    void testEmptyChecksYieldNotChecked()
    {
        ServiceReachabilityReport r = ServiceReachabilityReport.compute("facebook", new ArrayList<>());

        assertFalse(r.hasData());
        assertEquals(0, r.getTotalChecks());
        assertEquals("NOT_CHECKED", r.getCurrentVerdict());
        assertTrue(r.getEpisodes().isEmpty());
        assertNull(r.getCurrentSince());
    }

    @Test
    void testAllReachableIsSingleEpisode()
    {
        List<ServiceCheck> checks = List.of(
                check(0, "REACHABLE"),
                check(SIX_HOURS, "REACHABLE"),
                check(2 * SIX_HOURS, "REACHABLE"));

        ServiceReachabilityReport r = ServiceReachabilityReport.compute("facebook", checks);

        assertEquals(3, r.getTotalChecks());
        assertEquals(100.0, r.getReachablePercent(), 0.001);
        assertEquals("REACHABLE", r.getCurrentVerdict());
        assertEquals(1, r.getEpisodes().size());

        Episode e = r.getEpisodes().get(0);
        assertEquals("REACHABLE", e.getVerdict());
        assertEquals(3, e.getSampleCount());
        assertEquals(2 * SIX_HOURS, e.getDurationSeconds());
        assertEquals(BASE, e.getStart());
    }

    @Test
    void testBlockEpisodeBetweenReachableRuns()
    {
        // Die Zensur-Story: erreichbar, dann zwei Mal gesperrt, dann wieder erreichbar.
        List<ServiceCheck> checks = List.of(
                check(0, "REACHABLE"),
                check(SIX_HOURS, "REACHABLE"),
                check(2 * SIX_HOURS, "REACHABLE"),
                check(3 * SIX_HOURS, "DNS_BLOCKED"),
                check(4 * SIX_HOURS, "DNS_BLOCKED"),
                check(5 * SIX_HOURS, "REACHABLE"),
                check(6 * SIX_HOURS, "REACHABLE"));

        ServiceReachabilityReport r = ServiceReachabilityReport.compute("facebook", checks);

        assertEquals(7, r.getTotalChecks());
        assertEquals(3, r.getEpisodes().size(), "reachable / blocked / reachable");
        assertEquals(5.0 * 100.0 / 7.0, r.getReachablePercent(), 0.001);

        Episode blocked = r.getEpisodes().get(1);
        assertEquals("DNS_BLOCKED", blocked.getVerdict());
        assertEquals(2, blocked.getSampleCount());
        assertEquals(BASE.plusSeconds(3 * SIX_HOURS), blocked.getStart());
        assertEquals(BASE.plusSeconds(4 * SIX_HOURS), blocked.getEnd());

        // Aktueller Zustand = letzte Episode (wieder erreichbar, seit Stunde 30)
        assertEquals("REACHABLE", r.getCurrentVerdict());
        assertEquals(BASE.plusSeconds(5 * SIX_HOURS), r.getCurrentSince());
    }

    @Test
    void testUnsortedInputIsSortedChronologically()
    {
        List<ServiceCheck> checks = List.of(
                check(2 * SIX_HOURS, "REACHABLE"),
                check(0, "REACHABLE"),
                check(SIX_HOURS, "DNS_BLOCKED"));

        ServiceReachabilityReport r = ServiceReachabilityReport.compute("facebook", checks);

        // Chronologisch: REACHABLE, DNS_BLOCKED, REACHABLE -> 3 Episoden
        assertEquals(3, r.getEpisodes().size());
        assertEquals("REACHABLE", r.getEpisodes().get(0).getVerdict());
        assertEquals("DNS_BLOCKED", r.getEpisodes().get(1).getVerdict());
        assertEquals("REACHABLE", r.getEpisodes().get(2).getVerdict());
    }
}
