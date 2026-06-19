package at.mafue.signalreport.network;

import at.mafue.signalreport.config.ServiceReachabilityConfig;
import at.mafue.signalreport.config.ServiceTarget;
import at.mafue.signalreport.config.ServiceTarget.ServiceKind;
import at.mafue.signalreport.report.ServiceReachabilityAssessment.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Echter Netz-Zugriff -- daher aus der CI ausgeklammert: laeuft nur, wenn die
 * Umgebungsvariable {@code PROBE_SMOKE} gesetzt ist, sonst werden die Tests
 * uebersprungen (nicht als Fehler gewertet). Dient der manuellen Verifikation der
 * Probe gegen echte Domains.
 */
class ServiceReachabilityProbeSmokeTest
{
    private static boolean smokeEnabled()
    {
        return System.getenv("PROBE_SMOKE") != null;
    }

    @Test
    void smokeReferenceIsReachable()
    {
        assumeTrue(smokeEnabled(), "PROBE_SMOKE nicht gesetzt -- echter Netz-Test uebersprungen");

        ServiceReachabilityProbe probe = new ServiceReachabilityProbe();
        ServiceReachabilityResult r = probe.probeOne(
                new ServiceTarget("control-example", "Referenz", "example.com", ServiceKind.CONTROL, true),
                true, true);

        System.out.println("[SMOKE] example.com -> " + r.getVerdict()
                + " (" + r.getMethod() + ", ip=" + r.getResolvedIp() + ", http=" + r.getHttpStatus() + ")");
        assertNotNull(r.getVerdict());
        assertEquals(Verdict.REACHABLE, r.getVerdict(), "example.com sollte erreichbar sein");
    }

    @Test
    void smokeProbeAllDefaults()
    {
        assumeTrue(smokeEnabled(), "PROBE_SMOKE nicht gesetzt -- echter Netz-Test uebersprungen");

        ServiceReachabilityConfig cfg = new ServiceReachabilityConfig();
        ServiceReachabilityProbe probe = new ServiceReachabilityProbe();
        List<ServiceReachabilityResult> results = probe.probeAll(cfg);

        for (ServiceReachabilityResult r : results)
            {
            System.out.println("[SMOKE] " + r.getServiceId() + " -> " + r.getVerdict()
                    + " (" + r.getMethod() + ", ip=" + r.getResolvedIp() + ")");
            }
        assertFalse(results.isEmpty(), "Es sollten Ergebnisse fuer die aktiven Default-Dienste vorliegen");
    }
}
