package at.mafue.signalreport.config;

public class DnsServer
{
    private String name;
    private String address;
    private String region;
    private String provider;

    // Jackson benötigt no-arg Konstruktor
    public DnsServer()
    {
        this.provider = "Unknown";
    }

    // Konstruktor mit 3 Parametern (für createDefault())
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
}
