package at.mafue.signalreport.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Der Teil des DNS-Benchmarks, den der Nutzer festlegt, gespeichert in config.json unter
 * {@code dnsBenchmark}: eigene DNS-Server und ausgeblendete mitgelieferte Server. Die
 * mitgelieferte Liste selbst kommt mit dem Programm ({@link BuiltinCatalog}).
 * <p>
 * <b>Adressen:</b> Eigene Server werden nur als IP-Adresse angenommen (IPv4 oder IPv6), nie als
 * Hostname, denn ein Hostname muesste vor der Messung erst selbst aufgeloest werden. Die Pruefung
 * {@link #canonicalAddress(String)} loest nie per DNS auf: IPv4 muss vorher einem strengen Muster
 * entsprechen, IPv6 wird in eckigen Klammern uebergeben; in beiden Faellen parst
 * {@code InetAddress.getAllByName} laut JDK-Quelltext nur das Literal oder wirft eine Ausnahme.
 * <p>
 * Die Listen werden nie veraendert, sondern bei jeder Aenderung ersetzt; ein gleichzeitig
 * laufender Benchmark sieht so immer einen vollstaendigen Stand.
 */
public class DnsBenchmarkConfig
{
    public static final int MAX_CUSTOM_SERVERS = 50;

    /** Welche Server ein Benchmark-Lauf prueft. */
    public enum Scope
    {
        /** Mitgelieferte ohne ausgeblendete, dazu die eingeschalteten eigenen Server. */
        ALL,
        /** Alle eigenen Server, unabhaengig vom Schalter. */
        CUSTOM
    }

    /** Ein Pruefbefund zur eigenen Liste: i18n-Schluessel und Wert fuer den Platzhalter {value}. */
    public record Problem(String key, String value)
    {
    }

    private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Pattern IPV4 = Pattern.compile(OCTET + "(\\." + OCTET + "){3}");

    private volatile List<CustomDnsServer> customServers = List.of();
    private volatile List<String> hiddenBuiltins = List.of();

    public List<CustomDnsServer> getCustomServers()
    {
        return customServers;
    }

    public void setCustomServers(List<CustomDnsServer> servers)
    {
        this.customServers = servers == null ? List.of() : List.copyOf(servers.stream().filter(Objects::nonNull).toList());
    }

    /** Adressen (kanonische Schreibweise) der mitgelieferten Server, die nicht getestet werden sollen. */
    public List<String> getHiddenBuiltins()
    {
        return hiddenBuiltins;
    }

    public void setHiddenBuiltins(List<String> addresses)
    {
        if (addresses == null)
            {
            this.hiddenBuiltins = List.of();
            return;
            }
        Set<String> keys = new LinkedHashSet<>();
        for (String a : addresses)
            {
            if (a != null && !a.isBlank())
                {
                keys.add(addressKey(a));
                }
            }
        this.hiddenBuiltins = List.copyOf(keys);
    }

    /**
     * Server fuer einen Benchmark-Lauf. Gleiche Adresse wird nur einmal geprueft; ein eigener
     * Eintrag vertritt einen mitgelieferten Server mit derselben Adresse.
     */
    public List<DnsServer> serversFor(Scope scope)
    {
        List<CustomDnsServer> own = customServers;
        Set<String> ownKeys = new HashSet<>();
        for (CustomDnsServer c : own)
            {
            ownKeys.add(addressKey(c.getAddress()));
            }

        List<DnsServer> result = new ArrayList<>();
        if (scope == Scope.ALL)
            {
            Set<String> hidden = new HashSet<>(hiddenBuiltins);
            for (DnsServer b : BuiltinCatalog.dnsServers())
                {
                String key = addressKey(b.getAddress());
                if (!hidden.contains(key) && !ownKeys.contains(key))
                    {
                    result.add(b);
                    }
                }
            }
        Set<String> added = new HashSet<>();
        for (CustomDnsServer c : own)
            {
            if (scope == Scope.ALL && !c.isEnabled())
                {
                continue;
                }
            if (added.add(addressKey(c.getAddress())))
                {
                result.add(toDnsServer(c));
                }
            }
        return result;
    }

    /** Eigener Server in Benchmark-Form; die Notiz steht im Feld region (Anzeige in der Ergebnis-Kachel). */
    static DnsServer toDnsServer(CustomDnsServer c)
    {
        String name = c.getName().isBlank() ? c.getAddress() : c.getName();
        DnsServer s = new DnsServer(name, c.getAddress(), c.getNote(), "");
        s.setCustom(true);
        return s;
    }

    /**
     * IPv4- oder IPv6-Adresse in kanonischer Schreibweise; null fuer alles andere (Hostnamen,
     * Zonen-Angaben, Unsinn) sowie fuer die unspezifizierte Adresse und Multicast-Adressen.
     * Loest nie per DNS auf (siehe Klassenbeschreibung).
     */
    public static String canonicalAddress(String text)
    {
        if (text == null)
            {
            return null;
            }
        String a = text.trim();
        if (a.isEmpty() || a.length() > CustomDnsServer.MAX_ADDRESS_LENGTH)
            {
            return null;
            }
        InetAddress addr;
        try
            {
            if (IPV4.matcher(a).matches())
                {
                addr = InetAddress.getByName(a);
                } else if (a.indexOf(':') >= 0 && a.indexOf('%') < 0 && a.indexOf('[') < 0 && a.indexOf(']') < 0)
                {
                addr = InetAddress.getByName("[" + a + "]");
                } else
                {
                return null;
                }
            } catch (UnknownHostException e)
            {
            return null;
            }
        if (addr.isAnyLocalAddress() || addr.isMulticastAddress())
            {
            return null;
            }
        return addr.getHostAddress();
    }

    /** Vergleichsschluessel einer Adresse: kanonisch, wenn es eine IP-Adresse ist, sonst klein geschrieben. */
    public static String addressKey(String address)
    {
        String canonical = canonicalAddress(address);
        if (canonical != null)
            {
            return canonical;
            }
        return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Prueft eine neue eigene Liste. Was bereits gespeichert ist ({@code stored}), bleibt erlaubt,
     * auch wenn es heute abgelehnt wuerde (Altbestand, z. B. ein frueher eingetragener Hostname
     * oder eine Adresse, die inzwischen mitgeliefert wird); eine schon zu lange Liste darf nicht
     * weiter wachsen.
     */
    public static List<Problem> validate(List<CustomDnsServer> incoming, List<CustomDnsServer> stored)
    {
        List<Problem> problems = new ArrayList<>();
        Set<String> storedKeys = new HashSet<>();
        for (CustomDnsServer s : stored)
            {
            storedKeys.add(addressKey(s.getAddress()));
            }
        if (incoming.size() > MAX_CUSTOM_SERVERS && incoming.size() > stored.size())
            {
            problems.add(new Problem("api.dnsTooMany", String.valueOf(MAX_CUSTOM_SERVERS)));
            }
        Map<String, String> builtinNames = new HashMap<>();
        for (DnsServer b : BuiltinCatalog.dnsServers())
            {
            builtinNames.put(addressKey(b.getAddress()), b.getName());
            }

        Set<String> seen = new HashSet<>();
        for (CustomDnsServer c : incoming)
            {
            if (c.getName().isBlank() || c.getAddress().isBlank())
                {
                problems.add(new Problem("api.dnsEntryIncomplete", c.getName().isBlank() ? c.getAddress() : c.getName()));
                continue;
                }
            String key = addressKey(c.getAddress());
            boolean grandfathered = storedKeys.contains(key);
            if (canonicalAddress(c.getAddress()) == null && !grandfathered)
                {
                problems.add(new Problem("api.dnsInvalidAddress", c.getAddress()));
                }
            if (!seen.add(key))
                {
                problems.add(new Problem("api.dnsDuplicate", c.getAddress()));
                }
            String builtinName = builtinNames.get(key);
            if (builtinName != null && !grandfathered)
                {
                problems.add(new Problem("api.dnsIsBuiltin", builtinName));
                }
            }
        return problems;
    }

    /**
     * Uebernahme der alten, kompletten Liste aus config.json (bis 2.2.1): Was nicht mitgeliefert
     * ist, wird eigener Server (eingeschaltet). Mitgelieferte Adressen werden uebersprungen;
     * mitgelieferte Server, die in der alten Liste fehlen, werden nicht ausgeblendet, denn das
     * sind die seither neu hinzugekommenen.
     *
     * @return Anzahl der als eigene Server uebernommenen Eintraege
     */
    public int adoptLegacyList(List<DnsServer> legacy)
    {
        if (legacy == null || legacy.isEmpty())
            {
            return 0;
            }
        Set<String> builtinKeys = new HashSet<>();
        for (DnsServer b : BuiltinCatalog.dnsServers())
            {
            builtinKeys.add(addressKey(b.getAddress()));
            }
        List<CustomDnsServer> own = new ArrayList<>(customServers);
        Set<String> ownKeys = new HashSet<>();
        for (CustomDnsServer c : own)
            {
            ownKeys.add(addressKey(c.getAddress()));
            }

        int adopted = 0;
        for (DnsServer d : legacy)
            {
            if (d == null || d.getAddress() == null || d.getAddress().isBlank())
                {
                continue;
                }
            String key = addressKey(d.getAddress());
            if (builtinKeys.contains(key) || !ownKeys.add(key))
                {
                continue;
                }
            String name = d.getName() == null || d.getName().isBlank() ? d.getAddress().trim() : d.getName();
            own.add(new CustomDnsServer(name, d.getAddress(), legacyNote(d), true));
            adopted++;
            }
        setCustomServers(own);
        return adopted;
    }

    /** Notiz aus Region und Anbieter der alten Liste ("Unknown" und Leeres entfallen). */
    private static String legacyNote(DnsServer d)
    {
        List<String> parts = new ArrayList<>();
        if (d.getRegion() != null && !d.getRegion().isBlank())
            {
            parts.add(d.getRegion().trim());
            }
        if (d.getProvider() != null && !d.getProvider().isBlank() && !"Unknown".equals(d.getProvider()))
            {
            parts.add(d.getProvider().trim());
            }
        return String.join(" · ", parts);
    }
}
