package at.mafue.signalreport.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import at.mafue.signalreport.config.ServiceTarget.ServiceKind;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer die Dienst-Erreichbarkeits-Konfiguration. Es wird bewusst direkt auf
 * den Objekten getestet (kein {@code Config.load}/{@code createDefault}), um den
 * globalen Config-Singleton -- von dem andere Tests abhaengen -- nicht zu beruehren.
 */
class ServiceReachabilityConfigTest
{
    private static ServiceTarget byId(List<ServiceTarget> list, String id)
    {
        return list.stream().filter(t -> id.equals(t.getId())).findFirst().orElseThrow();
    }

    @Test
    void testDefaultsDisabledAndSixHourInterval()
    {
        ServiceReachabilityConfig cfg = new ServiceReachabilityConfig();
        assertFalse(cfg.isEnabled(), "Feature muss standardmaessig deaktiviert sein");
        assertEquals(360, cfg.getIntervalMinutes(), "Standard-Takt muss 6 Stunden (360 min) sein");
        assertTrue(cfg.isUseControlSni(), "Kontroll-SNI-Test ist standardmaessig aktiv");
        assertTrue(cfg.getServiceOverrides().isEmpty());
        assertTrue(cfg.getCustomServices().isEmpty());
    }

    @Test
    void testDefaultServiceListComesFromCatalog()
    {
        List<ServiceTarget> services = new ServiceReachabilityConfig().effectiveServices();

        assertEquals(BuiltinCatalog.services().size(), services.size());
        assertTrue(byId(services, "facebook").isEnabled());
        assertFalse(byId(services, "tiktok").isEnabled());
        assertEquals(ServiceKind.MESSENGER, byId(services, "whatsapp").getKind());
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

        c.getServiceReachability().setServiceOverrides(null);
        c.getServiceReachability().setCustomServices(null);
        assertNotNull(c.getServiceReachability().getServiceOverrides(), "getServiceOverrides darf nie null liefern");
        assertNotNull(c.getServiceReachability().getCustomServices(), "getCustomServices darf nie null liefern");
        assertFalse(c.getServiceReachability().effectiveServices().isEmpty());
    }

    @Test
    void testSwitchStoresOnlyDeviationsFromDefault()
    {
        ServiceReachabilityConfig cfg = new ServiceReachabilityConfig();

        cfg.applyServiceSwitch("facebook", false);   // Standard: ein
        cfg.applyServiceSwitch("tiktok", true);      // Standard: aus
        cfg.applyServiceSwitch("youtube", true);     // wie Standard: keine Abweichung
        cfg.applyServiceSwitch("gibt-es-nicht", true);
        assertEquals(Map.of("facebook", false, "tiktok", true), cfg.getServiceOverrides());

        List<ServiceTarget> services = cfg.effectiveServices();
        assertFalse(byId(services, "facebook").isEnabled());
        assertTrue(byId(services, "tiktok").isEnabled());
        assertTrue(byId(services, "youtube").isEnabled());

        cfg.applyServiceSwitch("facebook", true);    // zurueck zum Standard: Abweichung entfaellt
        assertEquals(Map.of("tiktok", true), cfg.getServiceOverrides());
    }

    @Test
    void testAdoptLegacyServices()
    {
        ServiceReachabilityConfig cfg = new ServiceReachabilityConfig();
        cfg.setLegacyServices(List.of(
                new ServiceTarget("facebook", "Facebook", "facebook.com", ServiceKind.WEB, false),   // abweichend
                new ServiceTarget("tiktok", "TikTok", "tiktok.com", ServiceKind.WEB, true),           // abweichend
                new ServiceTarget("whatsapp", "WhatsApp", "web.whatsapp.com", ServiceKind.MESSENGER, true), // wie Standard
                new ServiceTarget("orf", "ORF", "orf.at", ServiceKind.WEB, true),                      // eigener Dienst
                new ServiceTarget("", "Ohne id", "x.example", ServiceKind.WEB, true)));                // verworfen

        assertTrue(cfg.adoptLegacyServices());
        assertEquals(Map.of("facebook", false, "tiktok", true), cfg.getServiceOverrides());
        assertEquals(List.of("orf"), cfg.getCustomServices().stream().map(ServiceTarget::getId).toList());

        List<ServiceTarget> services = cfg.effectiveServices();
        assertEquals(BuiltinCatalog.services().size() + 1, services.size(),
                "alle mitgelieferten Dienste, auch die in der alten Liste fehlenden, plus der eigene");
        assertFalse(byId(services, "signal").isEnabled(), "in der alten Liste fehlender Dienst kommt mit seinem Standard");
        assertTrue(byId(services, "orf").isEnabled());

        assertFalse(cfg.adoptLegacyServices(), "nur einmal");
    }

    @Test
    void testSwitchOfCustomService()
    {
        ServiceReachabilityConfig cfg = new ServiceReachabilityConfig();
        cfg.setCustomServices(List.of(new ServiceTarget("orf", "ORF", "orf.at", ServiceKind.WEB, true)));
        cfg.applyServiceSwitch("orf", false);
        assertFalse(byId(cfg.effectiveServices(), "orf").isEnabled());
        assertTrue(cfg.getServiceOverrides().isEmpty(), "eigene Dienste brauchen keine Abweichung");
    }

    @Test
    void testJsonRoundTrip() throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();

        ServiceReachabilityConfig original = new ServiceReachabilityConfig();
        original.setEnabled(true);
        original.setIntervalMinutes(120);
        original.applyServiceSwitch("tiktok", true);
        original.setCustomServices(List.of(new ServiceTarget("signal-eigen", "Signal (eigen)", "signal.example", ServiceKind.MESSENGER, true)));

        String json = mapper.writeValueAsString(original);
        assertFalse(json.contains("\"services\""), "die komplette Liste wird nicht mehr geschrieben: " + json);
        ServiceReachabilityConfig restored = mapper.readValue(json, ServiceReachabilityConfig.class);

        assertTrue(restored.isEnabled());
        assertEquals(120, restored.getIntervalMinutes());
        assertEquals(Map.of("tiktok", true), restored.getServiceOverrides());
        assertEquals(original.effectiveServices().size(), restored.effectiveServices().size());
        // Enum-Werte muessen den JSON-Round-Trip ueberstehen
        assertEquals(ServiceKind.MESSENGER, byId(restored.effectiveServices(), "signal-eigen").getKind());
        assertTrue(restored.effectiveServices().stream().anyMatch(s -> s.getKind() == ServiceKind.CONTROL));
    }
}
