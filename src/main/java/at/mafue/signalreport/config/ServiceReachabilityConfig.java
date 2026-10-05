package at.mafue.signalreport.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Konfiguration der periodischen Dienst-Erreichbarkeitspruefung
 * (Sperr-/Zensur-Erkennung fuer ausgewaehlte Online-Dienste).
 * <p>
 * Das Feature ist bewusst standardmaessig <b>deaktiviert</b> und laeuft in einem
 * eigenen, deutlich langsameren Takt (Standard alle 6 Stunden), getrennt vom
 * 30-Sekunden-Messtakt der Verbindungsueberwachung. Ohne dieses Feature bleibt
 * SignalReport ein reiner Verbindungs-Monitor.
 * <p>
 * <b>Dienstliste:</b> Die Dienste und ihr Standard-Schalter kommen mit dem Programm
 * ({@link BuiltinCatalog}). In config.json stehen nur die Abweichungen dieser Installation
 * ({@code serviceOverrides}: Dienst-id &rarr; ein/aus) und eigene Dienste
 * ({@code customServices}, bisher nur aus der Uebernahme alter Listen). Neue Dienste eines
 * Updates erscheinen dadurch automatisch mit ihrem Standard-Schalter. Bis 2.2.1 stand die
 * komplette Liste unter {@code services} in config.json; {@link #adoptLegacyServices()}
 * ueberfuehrt sie.
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
    private volatile Map<String, Boolean> serviceOverrides = Map.of();
    private volatile List<ServiceTarget> customServices = List.of();
    /** Altbestand bis 2.2.1 (komplette Liste); nur Lesen, wird beim Laden ueberfuehrt. */
    private List<ServiceTarget> legacyServices;

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

    /** Abweichungen vom mitgelieferten Standard-Schalter: Dienst-id &rarr; eingeschaltet. */
    public Map<String, Boolean> getServiceOverrides()
    {
        return serviceOverrides;
    }

    public void setServiceOverrides(Map<String, Boolean> overrides)
    {
        Map<String, Boolean> copy = new LinkedHashMap<>();
        if (overrides != null)
            {
            overrides.forEach((id, on) ->
            {
            if (id != null && !id.isBlank() && on != null)
                {
                copy.put(id, on);
                }
            });
            }
        this.serviceOverrides = Collections.unmodifiableMap(copy);
    }

    /** Eigene, nicht mitgelieferte Dienste. */
    public List<ServiceTarget> getCustomServices()
    {
        return customServices;
    }

    public void setCustomServices(List<ServiceTarget> services)
    {
        this.customServices = services == null ? List.of() : List.copyOf(services.stream().filter(Objects::nonNull).toList());
    }

    /** Liest die alte, komplette Liste aus config.json-Dateien bis 2.2.1 (wird nicht mehr geschrieben). */
    @JsonProperty("services")
    public void setLegacyServices(List<ServiceTarget> services)
    {
        this.legacyServices = services;
    }

    /**
     * Alle Dienste dieser Installation: die mitgelieferten in der Reihenfolge der Liste mit dem
     * hier gueltigen Schalter, danach die eigenen (Kopien).
     */
    public List<ServiceTarget> effectiveServices()
    {
        Map<String, Boolean> overrides = serviceOverrides;
        List<ServiceTarget> list = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (ServiceTarget t : BuiltinCatalog.services())
            {
            Boolean on = overrides.get(t.getId());
            if (on != null)
                {
                t.setEnabled(on);
                }
            list.add(t);
            ids.add(t.getId());
            }
        for (ServiceTarget c : customServices)
            {
            if (ids.add(c.getId()))
                {
                list.add(new ServiceTarget(c));
                }
            }
        return list;
    }

    /**
     * Setzt den Schalter eines Dienstes. Bei mitgelieferten Diensten wird nur eine Abweichung vom
     * Standard gemerkt (stimmt der Schalter wieder mit dem Standard ueberein, entfaellt sie);
     * bei eigenen Diensten wird der Eintrag selbst geaendert. Unbekannte ids werden ignoriert.
     */
    public void applyServiceSwitch(String id, boolean on)
    {
        if (id == null)
            {
            return;
            }
        ServiceTarget builtin = BuiltinCatalog.servicesById().get(id);
        if (builtin != null)
            {
            Map<String, Boolean> m = new LinkedHashMap<>(serviceOverrides);
            if (builtin.isEnabled() == on)
                {
                m.remove(id);
                } else
                {
                m.put(id, on);
                }
            serviceOverrides = Collections.unmodifiableMap(m);
            return;
            }
        List<ServiceTarget> own = new ArrayList<>();
        for (ServiceTarget c : customServices)
            {
            ServiceTarget copy = new ServiceTarget(c);
            if (copy.getId().equals(id))
                {
                copy.setEnabled(on);
                }
            own.add(copy);
            }
        customServices = List.copyOf(own);
    }

    /**
     * Ueberfuehrt die alte, komplett gespeicherte Liste (bis 2.2.1): Schalter, die vom heutigen
     * Standard abweichen, werden gemerkte Abweichungen; nicht mitgelieferte Dienste werden eigene.
     *
     * @return true, wenn eine alte Liste vorhanden war (config.json sollte dann neu geschrieben werden)
     */
    public boolean adoptLegacyServices()
    {
        List<ServiceTarget> legacy = legacyServices;
        if (legacy == null)
            {
            return false;
            }
        legacyServices = null;

        Map<String, ServiceTarget> builtin = BuiltinCatalog.servicesById();
        Map<String, Boolean> overrides = new LinkedHashMap<>(serviceOverrides);
        List<ServiceTarget> own = new ArrayList<>(customServices);
        Set<String> ownIds = new HashSet<>();
        for (ServiceTarget c : own)
            {
            ownIds.add(c.getId());
            }

        for (ServiceTarget t : legacy)
            {
            if (t == null || t.getId().isBlank())
                {
                continue;
                }
            ServiceTarget standard = builtin.get(t.getId());
            if (standard != null)
                {
                if (t.isEnabled() != standard.isEnabled())
                    {
                    overrides.put(t.getId(), t.isEnabled());
                    }
                } else if (!t.getDomain().isBlank() && ownIds.add(t.getId()))
                {
                own.add(new ServiceTarget(t));
                }
            }
        serviceOverrides = Collections.unmodifiableMap(overrides);
        customServices = List.copyOf(own);
        return true;
    }
}
