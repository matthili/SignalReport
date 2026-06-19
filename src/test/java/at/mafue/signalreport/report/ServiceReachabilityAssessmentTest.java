package at.mafue.signalreport.report;

import org.junit.jupiter.api.Test;

import at.mafue.signalreport.report.ServiceReachabilityAssessment.Observation;
import at.mafue.signalreport.report.ServiceReachabilityAssessment.Verdict;

import static at.mafue.signalreport.report.ServiceReachabilityAssessment.classify;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline-Tests fuer die reine Verdikt-Logik der Dienst-Erreichbarkeit. Es wird
 * ausschliesslich klassifiziert -- kein Netz, keine DB.
 */
class ServiceReachabilityAssessmentTest
{
    @Test
    void testHealthyServiceIsReachable()
    {
        Observation o = new Observation().dns(true, false, true).tcp(true).tlsRealSni(true).http(200);
        assertEquals(Verdict.REACHABLE, classify(o));
    }

    @Test
    void testClientErrorStillReachable()
    {
        // 403/404: die Vordertuer antwortet -> Dienst erreichbar, nicht gesperrt.
        Observation o = new Observation().dns(true, false, true).tcp(true).tlsRealSni(true).http(403);
        assertEquals(Verdict.REACHABLE, classify(o));
    }

    @Test
    void testServerErrorIsServiceDown()
    {
        Observation o = new Observation().dns(true, false, true).tcp(true).tlsRealSni(true).http(503);
        assertEquals(Verdict.SERVICE_DOWN, classify(o));
    }

    @Test
    void testIspResolverFailsButPublicWorksIsDnsBlocked()
    {
        Observation o = new Observation().dns(false, false, true);
        assertEquals(Verdict.DNS_BLOCKED, classify(o));
    }

    @Test
    void testIspReturnsBogusIpIsDnsBlocked()
    {
        Observation o = new Observation().dns(true, true, true).tcp(true);
        assertEquals(Verdict.DNS_BLOCKED, classify(o));
    }

    @Test
    void testResolvedButNoTcpWithControlReachableIsConnectionBlocked()
    {
        Observation o = new Observation().dns(true, false, true).tcp(false).controlReachable(true);
        assertEquals(Verdict.CONNECTION_BLOCKED, classify(o));
    }

    @Test
    void testRealSniFailsButControlSniWorksIsSniBlocked()
    {
        Observation o = new Observation().dns(true, false, true).tcp(true)
                .tlsRealSni(false).tlsControlSni(true, true);
        assertEquals(Verdict.SNI_BLOCKED, classify(o));
    }

    @Test
    void testHttp451IsBlockpage()
    {
        Observation o = new Observation().dns(true, false, true).tcp(true).tlsRealSni(true).http(451);
        assertEquals(Verdict.BLOCKPAGE, classify(o));
    }

    @Test
    void testRedirectToBlockpageIsBlockpage()
    {
        Observation o = new Observation().dns(true, false, true).tcp(true).tlsRealSni(true)
                .http(302).blockpage(true);
        assertEquals(Verdict.BLOCKPAGE, classify(o));
    }

    @Test
    void testNothingResolvesIsUnknown()
    {
        Observation o = new Observation().dns(false, false, false);
        assertEquals(Verdict.UNKNOWN, classify(o));
    }

    @Test
    void testNoTcpAndControlUnreachableIsUnknown()
    {
        // Selbst die Referenz ist unten -> wir attestieren keine Sperre.
        Observation o = new Observation().dns(true, false, true).tcp(false).controlReachable(false);
        assertEquals(Verdict.UNKNOWN, classify(o));
    }

    @Test
    void testTlsOkButNoHttpResponseIsUnknown()
    {
        // TLS steht, aber keine HTTP-Antwort (httpStatus bleibt -1).
        Observation o = new Observation().dns(true, false, true).tcp(true).tlsRealSni(true);
        assertEquals(Verdict.UNKNOWN, classify(o));
    }

    @Test
    void testNullObservationIsUnknown()
    {
        assertEquals(Verdict.UNKNOWN, classify(null));
    }

    @Test
    void testIsBlockedCoversAllBlockVerdicts()
    {
        assertTrue(ServiceReachabilityAssessment.isBlocked(Verdict.DNS_BLOCKED));
        assertTrue(ServiceReachabilityAssessment.isBlocked(Verdict.CONNECTION_BLOCKED));
        assertTrue(ServiceReachabilityAssessment.isBlocked(Verdict.SNI_BLOCKED));
        assertTrue(ServiceReachabilityAssessment.isBlocked(Verdict.BLOCKPAGE));
        assertFalse(ServiceReachabilityAssessment.isBlocked(Verdict.REACHABLE));
        assertFalse(ServiceReachabilityAssessment.isBlocked(Verdict.SERVICE_DOWN));
        assertFalse(ServiceReachabilityAssessment.isBlocked(Verdict.UNKNOWN));
    }

    @Test
    void testVerdictKeyDistinctAndPresentForAllValues()
    {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Verdict v : Verdict.values())
            {
            String key = ServiceReachabilityAssessment.verdictKey(v);
            assertNotNull(key);
            assertTrue(key.startsWith("reachability.verdict."));
            assertTrue(keys.add(key), "Schluessel muss eindeutig sein: " + key);
            }
        assertEquals(Verdict.values().length, keys.size());
    }
}
