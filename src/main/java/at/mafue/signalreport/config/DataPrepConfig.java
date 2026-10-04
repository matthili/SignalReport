package at.mafue.signalreport.config;

import java.time.LocalTime;

/**
 * Konfiguration der Daten-Aufbereitung: Verdichtung der Rohmessungen zu Stundenwerten,
 * Aufbewahrungsfrist fuer Rohdaten und das Zeitfenster, in dem die aufwendigen Schritte
 * (Nachverdichtung der Historie, Loeschen) laufen duerfen.
 * <p>
 * Die laufende Verdichtung der jeweils letzten abgeschlossenen Stunde ist so leicht
 * (eine Stunde Rohdaten), dass sie jederzeit passiert; nur Rueckstaende und das Loeschen
 * sind auf das Fenster beschraenkt. Alternativ laeuft alles im Maintenance-Fenster, in dem
 * ohnehin keine Messungen stattfinden.
 */
public class DataPrepConfig
{
    public static final int DEFAULT_RETENTION_DAYS = 90;
    /** Untergrenze einer Aufbewahrungsfrist (0 = nie loeschen bleibt erlaubt). */
    public static final int MIN_RETENTION_DAYS = 7;
    public static final int MAX_RETENTION_DAYS = 3650;

    private boolean enabled = true;
    private int startHour = 3;
    private int startMinute = 0;
    private int endHour = 5;
    private int endMinute = 0;
    private boolean useMaintenanceWindow = false;
    private int retentionDays = DEFAULT_RETENTION_DAYS;

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public int getStartHour()
    {
        return startHour;
    }

    public void setStartHour(int startHour)
    {
        this.startHour = clamp(startHour, 0, 23);
    }

    public int getStartMinute()
    {
        return startMinute;
    }

    public void setStartMinute(int startMinute)
    {
        this.startMinute = clamp(startMinute, 0, 59);
    }

    public int getEndHour()
    {
        return endHour;
    }

    public void setEndHour(int endHour)
    {
        this.endHour = clamp(endHour, 0, 23);
    }

    public int getEndMinute()
    {
        return endMinute;
    }

    public void setEndMinute(int endMinute)
    {
        this.endMinute = clamp(endMinute, 0, 59);
    }

    public boolean isUseMaintenanceWindow()
    {
        return useMaintenanceWindow;
    }

    public void setUseMaintenanceWindow(boolean useMaintenanceWindow)
    {
        this.useMaintenanceWindow = useMaintenanceWindow;
    }

    public int getRetentionDays()
    {
        return retentionDays;
    }

    /** 0 = nie loeschen; sonst auf [MIN_RETENTION_DAYS, MAX_RETENTION_DAYS] begrenzt. */
    public void setRetentionDays(int retentionDays)
    {
        this.retentionDays = retentionDays <= 0 ? 0 : clamp(retentionDays, MIN_RETENTION_DAYS, MAX_RETENTION_DAYS);
    }

    /**
     * Liegt der Zeitpunkt im Aufbereitungsfenster? Mit {@code useMaintenanceWindow} gilt das
     * Maintenance-Fenster, sofern es aktiviert ist; andernfalls (und als Rueckfall) das eigene.
     */
    public boolean isWindowNow(LocalTime now, MaintenanceWindow maintenance)
    {
        if (!enabled)
            {
            return false;
            }
        if (useMaintenanceWindow && maintenance != null && maintenance.isEnabled())
            {
            return inWindow(now,
                    LocalTime.of(maintenance.getStartHour(), maintenance.getStartMinute()),
                    LocalTime.of(maintenance.getEndHour(), maintenance.getEndMinute()));
            }
        return inWindow(now, LocalTime.of(startHour, startMinute), LocalTime.of(endHour, endMinute));
    }

    public boolean isWindowNow(MaintenanceWindow maintenance)
    {
        return isWindowNow(LocalTime.now(), maintenance);
    }

    /** Fenster-Logik wie beim Maintenance-Fenster, inklusive Fenstern ueber Mitternacht. */
    static boolean inWindow(LocalTime now, LocalTime start, LocalTime end)
    {
        if (start.equals(end))
            {
            return false;
            }
        if (start.isBefore(end))
            {
            return !now.isBefore(start) && now.isBefore(end);
            }
        return !now.isBefore(start) || now.isBefore(end);
    }

    private static int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(max, value));
    }
}
