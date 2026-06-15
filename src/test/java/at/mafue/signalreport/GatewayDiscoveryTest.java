package at.mafue.signalreport;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer die reine Parse-/Klassifizierungs-Logik der Gateway-Erkennung.
 * Die Systemaufrufe (Traceroute) werden nicht getestet, sondern reale
 * Beispiel-Ausgaben durch die statischen Hilfsmethoden geschickt.
 */
class GatewayDiscoveryTest
{
    // --- isPrivateLan: RFC-1918-Bereiche ---

    @Test
    void testPrivateRangesAreRecognised()
    {
        assertTrue(GatewayDiscovery.isPrivateLan("192.168.20.1"));
        assertTrue(GatewayDiscovery.isPrivateLan("192.168.0.1"));
        assertTrue(GatewayDiscovery.isPrivateLan("10.0.0.1"));
        assertTrue(GatewayDiscovery.isPrivateLan("10.255.255.254"));
        assertTrue(GatewayDiscovery.isPrivateLan("172.16.0.1"));
        assertTrue(GatewayDiscovery.isPrivateLan("172.31.255.254"));
    }

    @Test
    void testPublicAndBoundaryAddressesAreNotPrivate()
    {
        assertFalse(GatewayDiscovery.isPrivateLan("217.25.120.3"));   // oeffentlich
        assertFalse(GatewayDiscovery.isPrivateLan("8.8.8.8"));
        assertFalse(GatewayDiscovery.isPrivateLan("100.64.0.1"));     // CGNAT = ISP-Seite
        assertFalse(GatewayDiscovery.isPrivateLan("172.15.0.1"));     // knapp unter dem Bereich
        assertFalse(GatewayDiscovery.isPrivateLan("172.32.0.1"));     // knapp ueber dem Bereich
        assertFalse(GatewayDiscovery.isPrivateLan("192.167.0.1"));    // nicht 192.168
        assertFalse(GatewayDiscovery.isPrivateLan("kein.ip"));
    }

    // --- firstIPv4 ---

    @Test
    void testFirstIPv4Extraction()
    {
        assertEquals("192.168.20.1",
                GatewayDiscovery.firstIPv4("  1    <1 ms    <1 ms    <1 ms  192.168.20.1"));
        assertEquals("217.25.120.3",
                GatewayDiscovery.firstIPv4("  3    10 ms     7 ms     8 ms  at-vie09c-rt01.as8412.net [217.25.120.3]"));
        assertNull(GatewayDiscovery.firstIPv4("  4     *        *        *     Zeitueberschreitung der Anforderung."));
        assertNull(GatewayDiscovery.firstIPv4("keine adresse hier"));
        // ungueltige Oktette werden nicht akzeptiert
        assertNull(GatewayDiscovery.firstIPv4("999.999.999.999"));
    }

    // --- parseHops + extractLocalChain: echte Windows-tracert-Ausgabe (Double-NAT) ---

    @Test
    void testRealWindowsTraceDoubleNat()
    {
        List<String> output = Arrays.asList(
                "",
                "Routenverfolgung zu www.magenta.at [212.166.122.42]",
                "ueber maximal 30 Hops:",
                "",
                "  1    <1 ms    <1 ms    <1 ms  192.168.20.1",
                "  2     1 ms     2 ms     2 ms  192.168.0.1",
                "  3    10 ms     7 ms     8 ms  at-vie09c-rt01.as8412.net [217.25.120.3]",
                "  4    11 ms    10 ms     9 ms  at-vie09c-rc01.as8412.net [217.25.122.250]",
                "  5    10 ms    10 ms    10 ms  217-25-122-182.static.upcbusiness.at [217.25.122.182]"
        );

        List<String> hops = GatewayDiscovery.parseHops(output);
        // Kopfzeile mit Ziel-IP 212.166.122.42 darf NICHT als Hop auftauchen
        assertFalse(hops.contains("212.166.122.42"));
        assertEquals(List.of("192.168.20.1", "192.168.0.1", "217.25.120.3",
                "217.25.122.250", "217.25.122.182"), hops);

        List<String> chain = GatewayDiscovery.extractLocalChain(hops);
        assertEquals(List.of("192.168.20.1", "192.168.0.1"), chain);
        // nah = Router, fern = Pforte ins Internet
        assertEquals("192.168.20.1", chain.get(0));
        assertEquals("192.168.0.1", chain.get(chain.size() - 1));
    }

    // --- Linux traceroute -n ---

    @Test
    void testLinuxTrace()
    {
        List<String> output = Arrays.asList(
                "traceroute to 1.1.1.1 (1.1.1.1), 8 hops max, 60 byte packets",
                " 1  192.168.20.1  0.512 ms  0.480 ms  0.450 ms",
                " 2  192.168.0.1  1.2 ms  1.1 ms  1.0 ms",
                " 3  217.25.120.3  8.0 ms  7.5 ms  7.8 ms"
        );

        List<String> chain = GatewayDiscovery.extractLocalChain(GatewayDiscovery.parseHops(output));
        assertEquals(List.of("192.168.20.1", "192.168.0.1"), chain);
    }

    // --- Einzelner Router: nah == fern ---

    @Test
    void testSingleRouter()
    {
        List<String> output = Arrays.asList(
                "  1    <1 ms    <1 ms    <1 ms  192.168.1.1",
                "  2    12 ms    11 ms    10 ms  93.184.216.34"
        );
        List<String> chain = GatewayDiscovery.extractLocalChain(GatewayDiscovery.parseHops(output));
        assertEquals(List.of("192.168.1.1"), chain);
        assertEquals(chain.get(0), chain.get(chain.size() - 1));
    }

    // --- CGNAT direkt hinter dem Router: lokale Kette endet am Router ---

    @Test
    void testCgnatBoundary()
    {
        List<String> output = Arrays.asList(
                "  1    <1 ms    <1 ms    <1 ms  192.168.1.1",
                "  2    12 ms    11 ms    10 ms  100.64.0.1",
                "  3    15 ms    14 ms    13 ms  93.184.216.34"
        );
        List<String> chain = GatewayDiscovery.extractLocalChain(GatewayDiscovery.parseHops(output));
        assertEquals(List.of("192.168.1.1"), chain);
    }

    // --- Timeout-Hop beendet die lokale Kette konservativ ---

    @Test
    void testTimeoutStopsChain()
    {
        List<String> output = Arrays.asList(
                "  1    <1 ms    <1 ms    <1 ms  192.168.1.1",
                "  2     *        *        *     Zeitueberschreitung der Anforderung.",
                "  3    10 ms     7 ms     8 ms  93.184.216.34"
        );
        List<String> hops = GatewayDiscovery.parseHops(output);
        assertEquals(Arrays.asList("192.168.1.1", null, "93.184.216.34"), hops);

        List<String> chain = GatewayDiscovery.extractLocalChain(hops);
        assertEquals(List.of("192.168.1.1"), chain);
    }

    // --- Keine lokale Kette (direkt oeffentlich) ---

    @Test
    void testNoLocalChain()
    {
        List<String> output = Arrays.asList(
                "  1    10 ms    10 ms    10 ms  93.184.216.34"
        );
        List<String> chain = GatewayDiscovery.extractLocalChain(GatewayDiscovery.parseHops(output));
        assertTrue(chain.isEmpty());
    }

    // --- Virtuelle Gateway-Bereiche (VM/Docker-NAT) ---

    @Test
    void testVirtualGatewayRanges()
    {
        assertTrue(GatewayDiscovery.isVirtualGatewayRange("172.17.0.1"));    // Docker Default-Bridge
        assertTrue(GatewayDiscovery.isVirtualGatewayRange("10.0.2.2"));      // VirtualBox/QEMU NAT
        assertTrue(GatewayDiscovery.isVirtualGatewayRange("10.0.2.3"));
        assertFalse(GatewayDiscovery.isVirtualGatewayRange("192.168.20.1")); // echtes Heimnetz
        assertFalse(GatewayDiscovery.isVirtualGatewayRange("172.16.0.1"));   // 172.16, nicht .17
        assertFalse(GatewayDiscovery.isVirtualGatewayRange("10.0.3.1"));     // nicht 10.0.2.x
        assertFalse(GatewayDiscovery.isVirtualGatewayRange("8.8.8.8"));
    }

    @Test
    void testDockerLikeRange()
    {
        assertTrue(GatewayDiscovery.isDockerLikeRange("172.17.0.1"));
        assertTrue(GatewayDiscovery.isDockerLikeRange("172.18.0.1"));        // user-defined bridge
        assertTrue(GatewayDiscovery.isDockerLikeRange("172.31.255.254"));
        assertFalse(GatewayDiscovery.isDockerLikeRange("172.15.0.1"));
        assertFalse(GatewayDiscovery.isDockerLikeRange("172.32.0.1"));
        assertFalse(GatewayDiscovery.isDockerLikeRange("192.168.1.1"));
    }

    // --- stripVirtualLeadingHops: Docker-Bridge ueberspringen ---

    @Test
    void testStripDockerBridgeKeepsRealRouter()
    {
        // Container sieht zuerst die docker0-Bridge, dann den echten Router
        assertEquals(List.of("192.168.20.1"),
                GatewayDiscovery.stripVirtualLeadingHops(List.of("172.17.0.1", "192.168.20.1")));
    }

    @Test
    void testStripKeepsLastHopEvenIfVirtual()
    {
        // Reine NAT-Pforte ohne sichtbaren Router (z. B. VirtualBox): bleibt als einziger Hop
        assertEquals(List.of("10.0.2.2"),
                GatewayDiscovery.stripVirtualLeadingHops(List.of("10.0.2.2")));
        assertEquals(List.of("172.17.0.1"),
                GatewayDiscovery.stripVirtualLeadingHops(List.of("172.17.0.1")));
    }

    @Test
    void testStripLeavesRealChainUntouched()
    {
        List<String> chain = List.of("192.168.20.1", "192.168.0.1");
        assertEquals(chain, GatewayDiscovery.stripVirtualLeadingHops(chain));
    }

    @Test
    void testDockerTraceEndToEnd()
    {
        // Simulierte Container-Traceroute: docker0-Bridge, echter Router, ISP
        List<String> output = Arrays.asList(
                "traceroute to 1.1.1.1 (1.1.1.1), 8 hops max, 60 byte packets",
                " 1  172.17.0.1  0.1 ms  0.1 ms  0.1 ms",
                " 2  192.168.20.1  0.5 ms  0.4 ms  0.4 ms",
                " 3  217.25.120.3  8.0 ms  7.5 ms  7.8 ms"
        );
        List<String> chain = GatewayDiscovery.stripVirtualLeadingHops(
                GatewayDiscovery.extractLocalChain(GatewayDiscovery.parseHops(output)));
        // Bridge entfernt, echter Router bleibt als nah == fern
        assertEquals(List.of("192.168.20.1"), chain);
    }
}
