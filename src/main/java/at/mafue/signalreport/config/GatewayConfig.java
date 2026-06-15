package at.mafue.signalreport.config;

/**
 * Konfiguration der lokalen Gateway-Ueberwachung.
 * <p>
 * Bei {@code autoDiscover=true} ermittelt SignalReport beim Start per
 * Traceroute den naechsten ({@code near}) und den weitesten lokalen
 * ({@code far}) Gateway. Beide Felder lassen sich auch manuell setzen
 * (dann {@code autoDiscover=false}), z. B. fuer ungewoehnliche Netze.
 * Bei nur einem Gateway ist {@code far} leer oder gleich {@code near}.
 */
public class GatewayConfig
{
    private boolean autoDiscover = true;
    private String near = "";
    private String far = "";
    private String nearLabel = "";
    private String farLabel = "";

    // Pro Segment: manuell gesetzte IP (nicht durch Auto-Erkennung ueberschreiben)
    // und ob diese manuelle IP einen lokalen IP-Wechsel ueberdauert (persistent)
    // oder dann neu ermittelt wird. farPingEnabled schaltet die kontinuierliche
    // Messung des fernen Gateways ab (z. B. fuer ein Geraet, das nicht dauernd
    // gepingt werden soll); der Gateway bleibt erkannt und wird angezeigt.
    private boolean nearManual = false;
    private boolean farManual = false;
    private boolean nearPersistent = false;
    private boolean farPersistent = false;
    private boolean farPingEnabled = true;

    public boolean isAutoDiscover()
    {
        return autoDiscover;
    }

    public void setAutoDiscover(boolean autoDiscover)
    {
        this.autoDiscover = autoDiscover;
    }

    public String getNear()
    {
        return near;
    }

    public void setNear(String near)
    {
        this.near = near != null ? near : "";
    }

    public String getFar()
    {
        return far;
    }

    public void setFar(String far)
    {
        this.far = far != null ? far : "";
    }

    public String getNearLabel()
    {
        return nearLabel;
    }

    public void setNearLabel(String nearLabel)
    {
        this.nearLabel = nearLabel != null ? nearLabel : "";
    }

    public String getFarLabel()
    {
        return farLabel;
    }

    public void setFarLabel(String farLabel)
    {
        this.farLabel = farLabel != null ? farLabel : "";
    }

    public boolean isNearManual()
    {
        return nearManual;
    }

    public void setNearManual(boolean nearManual)
    {
        this.nearManual = nearManual;
    }

    public boolean isFarManual()
    {
        return farManual;
    }

    public void setFarManual(boolean farManual)
    {
        this.farManual = farManual;
    }

    public boolean isNearPersistent()
    {
        return nearPersistent;
    }

    public void setNearPersistent(boolean nearPersistent)
    {
        this.nearPersistent = nearPersistent;
    }

    public boolean isFarPersistent()
    {
        return farPersistent;
    }

    public void setFarPersistent(boolean farPersistent)
    {
        this.farPersistent = farPersistent;
    }

    public boolean isFarPingEnabled()
    {
        return farPingEnabled;
    }

    public void setFarPingEnabled(boolean farPingEnabled)
    {
        this.farPingEnabled = farPingEnabled;
    }
}
