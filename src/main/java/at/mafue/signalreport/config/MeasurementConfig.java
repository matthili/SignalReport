package at.mafue.signalreport.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public class MeasurementConfig
{
    @JsonProperty("intervalSeconds")
    private int intervalSeconds;
    private Targets targets;

    public int getIntervalSeconds()
    {
        return intervalSeconds;
    }

    public void setIntervalSeconds(int intervalSeconds)
    {
        this.intervalSeconds = intervalSeconds;
    }

    public Targets getTargets()
    {
        return targets;
    }

    public void setTargets(Targets targets)
    {
        this.targets = targets;
    }
}
