package at.mafue.signalreport.storage;

public class HourlyAverage
{
    private final int hourOfDay;
    private final double avgLatency;
    private final int count;

    public HourlyAverage(int hourOfDay, double avgLatency, int count)
    {
        this.hourOfDay = hourOfDay;
        this.avgLatency = avgLatency;
        this.count = count;
    }

    public int getHourOfDay()
    {
        return hourOfDay;
    }

    public double getAvgLatency()
    {
        return avgLatency;
    }

    public int getCount()
    {
        return count;
    }
}
