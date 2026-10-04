package at.mafue.signalreport.storage;

import java.time.Instant;

/**
 * Eine Stunde eines Messtyps und Ziels in verdichteter Form (Tabelle {@code measurement_hourly}).
 * <p>
 * Zaehlweise wie in {@link H2MeasurementRepository#calculateStatistics}: {@code sampleCount}
 * sind die nicht ausgenommenen Messungen, {@code okCount} die erfolgreichen davon; Latenz-
 * Kennzahlen beziehen sich auf die erfolgreichen, nicht ausgenommenen Messungen in
 * chronologischer Reihenfolge (Jitter = mittlere Abweichung aufeinanderfolgender Werte).
 */
public class HourlyRollup
{
    private final Instant hourStart;
    private final String type;
    private final String target;
    private final String hostHash;
    private final int sampleCount;
    private final int okCount;
    private final int excludedCount;
    private final double minMs;
    private final double avgMs;
    private final double medianMs;
    private final double p95Ms;
    private final double maxMs;
    private final Instant maxAt;
    private final double jitterMs;

    public HourlyRollup(Instant hourStart, String type, String target, String hostHash,
                        int sampleCount, int okCount, int excludedCount,
                        double minMs, double avgMs, double medianMs, double p95Ms, double maxMs,
                        Instant maxAt, double jitterMs)
    {
        this.hourStart = hourStart;
        this.type = type;
        this.target = target;
        this.hostHash = hostHash;
        this.sampleCount = sampleCount;
        this.okCount = okCount;
        this.excludedCount = excludedCount;
        this.minMs = minMs;
        this.avgMs = avgMs;
        this.medianMs = medianMs;
        this.p95Ms = p95Ms;
        this.maxMs = maxMs;
        this.maxAt = maxAt;
        this.jitterMs = jitterMs;
    }

    public Instant getHourStart()  { return hourStart; }
    public String getType()        { return type; }
    public String getTarget()      { return target; }
    public String getHostHash()    { return hostHash; }
    public int getSampleCount()    { return sampleCount; }
    public int getOkCount()        { return okCount; }
    public int getExcludedCount()  { return excludedCount; }
    public int getFailedCount()    { return sampleCount - okCount; }
    public double getMinMs()       { return minMs; }
    public double getAvgMs()       { return avgMs; }
    public double getMedianMs()    { return medianMs; }
    public double getP95Ms()       { return p95Ms; }
    public double getMaxMs()       { return maxMs; }
    public Instant getMaxAt()      { return maxAt; }
    public double getJitterMs()    { return jitterMs; }
}
