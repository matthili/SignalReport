package at.mafue.signalreport.config;

import at.mafue.signalreport.config.ServiceTarget.ServiceKind;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Die mitgelieferten Listen im Programm (/catalog/*.json): vollstaendig lesbar, nur gueltige
 * IP-Adressen ohne Dubletten, eindeutige Dienst-ids, und es werden immer Kopien herausgegeben.
 */
class BuiltinCatalogTest
{
    @Test
    void testDnsCatalogContainsOnlyValidUniqueServers()
    {
        List<DnsServer> servers = BuiltinCatalog.dnsServers();
        assertFalse(servers.isEmpty(), "die mitgelieferte DNS-Liste darf nicht leer sein");

        Set<String> keys = new HashSet<>();
        for (DnsServer d : servers)
            {
            assertNotNull(DnsBenchmarkConfig.canonicalAddress(d.getAddress()), "keine gueltige IP-Adresse: " + d.getAddress());
            assertTrue(keys.add(DnsBenchmarkConfig.addressKey(d.getAddress())), "doppelte Adresse: " + d.getAddress());
            assertFalse(d.getName() == null || d.getName().isBlank(), "Name fehlt bei " + d.getAddress());
            assertFalse(d.getRegion() == null || d.getRegion().isBlank(), "Region fehlt bei " + d.getAddress());
            assertFalse(d.getProvider() == null || d.getProvider().isBlank(), "Anbieter fehlt bei " + d.getAddress());
            assertFalse(d.isCustom(), "mitgelieferte Server sind nie eigene");
            }

        // Stichproben aus der bisherigen Liste in Config.java, inkl. der zuletzt ergaenzten DNS4EU-Server
        assertTrue(servers.stream().anyMatch(d -> "8.8.8.8".equals(d.getAddress()) && "Google Primary".equals(d.getName())));
        assertTrue(servers.stream().anyMatch(d -> "86.54.11.100".equals(d.getAddress()) && "whalebone".equals(d.getProvider())));
        assertTrue(servers.stream().anyMatch(d -> "86.54.11.200".equals(d.getAddress())));
    }

    @Test
    void testDnsCatalogHandsOutCopies()
    {
        BuiltinCatalog.dnsServers().get(0).setName("veraendert");
        assertNotEquals("veraendert", BuiltinCatalog.dnsServers().get(0).getName());
    }

    @Test
    void testServiceCatalogContainsKnownServicesWithDefaults()
    {
        List<ServiceTarget> services = BuiltinCatalog.services();
        assertFalse(services.isEmpty());

        Set<String> ids = new HashSet<>();
        for (ServiceTarget t : services)
            {
            assertFalse(t.getId().isBlank());
            assertTrue(ids.add(t.getId()), "doppelte Dienst-id: " + t.getId());
            assertFalse(t.getDomain().isBlank(), "Domain fehlt bei " + t.getId());
            assertFalse(t.getDisplayName().isBlank(), "Anzeigename fehlt bei " + t.getId());
            }

        ServiceTarget facebook = BuiltinCatalog.servicesById().get("facebook");
        assertNotNull(facebook);
        assertTrue(facebook.isEnabled(), "Facebook ist standardmaessig eingeschaltet");
        assertFalse(BuiltinCatalog.servicesById().get("tiktok").isEnabled(), "TikTok ist standardmaessig ausgeschaltet");
        assertEquals(ServiceKind.MESSENGER, BuiltinCatalog.servicesById().get("whatsapp").getKind());
        assertTrue(services.stream().anyMatch(t -> t.getKind() == ServiceKind.CONTROL), "Referenz-Anker (CONTROL) fehlt");
    }

    @Test
    void testServiceCatalogHandsOutCopies()
    {
        BuiltinCatalog.services().get(0).setEnabled(false);
        BuiltinCatalog.servicesById().get("facebook").setEnabled(false);
        assertTrue(BuiltinCatalog.servicesById().get("facebook").isEnabled());
        assertTrue(BuiltinCatalog.services().get(0).isEnabled());
    }
}
