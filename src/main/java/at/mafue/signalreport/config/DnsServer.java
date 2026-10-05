package at.mafue.signalreport.config;

/**
 * Ein DNS-Server fuer den Benchmark. Mitgelieferte Server kommen aus
 * {@link BuiltinCatalog} (Datei /catalog/dns-servers.json im Programm); eigene Server des
 * Nutzers ({@link CustomDnsServer}) werden fuer einen Benchmark-Lauf in diese Form gebracht
 * und tragen dann {@code custom = true}.
 */
public class DnsServer
{
    private String name;
    private String address;
    private String region;
    private String provider;
    private boolean custom;

    // Jackson benötigt no-arg Konstruktor
    public DnsServer()
    {
        this.provider = "Unknown";
    }

    // Konstruktor mit 3 Parametern
    public DnsServer(String name, String address, String region)
    {
        this.name = name;
        this.address = address;
        this.region = region;
        this.provider = "Unknown";
    }

    // Vollständiger Konstruktor mit 4 Parametern
    public DnsServer(String name, String address, String region, String provider)
    {
        this.name = name;
        this.address = address;
        this.region = region;
        this.provider = provider != null && !provider.isEmpty() ? provider : "Unknown";
    }

    /** Kopie: die mitgelieferte Liste wird nie direkt herausgegeben. */
    public DnsServer(DnsServer other)
    {
        this.name = other.name;
        this.address = other.address;
        this.region = other.region;
        this.provider = other.provider;
        this.custom = other.custom;
    }

    // Getter + Setter
    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getAddress()
    {
        return address;
    }

    public void setAddress(String address)
    {
        this.address = address;
    }

    public String getRegion()
    {
        return region;
    }

    public void setRegion(String region)
    {
        this.region = region;
    }

    public String getProvider()
    {
        return provider;
    }

    public void setProvider(String provider)
    {
        this.provider = provider != null && !provider.isEmpty() ? provider : "Unknown";
    }

    /** true = eigener Server des Nutzers, false = mitgeliefert. */
    public boolean isCustom()
    {
        return custom;
    }

    public void setCustom(boolean custom)
    {
        this.custom = custom;
    }
}
