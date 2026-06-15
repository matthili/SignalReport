package at.mafue.signalreport.config;

public class MaintenanceWindow
{
    private boolean enabled = false;
    private int startHour = 4;
    private int startMinute = 0;
    private int endHour = 4;
    private int endMinute = 10;

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
        this.startHour = startHour;
    }

    public int getStartMinute()
    {
        return startMinute;
    }

    public void setStartMinute(int startMinute)
    {
        this.startMinute = startMinute;
    }

    public int getEndHour()
    {
        return endHour;
    }

    public void setEndHour(int endHour)
    {
        this.endHour = endHour;
    }

    public int getEndMinute()
    {
        return endMinute;
    }

    public void setEndMinute(int endMinute)
    {
        this.endMinute = endMinute;
    }

    // Prüft, ob aktuelle Zeit im Maintenance-Fenster liegt
    public boolean isMaintenanceTime()
    {
        if (!enabled) return false;

        java.time.LocalTime now = java.time.LocalTime.now();
        java.time.LocalTime start = java.time.LocalTime.of(startHour, startMinute);
        java.time.LocalTime end = java.time.LocalTime.of(endHour, endMinute);

        if (start.isBefore(end))
            {
            // Normales Fenster (z.B. 04:00–04:10)
            return !now.isBefore(start) && now.isBefore(end);
            } else
            {
            // Über Mitternacht (z.B. 23:00–01:00)
            return !now.isBefore(start) || now.isBefore(end);
            }
    }
}
