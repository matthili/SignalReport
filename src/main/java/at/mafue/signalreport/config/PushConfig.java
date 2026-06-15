package at.mafue.signalreport.config;

public class PushConfig
{
    private boolean enabled = false;
    private double latencyThreshold = 100.0; // ms
    private int consecutiveBadMeasurements = 2;

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public double getLatencyThreshold()
    {
        return latencyThreshold;
    }

    public void setLatencyThreshold(double threshold)
    {
        this.latencyThreshold = threshold;
    }

    public int getConsecutiveBadMeasurements()
    {
        return consecutiveBadMeasurements;
    }

    public void setConsecutiveBadMeasurements(int count)
    {
        this.consecutiveBadMeasurements = count;
    }
}
