package at.mafue.signalreport.config;

/**
 * Ein einzelner Dienst bzw. eine Plattform, deren Erreichbarkeit periodisch
 * geprueft wird (z. B. facebook.com).
 * <p>
 * {@code kind} bestimmt die Tiefe der Pruefung: {@code WEB}-Dienste durchlaufen
 * die volle Schicht-Probe (DNS, TCP, TLS/SNI, HTTP), {@code MESSENGER} nur eine
 * groebere Endpunkt-Erreichbarkeit (eigene Protokolle, bewusst sperr-resistent),
 * {@code CONTROL} dient als neutraler Referenz-Anker, der normalerweise nie
 * gesperrt ist. {@code expectedOwner} und {@code contentFingerprint} sind
 * optionale Hilfen fuer die spaetere Klassifizierung und duerfen leer bleiben.
 */
public class ServiceTarget
{
    public enum ServiceKind
    {
        WEB,
        MESSENGER,
        CONTROL
    }

    private String id = "";
    private String displayName = "";
    private String domain = "";
    private int port = 443;
    private ServiceKind kind = ServiceKind.WEB;
    private String expectedOwner = "";
    private String contentFingerprint = "";
    private boolean enabled = true;

    // Jackson benoetigt einen no-arg Konstruktor.
    public ServiceTarget()
    {
    }

    public ServiceTarget(String id, String displayName, String domain, ServiceKind kind, boolean enabled)
    {
        setId(id);
        setDisplayName(displayName);
        setDomain(domain);
        setKind(kind);
        this.enabled = enabled;
    }

    public String getId()
    {
        return id;
    }

    public void setId(String id)
    {
        this.id = id != null ? id : "";
    }

    public String getDisplayName()
    {
        return displayName;
    }

    public void setDisplayName(String displayName)
    {
        this.displayName = displayName != null ? displayName : "";
    }

    public String getDomain()
    {
        return domain;
    }

    public void setDomain(String domain)
    {
        this.domain = domain != null ? domain : "";
    }

    public int getPort()
    {
        return port;
    }

    public void setPort(int port)
    {
        this.port = (port > 0 && port <= 65535) ? port : 443;
    }

    public ServiceKind getKind()
    {
        return kind;
    }

    public void setKind(ServiceKind kind)
    {
        this.kind = kind != null ? kind : ServiceKind.WEB;
    }

    public String getExpectedOwner()
    {
        return expectedOwner;
    }

    public void setExpectedOwner(String expectedOwner)
    {
        this.expectedOwner = expectedOwner != null ? expectedOwner : "";
    }

    public String getContentFingerprint()
    {
        return contentFingerprint;
    }

    public void setContentFingerprint(String contentFingerprint)
    {
        this.contentFingerprint = contentFingerprint != null ? contentFingerprint : "";
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }
}
