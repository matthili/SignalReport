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
        // s, f, f, f, s -> ein Ausfall (Fehlschlaege bei 10,20,30 -> Span 20 s)
        List<Measurement> ms = List.of(
                m(0, true), m(10, false), m(20, false), m(30, false), m(40, true));
        ReliabilityReport r = ReliabilityReport.compute(ms, INTERVAL, 50, 0);
        assertEquals(40.0, r.getUptimePercent(), 0.001);   // 2 von 5
        assertEquals(1, r.getOutageCount());
        assertEquals(1, r.getOutages().size());
        assertEquals(20, r.getLongestOutageSeconds());     // 30 - 10 s
        assertEquals(20, r.getMttrSeconds());
        assertEquals(3, r.getOutages().get(0).getSampleCount());
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
        assertEquals(2, r.getOutages().size());
        assertEquals(10, r.getLongestOutageSeconds()); // jede Serie 10 s Span
    }

    @Test
    void testModemRebootIsOneAggregatedOutage()
    {
        // Modem-Neustart: viele aufeinanderfolgende Fehlschlaege ueber ~150 s bei 30s-Intervall.
        // Muss EIN Ausfall sein, nicht viele Fragmente.
        List<Measurement> ms = new ArrayList<>();
        ms.add(m(0, true));
        for (long s = 30; s <= 180; s += 30) ms.add(m(s, false)); // 6 Fehlschlaege
        ms.add(m(210, true));

        ReliabilityReport r = ReliabilityReport.compute(ms, 30, 300, 0);
        assertEquals(1, r.getOutageCount());
        assertEquals(1, r.getOutages().size());
        ReliabilityReport.Outage o = r.getOutages().get(0);
        assertEquals(6, o.getSampleCount());
        assertEquals(150, o.getDurationSeconds()); // 180 - 30 s
        assertFalse(o.isExcluded());
    }

    @Test
    void testExcludedOutageDropsFromHeadlineButStaysInList()
    {
        List<Measurement> ms = new ArrayList<>();
        ms.add(m(0, true));
        Measurement f1 = m(10, false); f1.setExcluded(true);
        Measurement f2 = m(20, false); f2.setExcluded(true);
        Measurement f3 = m(30, false); f3.setExcluded(true);
        ms.add(f1); ms.add(f2); ms.add(f3);
        ms.add(m(40, true));

        ReliabilityReport r = ReliabilityReport.compute(ms, INTERVAL, 50, 0);
        // In der Liste vorhanden, aber als ausgenommen markiert
        assertEquals(1, r.getOutages().size());
        assertTrue(r.getOutages().get(0).isExcluded());
        // Kennzahlen ignorieren ihn
        assertEquals(0, r.getOutageCount());
        assertEquals(100.0, r.getUptimePercent(), 0.001); // nur 2 erfolgreiche, ausgenommene zaehlen nicht
        // Abdeckung wird durch das Ausschliessen nicht gesenkt: erwartet 5 - 3 = 2, gemessen 2 -> 100 %
        assertEquals(100.0, r.getCoveragePercent(), 0.001);
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
