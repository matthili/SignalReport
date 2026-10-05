package at.mafue.signalreport.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mitgelieferte Listen, die zum Programm gehoeren und nie in config.json geschrieben werden:
 * die DNS-Server fuer den Benchmark ({@value #DNS_RESOURCE}) und die Dienste der
 * Erreichbarkeitspruefung ({@value #SERVICES_RESOURCE}, {@code enabled} = Standard-Schalter).
 * <p>
 * Jedes Update bringt damit neue, korrigierte und gestrichene Eintraege automatisch mit. In
 * config.json steht nur, was der Nutzer davon abweichend festlegt: eigene DNS-Server,
 * ausgeblendete mitgelieferte Server, abweichende Dienst-Schalter. Bis 2.2.1 stand die
 * komplette Liste in config.json, und Ergaenzungen im Code erreichten bestehende
 * Installationen nie ({@link Config#migrateLegacyLists()} ueberfuehrt solche Dateien).
 * <p>
 * Herausgegeben werden immer Kopien, damit niemand die gemeinsame Liste veraendert.
 */
public final class BuiltinCatalog
{
    static final String DNS_RESOURCE = "/catalog/dns-servers.json";
    static final String SERVICES_RESOURCE = "/catalog/services.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile List<DnsServer> dnsServers;
    private static volatile List<ServiceTarget> services;

    private BuiltinCatalog()
    {
    }

    /** Mitgelieferte DNS-Server in der Reihenfolge der Datei (Kopien). */
    public static List<DnsServer> dnsServers()
    {
        List<DnsServer> list = dnsServers;
        if (list == null)
            {
            list = load(DNS_RESOURCE, new TypeReference<List<DnsServer>>()
            {
            });
            dnsServers = list;
            }
        List<DnsServer> copy = new ArrayList<>(list.size());
        for (DnsServer d : list)
            {
            copy.add(new DnsServer(d));
            }
        return copy;
    }

    /** Mitgelieferte Dienste mit ihrem Standard-Schalter in der Reihenfolge der Datei (Kopien). */
    public static List<ServiceTarget> services()
    {
        List<ServiceTarget> list = services;
        if (list == null)
            {
            list = load(SERVICES_RESOURCE, new TypeReference<List<ServiceTarget>>()
            {
            });
            services = list;
            }
        List<ServiceTarget> copy = new ArrayList<>(list.size());
        for (ServiceTarget t : list)
            {
            copy.add(new ServiceTarget(t));
            }
        return copy;
    }

    /** Mitgelieferte Dienste nach id (Kopien, Reihenfolge der Datei). */
    public static Map<String, ServiceTarget> servicesById()
    {
        Map<String, ServiceTarget> byId = new LinkedHashMap<>();
        for (ServiceTarget t : services())
            {
            byId.put(t.getId(), t);
            }
        return byId;
    }

    private static <T> List<T> load(String resource, TypeReference<List<T>> type)
    {
        try (InputStream in = BuiltinCatalog.class.getResourceAsStream(resource))
            {
            if (in == null)
                {
                throw new IllegalStateException("Mitgelieferte Liste fehlt im Programm: " + resource);
                }
            return List.copyOf(MAPPER.readValue(in, type));
            } catch (IOException e)
            {
            throw new IllegalStateException("Mitgelieferte Liste " + resource + " ist fehlerhaft: " + e.getMessage(), e);
            }
    }
}
