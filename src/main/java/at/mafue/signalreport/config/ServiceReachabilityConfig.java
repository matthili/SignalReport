package at.mafue.signalreport.config;

import java.util.ArrayList;
import java.util.List;

import at.mafue.signalreport.config.ServiceTarget.ServiceKind;

/**
 * Konfiguration der periodischen Dienst-Erreichbarkeitspruefung
 * (Sperr-/Zensur-Erkennung fuer ausgewaehlte Online-Dienste).
 * <p>
 * Das Feature ist bewusst standardmaessig <b>deaktiviert</b> und laeuft in einem
 * eigenen, deutlich langsameren Takt (Standard alle 6 Stunden), getrennt vom
 * 30-Sekunden-Messtakt der Verbindungsueberwachung. Ohne dieses Feature bleibt
 * SignalReport ein reiner Verbindungs-Monitor.
 */
public class ServiceReachabilityConfig
{
    /** Untergrenze fuer den Pruef-Takt in Minuten (verhindert uebermaessige Last). */
    public static final int MIN_INTERVAL_MINUTES = 15;
    /** Obergrenze fuer den Pruef-Takt in Minuten (eine Woche). */
    public static final int MAX_INTERVAL_MINUTES = 7 * 24 * 60;

    private boolean enabled = false;
    private int intervalMinutes = 360;     // Standard: alle 6 Stunden
    private boolean useControlSni = true;  // zweiter TLS-Connect mit Kontroll-SNI (SNI-Sperre erkennen)
    private List<ServiceTarget> services = defaultServices();

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public int getIntervalMinutes()
    {
        return intervalMinutes;
    }

    public void setIntervalMinutes(int intervalMinutes)
    {
        this.intervalMinutes = Math.max(MIN_INTERVAL_MINUTES, Math.min(MAX_INTERVAL_MINUTES, intervalMinutes));
    }

    public boolean isUseControlSni()
    {
        return useControlSni;
    }

    public void setUseControlSni(boolean useControlSni)
    {
        this.useControlSni = useControlSni;
    }

    public List<ServiceTarget> getServices()
    {
        if (this.services == null)
            {
            this.services = new ArrayList<>();
            }
        return this.services;
    }

    public void setServices(List<ServiceTarget> services)
    {
        this.services = services != null ? services : new ArrayList<>();
    }

    /**
     * Sinnvolle Standard-Auswahl: ein paar grosse Web-Dienste und ein
     * Referenz-Anker sind aktiv, weitere (TikTok, Reddit, Bluesky, Mastodon,
     * Telegram, Signal) sind vorkonfiguriert, aber deaktiviert. Messenger liefern
     * nur eine grobe Endpunkt-Erreichbarkeit; Mastodon ist foederiert (geprueft
     * wird eine konkrete Instanz).
     */
    public static List<ServiceTarget> defaultServices()
    {
        List<ServiceTarget> list = new ArrayList<>();

        // Web-Dienste (volle Schicht-Probe) -- haeufig genutzt, daher aktiv
        list.add(new ServiceTarget("facebook", "Facebook", "facebook.com", ServiceKind.WEB, true));
        list.add(new ServiceTarget("instagram", "Instagram", "instagram.com", ServiceKind.WEB, true));
        list.add(new ServiceTarget("x", "X (Twitter)", "x.com", ServiceKind.WEB, true));
        list.add(new ServiceTarget("youtube", "YouTube", "youtube.com", ServiceKind.WEB, true));
        list.add(new ServiceTarget("wikipedia", "Wikipedia", "wikipedia.org", ServiceKind.WEB, true));

        // Web-Dienste -- vorkonfiguriert, aber standardmaessig deaktiviert
        list.add(new ServiceTarget("tiktok", "TikTok", "tiktok.com", ServiceKind.WEB, false));
        list.add(new ServiceTarget("reddit", "Reddit", "reddit.com", ServiceKind.WEB, false));
        list.add(new ServiceTarget("bluesky", "Bluesky", "bsky.app", ServiceKind.WEB, false));
        list.add(new ServiceTarget("mastodon", "Mastodon (mastodon.social)", "mastodon.social", ServiceKind.WEB, false));

        // Messenger (nur grobe Endpunkt-Erreichbarkeit, sperr-resistent)
        list.add(new ServiceTarget("whatsapp", "WhatsApp", "web.whatsapp.com", ServiceKind.MESSENGER, true));
        list.add(new ServiceTarget("telegram", "Telegram", "web.telegram.org", ServiceKind.MESSENGER, false));
        list.add(new ServiceTarget("signal", "Signal", "signal.org", ServiceKind.MESSENGER, false));

        // Neutraler Referenz-Anker (prueft, ob der Mess-Pfad selbst plausibel ist)
        list.add(new ServiceTarget("control-example", "Referenz (example.com)", "example.com", ServiceKind.CONTROL, true));

        return list;
    }
}
