package at.mafue.signalreport;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer die lücken-saubere Zuverlässigkeits-Auswertung.
 * Intervall = 10 s, Lücken-Schwelle = max(30, 60) = 60 s.
 */
class ReliabilityReportTest
{
    private static final int INTERVAL = 10;

    private static Measurement m(long epochSec, boolean success)
    {
        return new Measurement("t", 10.0, success, "PING",
                Instant.ofEpochSecond(epochSec), "", "", "", "", "h");
    }

    @Test
    void testEmpty()
    {
        ReliabilityReport r = ReliabilityReport.compute(new ArrayList<>(), INTERVAL, 3600, 0);
        assertFalse(r.hasData());
        assertEquals(0.0, r.getUptimePercent());
        assertEquals(0, r.getOutageCount());
    }

    @Test
    void testAllSuccess()
    {
        List<Measurement> ms = List.of(m(0, true), m(10, true), m(20, true), m(30, true));
        ReliabilityReport r = ReliabilityReport.compute(ms, INTERVAL, 40, 0);
        assertEquals(100.0, r.getUptimePercent(), 0.001);
        assertEquals(0, r.getOutageCount());
        assertEquals(100.0, r.getCoveragePercent(), 0.001); // 4 von 4 erwarteten
    }

    @Test
    void testSingleOutage()
    {
        // s, f, f, f, s -> ein Ausfall (3 Fehlschlaege)
        List<Measurement> ms = List.of(
                m(0, true), m(10, false), m(20, false), m(30, false), m(40, true));
        ReliabilityReport r = ReliabilityReport.compute(ms, INTERVAL, 50, 0);
        assertEquals(40.0, r.getUptimePercent(), 0.001);   // 2 von 5
        assertEquals(1, r.getOutageCount());
        assertEquals(30, r.getLongestOutageSeconds());     // 3 * 10 s
        assertEquals(30, r.getMttrSeconds());              // mittlere Ausfalldauer
    }

    @Test
    void testIsolatedFailIsNoOutage()
    {
        // ein einzelner Fehlschlag ist kein Ausfall, senkt aber die Verfuegbarkeit
        List<Measurement> ms = List.of(m(0, true), m(10, false), m(20, true));
        ReliabilityReport r = ReliabilityReport.compute(ms, INTERVAL, 30, 0);
        assertEquals(0, r.getOutageCount());
        assertEquals(66.667, r.getUptimePercent(), 0.01);
    }

    @Test
    void testFailGapFailIsNotOneLongOutage()
    {
        // Fehlschlag, dann grosse Luecke (Rechner aus), dann Fehlschlag:
        // KEIN durchgehender Ausfall ueber die Luecke hinweg.
        List<Measurement> ms = List.of(m(0, false), m(200, false));
        ReliabilityReport r = ReliabilityReport.compute(ms, INTERVAL, 210, 0);
        assertEquals(0, r.getOutageCount());
        assertEquals(0, r.getLongestOutageSeconds());
    }

    @Test
    void testTwoOutagesSplitByGap()
    {
        // [f,f] | Luecke | [f,f]  -> zwei getrennte Ausfaelle
        List<Measurement> ms = List.of(
                m(0, false), m(10, false),     // Lauf 1: Ausfall
                m(300, false), m(310, false)); // nach Luecke, Lauf 2: Ausfall
        ReliabilityReport r = ReliabilityReport.compute(ms, INTERVAL, 320, 0);
        assertEquals(2, r.getOutageCount());
        assertEquals(20, r.getLongestOutageSeconds());
    }

    @Test
    void testMaintenanceRaisesCoverage()
    {
        // 4 gemessene Zyklen, Fenster = 80 s (8 moegliche), aber 4 davon Wartung.
        // Erwartet = 8 - 4 = 4 -> Abdeckung 100 % (nicht 50 %).
        List<Measurement> ms = List.of(m(0, true), m(10, true), m(20, true), m(30, true));
        ReliabilityReport withMaint = ReliabilityReport.compute(ms, INTERVAL, 80, 4);
        assertEquals(100.0, withMaint.getCoveragePercent(), 0.001);

        ReliabilityReport withoutMaint = ReliabilityReport.compute(ms, INTERVAL, 80, 0);
        assertEquals(50.0, withoutMaint.getCoveragePercent(), 0.001);
    }

    @Test
    void testGapDoesNotCountAsDowntime()
    {
        // Vor und nach der Luecke alles erfolgreich -> 100 % Verfuegbarkeit,
        // die Luecke selbst ist kein Ausfall.
        List<Measurement> ms = List.of(m(0, true), m(10, true), m(500, true), m(510, true));
        ReliabilityReport r = ReliabilityReport.compute(ms, INTERVAL, 520, 0);
        assertEquals(100.0, r.getUptimePercent(), 0.001);
        assertEquals(0, r.getOutageCount());
    }
}
