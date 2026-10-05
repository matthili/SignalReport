package at.mafue.signalreport.config;

/**
 * Ein vom Nutzer selbst eingetragener DNS-Server fuer den Benchmark, gespeichert in
 * config.json unter {@code dnsBenchmark.customServers}. {@code enabled} bestimmt, ob der Server
 * im grossen Benchmark mitlaeuft; "Nur eigene testen" prueft immer alle eigenen Server.
 * Werte werden beim Setzen getrimmt und auf eine Hoechstlaenge gekuerzt.
 */
public class CustomDnsServer
{
    public static final int MAX_NAME_LENGTH = 80;
    public static final int MAX_ADDRESS_LENGTH = 45;
    public static final int MAX_NOTE_LENGTH = 120;

    private String name = "";
    private String address = "";
    private String note = "";
    private boolean enabled = true;

    // Jackson benoetigt einen no-arg Konstruktor.
    public CustomDnsServer()
    {
    }

    public CustomDnsServer(String name, String address, String note, boolean enabled)
    {
        setName(name);
        setAddress(address);
        setNote(note);
        this.enabled = enabled;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = clean(name, MAX_NAME_LENGTH);
    }

    public String getAddress()
    {
        return address;
    }

    public void setAddress(String address)
    {
        this.address = clean(address, MAX_ADDRESS_LENGTH);
    }

    public String getNote()
    {
        return note;
    }

    public void setNote(String note)
    {
        this.note = clean(note, MAX_NOTE_LENGTH);
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    private static String clean(String value, int maxLength)
    {
        if (value == null)
            {
            return "";
            }
        String v = value.trim();
        return v.length() > maxLength ? v.substring(0, maxLength) : v;
    }
}
