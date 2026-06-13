package at.mafue.signalreport;

import at.mafue.signalreport.H2MeasurementRepository.Statistics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer die Stoerungs-Lokalisierung (whose-fault).
 */
class ConnectivityAssessmentTest
{
    /** Gesundes Segment: niedrige Latenz, kein Verlust. */
    private static Statistics healthy(double latency)
    {
        return new Statistics(latency, latency, latency, 0.0, 0.0);
    }

    /** Gestoertes Segment: hoher Paketverlust. */
    private static Statistics broken()
    {
        return new Statistics(0.0, 0.0, 0.0, 100.0, 0.0);
    }

    /** Kein Datensatz im Zeitfenster. */
    private static Statistics noData()
    {
        return new Statistics(0.0, 0.0, 0.0, 0.0, 0.0);
    }

    @Test
    void testNoData()
    {
        assertEquals(ConnectivityAssessment.Verdict.NO_DATA,
                ConnectivityAssessment.assess(null, null, null));
        assertEquals(ConnectivityAssessment.Verdict.NO_DATA,
                ConnectivityAssessment.assess(noData(), noData(), noData()));
    }

    @Test
    void testAllGood()
    {
        assertEquals(ConnectivityAssessment.Verdict.ALL_GOOD,
                ConnectivityAssessment.assess(healthy(0.4), healthy(2.0), healthy(20.0)));
    }

    @Test
    void testLocalNetworkFault()
    {
        // Eigener Router gestoert -> lokales Netz
        assertEquals(ConnectivityAssessment.Verdict.LOCAL_NETWORK,
                ConnectivityAssessment.assess(broken(), healthy(2.0), broken()));
    }

    @Test
    void testLocalEdgeFault()
    {
        // Router ok, Modem/Edge gestoert
        assertEquals(ConnectivityAssessment.Verdict.LOCAL_EDGE,
                ConnectivityAssessment.assess(healthy(0.4), broken(), broken()));
    }

    @Test
    void testIspOrBeyond()
    {
        // Lokale Kette gesund, Internet gestoert -> Provider o. dahinter
        assertEquals(ConnectivityAssessment.Verdict.ISP_OR_BEYOND,
                ConnectivityAssessment.assess(healthy(0.4), healthy(2.0), broken()));
    }

    @Test
    void testIspWithoutFarGateway()
    {
        // Nur ein Router (kein far), der ist gesund, Internet gestoert
        assertEquals(ConnectivityAssessment.Verdict.ISP_OR_BEYOND,
                ConnectivityAssessment.assess(healthy(0.4), null, broken()));
    }

    @Test
    void testAllGoodWhenInternetHealthyDespiteNoGateways()
    {
        assertEquals(ConnectivityAssessment.Verdict.ALL_GOOD,
                ConnectivityAssessment.assess(null, null, healthy(20.0)));
    }

    @Test
    void testHasDataAndHealthyHelpers()
    {
        assertFalse(ConnectivityAssessment.hasData(noData()));
        assertFalse(ConnectivityAssessment.hasData(null));
        assertTrue(ConnectivityAssessment.hasData(healthy(1.0)));
        assertTrue(ConnectivityAssessment.hasData(broken())); // Fehlschlaege sind auch Daten

        assertTrue(ConnectivityAssessment.isHealthy(healthy(1.0)));
        assertFalse(ConnectivityAssessment.isHealthy(broken()));
        assertFalse(ConnectivityAssessment.isHealthy(noData()));

        // knapp unter Schwelle gesund, knapp darueber gestoert
        assertTrue(ConnectivityAssessment.isHealthy(new Statistics(10, 10, 10, 19.9, 0)));
        assertFalse(ConnectivityAssessment.isHealthy(new Statistics(10, 10, 10, 20.0, 0)));
    }
}
