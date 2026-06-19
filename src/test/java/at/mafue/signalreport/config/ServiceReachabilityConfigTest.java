package at.mafue.signalreport.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import at.mafue.signalreport.config.ServiceTarget.ServiceKind;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer die Dienst-Erreichbarkeits-Konfiguration. Es wird bewusst direkt auf
 * den Objekten getestet (kein {@code Config.load}/{@code createDefault}), um den
 * globalen Config-Singleton -- von dem andere Tests abhaengen -- nicht zu beruehren.
 */
class ServiceReachabilityConfigTest
{
    @Test
    void testDefaultsDisabledAndSixHourInterval()
    {
        ServiceReachabilityConfig cfg = new ServiceReachabilityConfig();
        assertFalse(cfg.isEnabled(), "Feature muss standardmaessig deaktiviert sein");
        assertEquals(360, cfg.getIntervalMinutes(), "Standard-Takt muss 6 Stunden (360 min) sein");
        assertTrue(cfg.isUseControlSni(), "Kontroll-SNI-Test ist standardmaessig aktiv");
    }

    @Test
    void testDefaultServiceListContainsKnownServicesAndControl()
    {
        ServiceReachabilityConfig cfg = new ServiceReachabilityConfig();
        List<ServiceTarget> services = cfg.getServices();

        assertNotNull(services);
        assertFalse(services.isEmpty(), "Standard-Dienstliste darf nicht leer sein");
        assertTrue(services.stream().anyMatch(s -> "facebook".equals(s.getId())));
        assertTrue(services.stream().anyMatch(s -> "whatsapp".equals(s.getId())));
        assertTrue(services.stream().anyMatch(s -> s.getKind() == ServiceKind.CONTROL),
                "Es muss mindestens ein Referenz-Anker (CONTROL) vorhanden sein");
    }

    @Test
    void testIntervalIsClampedToBounds()
    {
        ServiceReachabilityConfig cfg = new ServiceReachabilityConfig();

        cfg.setIntervalMinutes(1);
        assertEquals(ServiceReachabilityConfig.MIN_INTERVAL_MINUTES, cfg.getIntervalMinutes(),
                "Zu kleiner Wert muss auf das Minimum angehoben werden");

        cfg.setIntervalMinutes(999_999);
        assertEquals(ServiceReachabilityConfig.MAX_INTERVAL_MINUTES, cfg.getIntervalMinutes(),
                "Zu grosser Wert muss auf das Maximum begrenzt werden");

        cfg.setIntervalMinutes(120);
        assertEquals(120, cfg.getIntervalMinutes(), "Werte im erlaubten Bereich bleiben unveraendert");
    }

    @Test
    void testEnableToggle()
    {
        ServiceReachabilityConfig cfg = new ServiceReachabilityConfig();
        cfg.setEnabled(true);
        assertTrue(cfg.isEnabled());
        cfg.setEnabled(false);
        assertFalse(cfg.isEnabled());
    }

    @Test
    void testServiceTargetDefaultsAndToggle()
    {
        ServiceTarget t = new ServiceTarget("facebook", "Facebook", "facebook.com", ServiceKind.WEB, true);
        assertTrue(t.isEnabled());
        assertEquals(443, t.getPort(), "Default-Port muss 443 sein");
        assertEquals(ServiceKind.WEB, t.getKind());

        t.setEnabled(false);
        assertFalse(t.isEnabled());
    }

    @Test
    void testServiceTargetNullSafety()
    {
        ServiceTarget t = new ServiceTarget();
        assertNotNull(t.getId());
        assertNotNull(t.getDomain());
        assertNotNull(t.getDisplayName());

        t.setDomain(null);
        assertEquals("", t.getDomain(), "null-Domain wird zu Leerstring normalisiert");
        t.setKind(null);
        assertEquals(ServiceKind.WEB, t.getKind(), "null-Kind faellt auf WEB zurueck");
        t.setPort(0);
        assertEquals(443, t.getPort(), "ungueltiger Port faellt auf 443 zurueck");
    }

    @Test
    void testConfigLazyGetterNeverNull()
    {
        // Frische Config-Instanz ohne Singleton-Seiteneffekte.
        Config c = new Config();
        assertNotNull(c.getServiceReachability());
        assertFalse(c.getServiceReachability().isEnabled());

        c.getServiceReachability().setServices(null);
        assertNotNull(c.getServiceReachability().getServices(), "getServices darf nie null liefern");
    }

    @Test
    void testJsonRoundTrip() throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();

        ServiceReachabilityConfig original = new ServiceReachabilityConfig();
        original.setEnabled(true);
        original.setIntervalMinutes(120);

        String json = mapper.writeValueAsString(original);
        ServiceReachabilityConfig restored = mapper.readValue(json, ServiceReachabilityConfig.class);

        assertTrue(restored.isEnabled());
        assertEquals(120, restored.getIntervalMinutes());
        assertFalse(restored.getServices().isEmpty());
        assertEquals(original.getServices().size(), restored.getServices().size());
        // Enum-Werte muessen den JSON-Round-Trip ueberstehen
        assertTrue(restored.getServices().stream().anyMatch(s -> s.getKind() == ServiceKind.CONTROL));
    }
}
