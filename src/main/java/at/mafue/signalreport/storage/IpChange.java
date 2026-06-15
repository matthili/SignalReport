package at.mafue.signalreport.storage;

import java.time.Instant;

public class IpChange
{
    private final Instant timestamp;
    private final String oldIp;
    private final String newIp;
    private final String changeType;
    private final String hostHash;

    public IpChange(Instant timestamp, String oldIp, String newIp, String changeType, String hostHash)
    {
        this.timestamp = timestamp;
        this.oldIp = oldIp;
        this.newIp = newIp;
        this.changeType = changeType;
        this.hostHash = hostHash;
    }

    public Instant getTimestamp()
    {
        return timestamp;
    }

    public String getOldIp()
    {
        return oldIp;
    }

    public String getNewIp()
    {
        return newIp;
    }

    public String getChangeType()
    {
        return changeType;
    }

    public String getHostHash()
    {
        return hostHash;
    }
}
