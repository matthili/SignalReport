package at.mafue.signalreport.storage;

public class Statistics
{
    private final double avgLatency;
    private final double p95Latency;
    private final double maxLatency;
    private final double packetLossPercent;
    private final double jitter;

    public Statistics(double avgLatency, double p95Latency, double maxLatency,
                      double packetLossPercent, double jitter)
    {
        this.avgLatency = avgLatency;
        this.p95Latency = p95Latency;
        this.maxLatency = maxLatency;
        this.packetLossPercent = packetLossPercent;
        this.jitter = jitter;
    }

    public double getAvgLatency()
    {
        return avgLatency;
    }

    public double getP95Latency()
    {
        return p95Latency;
    }

    public double getMaxLatency()
    {
        return maxLatency;
    }

    public double getPacketLossPercent()
    {
        return packetLossPercent;
    }

    public double getJitter()
    {
        return jitter;
    }
}
