package at.mafue.signalreport.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ermittelt die lokale Gateway-Kette zwischen diesem Rechner und dem Internet.
 * <p>
 * Per Traceroute zu einem stabilen oeffentlichen Anker (1.1.1.1) wird der Pfad
 * verfolgt; alle Hops im privaten Adressbereich (RFC 1918) am Anfang der Route
 * bilden das lokale Netz. Der erste oeffentliche Hop markiert den Uebergang zum
 * ISP. Aus dieser Kette interessieren genau zwei Punkte:
 * <ul>
 *   <li><b>nah</b>  = erster privater Hop = der unmittelbare Router</li>
 *   <li><b>fern</b> = letzter privater Hop = die "Pforte ins Internet"
 *       (bei nur einem Gateway identisch mit "nah")</li>
 * </ul>
 * Schlaegt der Traceroute fehl oder ist das Programm nicht installiert (manche
 * NAS), wird auf das Default-Gateway der Routing-Tabelle zurueckgefallen.
 * <p>
 * Alle reinen Parse-/Klassifizierungs-Methoden sind statisch und ohne
 * Systemaufruf testbar; nur {@link #discoverLocalChain()} ruft das OS auf.
 */
public final class GatewayDiscovery
{
    private static final Logger logger = LoggerFactory.getLogger(GatewayDiscovery.class);

    /** Messungs-Typ fuer den naechsten (unmittelbaren) Gateway. */
    public static final String TYPE_NEAR = "GATEWAY_NEAR";
    /** Messungs-Typ fuer den weitesten lokalen Gateway (Pforte ins Internet). */
    public static final String TYPE_FAR = "GATEWAY_FAR";

    private static final String TRACE_ANCHOR = "1.1.1.1";
    private static final int MAX_HOPS = 8;

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase().contains("mac");

    private static final Pattern IPV4 = Pattern.compile(
            "\\b(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\b");
    // Eine Hop-Zeile beginnt (nach optionalem Leerraum) mit der Hop-Nummer.
    private static final Pattern HOP_LINE = Pattern.compile("^\\s*\\d+\\s");

    private GatewayDiscovery()
    {
    }

    // ========================================================================
    //  Oeffentlicher Einstieg (mit Systemaufruf)
    // ========================================================================

    /**
     * Ermittelt die lokale Gateway-Kette (privater Praefix der Route), von nah
     * nach fern. Leere Liste, wenn kein lokales Gateway feststellbar ist
     * (z. B. direkt zugewiesene oeffentliche IP).
     */
    public static List<String> discoverLocalChain()
    {
        try
            {
            List<String> hops = parseHops(runCommand(traceCommand(), 25));
            List<String> chain = stripVirtualLeadingHops(extractLocalChain(hops));
            if (!chain.isEmpty())
                {
                return chain;
                }
            logger.info("Traceroute lieferte keine lokale Kette - nutze Routing-Tabelle.");
            } catch (Exception e)
            {
            logger.warn("Traceroute fehlgeschlagen ({}) - nutze Routing-Tabelle.", e.getMessage());
            }

        // Fallback: nur das Default-Gateway (= naechster Hop)
        String gw = defaultGatewayFromRoutingTable();
        if (gw != null && isPrivateLan(gw))
            {
            List<String> single = new ArrayList<>();
            single.add(gw);
            return single;
            }
        return new ArrayList<>();
    }

    private static List<String> traceCommand()
    {
        if (IS_WINDOWS)
            {
            // -d = keine Namensaufloesung, -h max. Hops, -w Timeout (ms)
            return List.of("tracert", "-d", "-h", String.valueOf(MAX_HOPS), "-w", "1000", TRACE_ANCHOR);
            } else if (IS_MAC)
            {
            // -n keine Aufloesung, -m max. Hops, -w Wartezeit (s), -q 1 Probe
            return List.of("traceroute", "-n", "-m", String.valueOf(MAX_HOPS), "-w", "2", "-q", "1", TRACE_ANCHOR);
            } else
            {
            // Linux, NAS, Raspberry Pi OS
            return List.of("traceroute", "-n", "-m", String.valueOf(MAX_HOPS), "-w", "2", "-q", "1", TRACE_ANCHOR);
            }
    }

    /** Liest das Default-Gateway plattformspezifisch aus der Routing-Tabelle. */
    private static String defaultGatewayFromRoutingTable()
    {
        try
            {
            if (IS_WINDOWS)
                {
                for (String line : runCommand(List.of("ipconfig"), 10))
                    {
                    // "Standardgateway"/"Default Gateway" - Zeile mit IPv4
                    if (line.toLowerCase().contains("gateway"))
                        {
                        String ip = firstIPv4(line);
                        if (ip != null) return ip;
                        }
                    }
                } else if (IS_MAC)
                {
                for (String line : runCommand(List.of("route", "-n", "get", "default"), 10))
                    {
                    if (line.toLowerCase().contains("gateway"))
                        {
                        String ip = firstIPv4(line);
                        if (ip != null) return ip;
                        }
                    }
                } else
                {
                // Linux: "default via 192.168.20.1 dev eth0 ..."
                for (String line : runCommand(List.of("ip", "route", "show", "default"), 10))
                    {
                    if (line.contains("default"))
                        {
                        String ip = firstIPv4(line);
                        if (ip != null) return ip;
                        }
                    }
                }
            } catch (Exception e)
            {
            logger.warn("Routing-Tabelle nicht lesbar: {}", e.getMessage());
            }
        return null;
    }

    private static List<String> runCommand(List<String> command, int timeoutSeconds) throws Exception
    {
        List<String> lines = new ArrayList<>();
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream())))
            {
            String line;
            while ((line = reader.readLine()) != null)
                {
                lines.add(line);
                }
            }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS))
            {
            process.destroyForcibly();
            }
        return lines;
    }

    private static Boolean inContainerCache;

    /**
     * Best-Effort-Erkennung, ob SignalReport in einem Container laeuft
     * (Docker/Podman/LXC/Kubernetes). Nur unter Linux aussagekraeftig; auf
     * anderen Systemen immer {@code false}. Das Ergebnis wird gecacht, da es
     * sich zur Laufzeit nicht aendert.
     */
    public static boolean runningInContainer()
    {
        if (inContainerCache != null)
            {
            return inContainerCache;
            }
        boolean result = false;
        try
            {
            if (new java.io.File("/.dockerenv").exists()
                    || new java.io.File("/run/.containerenv").exists())
                {
                result = true;
                } else
                {
                java.nio.file.Path cgroup = java.nio.file.Path.of("/proc/1/cgroup");
                if (java.nio.file.Files.exists(cgroup))
                    {
                    String content = java.nio.file.Files.readString(cgroup);
                    result = content.contains("docker") || content.contains("kubepods")
                            || content.contains("containerd") || content.contains("/lxc");
                    }
                }
            } catch (Exception ignored)
            {
            // Best effort - im Zweifel "kein Container"
            }
        inContainerCache = result;
        return result;
    }

    // ========================================================================
    //  Reine Logik (statisch, ohne Systemaufruf - voll testbar)
    // ========================================================================

    /**
     * Extrahiert pro Hop-Zeile die erste IPv4-Adresse. Zeilen, die keine
     * Hop-Zeilen sind (Kopfzeile, Leerzeilen), werden uebersprungen;
     * Timeout-Hops (ohne IP, z. B. "* * *") ergeben {@code null}.
     */
    static List<String> parseHops(List<String> outputLines)
    {
        List<String> hops = new ArrayList<>();
        for (String line : outputLines)
            {
            if (!HOP_LINE.matcher(line).find())
                {
                continue; // Kopfzeile o. ae. - kein Hop
                }
            hops.add(firstIPv4(line)); // null bei Timeout-Hop
            }
        return hops;
    }

    /**
     * Liefert den zusammenhaengenden privaten Praefix der Hop-Liste (von nah
     * nach fern). Bricht beim ersten oeffentlichen Hop oder beim ersten
     * Timeout/unbekannten Hop ab.
     */
    static List<String> extractLocalChain(List<String> hops)
    {
        List<String> chain = new ArrayList<>();
        for (String hop : hops)
            {
            if (hop == null || !isPrivateLan(hop))
                {
                break;
                }
            chain.add(hop);
            }
        return chain;
    }

    /** Erste gueltige IPv4-Adresse einer Zeile, sonst {@code null}. */
    static String firstIPv4(String line)
    {
        if (line == null) return null;
        Matcher m = IPV4.matcher(line);
        while (m.find())
            {
            String candidate = m.group(1);
            if (isValidIPv4(candidate))
                {
                return candidate;
                }
            }
        return null;
    }

    private static boolean isValidIPv4(String ip)
    {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        for (String p : parts)
            {
            try
                {
                int v = Integer.parseInt(p);
                if (v < 0 || v > 255) return false;
                } catch (NumberFormatException e)
                {
                return false;
                }
            }
        return true;
    }

    /**
     * Prueft, ob eine IPv4-Adresse im privaten LAN-Bereich (RFC 1918) liegt:
     * 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16. CGNAT (100.64.0.0/10) gilt
     * bewusst als ISP-Seite und damit NICHT als lokal.
     */
    static boolean isPrivateLan(String ip)
    {
        if (!isValidIPv4(ip)) return false;
        String[] p = ip.split("\\.");
        int o0 = Integer.parseInt(p[0]);
        int o1 = Integer.parseInt(p[1]);
        if (o0 == 10) return true;
        if (o0 == 172 && o1 >= 16 && o1 <= 31) return true;
        if (o0 == 192 && o1 == 168) return true;
        return false;
    }

    /**
     * Bekannte virtuelle NAT-Bereiche, die in VMs/Containern als Gateway
     * auftauchen, aber NICHT den echten Router/Modem widerspiegeln:
     * Docker-Default-Bridge (172.17.0.0/16) sowie das User-Mode-NAT von
     * VirtualBox/QEMU (10.0.2.0/24). Andere NAT-Setups (z. B. VMware-NAT auf
     * 192.168.x.2) sind per IP-Bereich nicht von echten Heimnetzen zu
     * unterscheiden und werden hier bewusst nicht erfasst.
     */
    public static boolean isVirtualGatewayRange(String ip)
    {
        if (!isValidIPv4(ip)) return false;
        String[] p = ip.split("\\.");
        int o0 = Integer.parseInt(p[0]);
        int o1 = Integer.parseInt(p[1]);
        int o2 = Integer.parseInt(p[2]);
        if (o0 == 172 && o1 == 17) return true;            // Docker Default-Bridge
        if (o0 == 10 && o1 == 0 && o2 == 2) return true;   // VirtualBox/QEMU User-Mode-NAT
        return false;
    }

    /**
     * 172.16.0.0/12 - der Bereich, aus dem Docker seine Bridge-Netze vergibt.
     * Nur in Kombination mit einer erkannten Container-Umgebung aussagekraeftig:
     * dort ist ein 172.x-Gateway so gut wie immer eine Docker-Bridge und nicht
     * der echte Router. Heimnetze nutzen 172.x sehr selten.
     */
    public static boolean isDockerLikeRange(String ip)
    {
        if (!isValidIPv4(ip)) return false;
        String[] p = ip.split("\\.");
        int o0 = Integer.parseInt(p[0]);
        int o1 = Integer.parseInt(p[1]);
        return o0 == 172 && o1 >= 16 && o1 <= 31;
    }

    /**
     * Entfernt fuehrende virtuelle Hops (z. B. die Docker-Bridge), solange
     * danach noch ein echter privater Hop folgt. So wird in einem Container
     * der dahinterliegende echte Router als "nah" gewaehlt statt der Bridge.
     * Der letzte Hop bleibt immer erhalten - reicht eine reine NAT-Pforte ohne
     * sichtbaren Router (z. B. VirtualBox), bleibt sie als einziger Hop stehen
     * (und wird spaeter als "virtuell verdaechtig" markiert).
     */
    static List<String> stripVirtualLeadingHops(List<String> chain)
    {
        int start = 0;
        while (start < chain.size() - 1 && isVirtualGatewayRange(chain.get(start)))
            {
            start++;
            }
        return start == 0 ? chain : new ArrayList<>(chain.subList(start, chain.size()));
    }
}
