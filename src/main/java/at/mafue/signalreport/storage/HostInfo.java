package at.mafue.signalreport.storage;

import java.time.Instant;

public class HostInfo
{
    private final String hostHash;
    private final String hostname;
    private final String operatingSystem;
    private final Instant firstSeen;
    private final Instant lastSeen;

    public HostInfo(String hostHash, String hostname, String operatingSystem,
                    Instant firstSeen, Instant lastSeen)
    {
        this.hostHash = hostHash;
        this.hostname = hostname;
        this.operatingSystem = operatingSystem;
        this.firstSeen = firstSeen;
        this.lastSeen = lastSeen;
    }

    public String getHostHash()
    {
        return hostHash;
    }

    public String getHostname()
    {
        return hostname;
    }

    public String getOperatingSystem()
    {
        return operatingSystem;
    }

    public Instant getFirstSeen()
    {
        return firstSeen;
    }

    public Instant getLastSeen()
    {
        return lastSeen;
    }
}
