package at.mafue.signalreport.config;

import at.mafue.signalreport.config.DnsBenchmarkConfig.Problem;
import at.mafue.signalreport.config.DnsBenchmarkConfig.Scope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static at.mafue.signalreport.config.DnsBenchmarkConfig.canonicalAddress;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Nutzer-Teil des DNS-Benchmarks: Adresspruefung ohne DNS-Aufloesung, Zusammenstellung der
 * Benchmark-Liste, Pruefung neuer Eintraege und Uebernahme alter config.json-Dateien.
 * Ohne Config-Singleton (nur Objekte und ObjectMapper).
 */
class DnsBenchmarkConfigTest
{
    private static CustomDnsServer own(String name, String address)
    {
        return new CustomDnsServer(name, address, "", true);
    }

    private static List<String> keys(List<Problem> problems)
    {
        return problems.stream().map(Problem::key).toList();
    }

    @Test
    void testCanonicalAddressAcceptsIpLiterals()
    {
        assertEquals("8.8.8.8", canonicalAddress("8.8.8.8"));
        assertEquals("192.168.20.1", canonicalAddress("  192.168.20.1 "));
        assertEquals("2001:4860:4860:0:0:0:0:8888", canonicalAddress("2001:4860:4860::8888"));
        assertEquals(canonicalAddress("2001:4860:4860::8888"), canonicalAddress("2001:4860:4860:0:0::8888"),
                "verschiedene Schreibweisen derselben IPv6-Adresse ergeben denselben Schluessel");
        assertEquals("0:0:0:0:0:0:0:1", canonicalAddress("::1"));
    }

    @Test
    void testCanonicalAddressRejectsHostnamesAndInvalidInput()
    {
        String[] rejected = {null, "", "   ", "dns.google", "localhost", "256.1.1.1", "1.2.3", "1.2.3.4.5",
                "01.2.3.4", "1.2.3.4:53", "8.8.8.8/32", "fe80::1%eth0", "[::1]", "2001:::1", "::", "0.0.0.0",
                "224.0.0.1", "ff02::1", "a".repeat(50)};
        for (String s : rejected)
            {
            assertNull(canonicalAddress(s), "muss abgelehnt werden: " + s);
            }
    }

    @Test
    void testServersForFullAndCustomRun()
    {
        DnsBenchmarkConfig cfg = new DnsBenchmarkConfig();
        int builtins = BuiltinCatalog.dnsServers().size();
        assertEquals(builtins, cfg.serversFor(Scope.ALL).size(), "ohne eigenen Teil: genau die mitgelieferte Liste");
        assertTrue(cfg.serversFor(Scope.CUSTOM).isEmpty());

        cfg.setHiddenBuiltins(List.of("8.8.8.8", "1.1.1.1"));
        cfg.setCustomServers(List.of(
                new CustomDnsServer("Router", "192.168.20.1", "Heimnetz", true),
                new CustomDnsServer("Nur manchmal", "192.168.20.2", "", false)));

        List<DnsServer> all = cfg.serversFor(Scope.ALL);
        assertEquals(builtins - 2 + 1, all.size(), "ohne 2 ausgeblendete, plus 1 eingeschalteter eigener");
        assertTrue(all.stream().noneMatch(s -> "8.8.8.8".equals(s.getAddress())));
        assertTrue(all.stream().noneMatch(s -> "192.168.20.2".equals(s.getAddress())), "ausgeschalteter eigener fehlt im vollen Lauf");
        DnsServer router = all.stream().filter(DnsServer::isCustom).findFirst().orElseThrow();
        assertEquals("Router", router.getName());
        assertEquals("192.168.20.1", router.getAddress());
        assertEquals("Heimnetz", router.getRegion(), "die Notiz wird in der Ergebnis-Kachel gezeigt");

        List<DnsServer> custom = cfg.serversFor(Scope.CUSTOM);
        assertEquals(List.of("192.168.20.1", "192.168.20.2"), custom.stream().map(DnsServer::getAddress).toList(),
                "\"Nur eigene testen\" prueft alle eigenen, auch ausgeschaltete");
        assertTrue(custom.stream().allMatch(DnsServer::isCustom));
    }

    @Test
    void testOwnEntryReplacesBuiltinWithSameAddress()
    {
        DnsBenchmarkConfig cfg = new DnsBenchmarkConfig();
        cfg.setCustomServers(List.of(own("Mein Google", "8.8.8.8")));
        List<DnsServer> all = cfg.serversFor(Scope.ALL);
        List<DnsServer> google = all.stream().filter(s -> "8.8.8.8".equals(s.getAddress())).toList();
        assertEquals(1, google.size(), "gleiche Adresse nur einmal");
        assertTrue(google.get(0).isCustom());
        assertEquals("Mein Google", google.get(0).getName());
        assertEquals(BuiltinCatalog.dnsServers().size(), all.size());
    }

    @Test
    void testValidateRejectsBadEntries()
    {
        List<CustomDnsServer> none = List.of();
        assertEquals(List.of("api.dnsEntryIncomplete"), keys(DnsBenchmarkConfig.validate(List.of(own("", "1.2.3.4")), none)));
        assertEquals(List.of("api.dnsEntryIncomplete"), keys(DnsBenchmarkConfig.validate(List.of(own("Leer", "")), none)));

        List<Problem> hostname = DnsBenchmarkConfig.validate(List.of(own("Google", "dns.google")), none);
        assertEquals(List.of("api.dnsInvalidAddress"), keys(hostname));
        assertEquals("dns.google", hostname.get(0).value());

        assertEquals(List.of("api.dnsDuplicate"),
                keys(DnsBenchmarkConfig.validate(List.of(own("A", "192.0.2.53"), own("B", "192.0.2.53")), none)));

        List<Problem> builtin = DnsBenchmarkConfig.validate(List.of(own("Google", "8.8.8.8")), none);
        assertEquals(List.of("api.dnsIsBuiltin"), keys(builtin));
        assertEquals("Google Primary", builtin.get(0).value(), "die Meldung nennt den mitgelieferten Server");

        List<CustomDnsServer> tooMany = new ArrayList<>();
        for (int i = 1; i <= DnsBenchmarkConfig.MAX_CUSTOM_SERVERS + 1; i++)
            {
            tooMany.add(own("S" + i, "10.0.0." + i));
            }
        assertTrue(keys(DnsBenchmarkConfig.validate(tooMany, none)).contains("api.dnsTooMany"));

        assertTrue(DnsBenchmarkConfig.validate(List.of(own("Router", "192.168.20.1"), own("v6", "2001:db8::53")), none).isEmpty());
    }

    @Test
    void testValidateKeepsStoredLegacyEntries()
    {
        List<CustomDnsServer> stored = List.of(own("Alter Hostname", "dns.example.org"), own("Alt-Google", "8.8.4.4"));
        List<CustomDnsServer> resubmitted = List.of(own("Alter Hostname", "dns.example.org"), own("Alt-Google", "8.8.4.4"),
                own("Neu", "192.168.20.1"));
        assertTrue(DnsBenchmarkConfig.validate(resubmitted, stored).isEmpty(),
                "gespeicherter Altbestand bleibt erlaubt, auch wenn er heute abgelehnt wuerde");
        assertEquals(List.of("api.dnsInvalidAddress"),
                keys(DnsBenchmarkConfig.validate(List.of(own("Neuer Hostname", "neu.example.org")), stored)),
                "neue Hostnamen werden weiterhin abgelehnt");
    }

    @Test
    void testAdoptLegacyListKeepsEverythingThatIsNotBuiltIn()
    {
        DnsBenchmarkConfig cfg = new DnsBenchmarkConfig();
        List<DnsServer> legacy = List.of(
                new DnsServer("Google Primary", "8.8.8.8", "Nordamerika", "Google"),      // mitgeliefert
                new DnsServer("Cloudflare", "1.1.1.1", "Nordamerika", "Cloudflare"),      // mitgeliefert, anderer Name
                new DnsServer("Mein Router", "192.168.20.1", "Europa", "Unknown"),         // eigener
                new DnsServer("Alter Hostname", "dns.example.org", "", "Beispiel"),        // eigener (Altbestand)
                new DnsServer("", "   ", "", ""),                                          // leer: verworfen
                new DnsServer("", "10.0.0.53", "", ""));                                   // ohne Namen

        assertEquals(3, cfg.adoptLegacyList(legacy));
        List<CustomDnsServer> own = cfg.getCustomServers();
        assertEquals(List.of("192.168.20.1", "dns.example.org", "10.0.0.53"),
                own.stream().map(CustomDnsServer::getAddress).toList());
        assertEquals("Europa", own.get(0).getNote(), "\"Unknown\" entfaellt in der Notiz");
        assertEquals("Beispiel", own.get(1).getNote());
        assertEquals("10.0.0.53", own.get(2).getName(), "ohne Namen gilt die Adresse als Name");
        assertTrue(own.stream().allMatch(CustomDnsServer::isEnabled));
        assertTrue(cfg.getHiddenBuiltins().isEmpty(), "in der alten Liste fehlende mitgelieferte Server sind neu, nicht ausgeblendet");

        assertEquals(0, cfg.adoptLegacyList(legacy), "ein zweiter Lauf legt nichts doppelt an");
        assertEquals(3, cfg.getCustomServers().size());
    }

    @Test
    void testConfigMigrationDropsLegacyKeysAndKeepsUserChoices() throws Exception
    {
        String oldFormat = """
                {
                  "dnsServers": [
                    { "name": "Google Primary", "address": "8.8.8.8", "region": "Nordamerika", "provider": "Google" },
                    { "name": "Mein Router", "address": "192.168.20.1", "region": "Europa", "provider": "Heimnetz" }
                  ],
                  "serviceReachability": {
                    "enabled": true,
                    "services": [
                      { "id": "facebook", "displayName": "Facebook", "domain": "facebook.com", "kind": "WEB", "enabled": false },
                      { "id": "tiktok", "displayName": "TikTok", "domain": "tiktok.com", "kind": "WEB", "enabled": false }
                    ]
                  }
                }
                """;
        ObjectMapper mapper = new ObjectMapper();
        Config c = mapper.readValue(oldFormat, Config.class);

        assertTrue(c.migrateLegacyLists());
        assertEquals(1, c.getDnsBenchmark().getCustomServers().size());
        assertEquals("Mein Router", c.getDnsBenchmark().getCustomServers().get(0).getName());
        assertEquals(Map.of("facebook", false), c.getServiceReachability().getServiceOverrides(),
                "nur der vom Standard abweichende Schalter wird gemerkt");
        assertTrue(c.getServiceReachability().isEnabled());

        String written = mapper.writeValueAsString(c);
        assertFalse(written.contains("\"dnsServers\""), written);
        assertFalse(written.contains("\"services\""), written);
        assertFalse(written.contains("legacy"), written);
        assertTrue(written.contains("\"dnsBenchmark\""), written);
        assertTrue(written.contains("\"serviceOverrides\""), written);

        Config reread = mapper.readValue(written, Config.class);
        assertFalse(reread.migrateLegacyLists(), "neues Format: nichts mehr zu uebernehmen");
        assertEquals(1, reread.getDnsBenchmark().getCustomServers().size());
        assertEquals(Map.of("facebook", false), reread.getServiceReachability().getServiceOverrides());
        assertEquals(BuiltinCatalog.dnsServers().size() + 1, reread.getDnsBenchmark().serversFor(Scope.ALL).size(),
                "alle mitgelieferten Server plus der eigene, auch die in der alten Liste fehlenden");
    }
}
