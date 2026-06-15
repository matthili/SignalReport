package at.mafue.signalreport.storage;

import java.time.Instant;

public class IpChangeStats
{
    private final String hostHash;
    private final int changeCount;
    private final Instant firstChange;
    private final Instant lastChange;

    public IpChangeStats(String hostHash, int changeCount, Instant firstChange, Instant lastChange)
    {
        this.hostHash = hostHash;
        this.changeCount = changeCount;
        this.firstChange = firstChange;
        this.lastChange = lastChange;
    }

    public String getHostHash()
    {
        return hostHash;
    }

    public int getChangeCount()
    {
        return changeCount;
    }

    public Instant getFirstChange()
    {
        return firstChange;
    }

    public Instant getLastChange()
    {
        return lastChange;
    }
}
