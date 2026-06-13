package at.mafue.signalreport;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lücken-saubere Zuverlässigkeits-Auswertung (Verfügbarkeit, MTBF, MTTR).
 * <p>
 * Kernregel: Verfügbarkeit ist <b>erfolgreiche ÷ tatsächlich gemessene</b>
 * Stichproben – nicht ÷ Kalenderzeit. Zeiträume ohne Messungen (Wartungsfenster,
 * Rechner aus, Absturz) liefern keine Stichproben und fallen damit von selbst aus
 * Zähler und Nenner. Ein Internet-Ausfall hingegen erzeugt <i>fehlgeschlagene</i>
 * Stichproben und zählt korrekt als Ausfallzeit.
 * <p>
 * Die Messreihe wird in zusammenhängende Läufe segmentiert: Liegen zwei
 * aufeinanderfolgende Messungen weiter als {@code 3 × Intervall} (mind. 60 s)
 * auseinander, ist das eine Lücke – ein Ausfall darf nie über eine Lücke
 * hinweg gerechnet werden.
 */
public final class ReliabilityReport
{
    /** Messungs-Typ-Marker fuer uebersprungene Wartungs-Zyklen. */
    public static final String TYPE_MAINTENANCE = "MAINTENANCE";

    /** Ein Ausfall ist eine Folge von mind. so vielen aufeinanderfolgenden Fehlschlaegen. */
    private static final int OUTAGE_MIN_CONSECUTIVE = 2;

    private final double uptimePercent;
    private final double coveragePercent;
    private final int outageCount;
    private final long longestOutageSeconds;
    private final long mtbfSeconds;
    private final long mttrSeconds;
    private final int totalSamples;
    private final int failedSamples;

    private ReliabilityReport(double uptimePercent, double coveragePercent, int outageCount,
                              long longestOutageSeconds, long mtbfSeconds, long mttrSeconds,
                              int totalSamples, int failedSamples)
    {
        this.uptimePercent = uptimePercent;
        this.coveragePercent = coveragePercent;
        this.outageCount = outageCount;
        this.longestOutageSeconds = longestOutageSeconds;
        this.mtbfSeconds = mtbfSeconds;
        this.mttrSeconds = mttrSeconds;
        this.totalSamples = totalSamples;
        this.failedSamples = failedSamples;
    }

    /**
     * Berechnet die Kennzahlen aus den (typgleichen) Messungen eines Zeitfensters.
     *
     * @param measurements      Messungen genau eines Typs (z. B. PING), beliebige Reihenfolge
     * @param intervalSeconds   konfiguriertes Mess-Intervall (zur Lücken-Erkennung)
     * @param windowSeconds     Länge des Auswertungsfensters
     * @param maintenanceSamples Anzahl der Wartungs-Marker im Fenster (geplante, erwartete Lücken)
     */
    public static ReliabilityReport compute(List<Measurement> measurements, int intervalSeconds,
                                            long windowSeconds, int maintenanceSamples)
    {
        int interval = Math.max(1, intervalSeconds);
        long gapThreshold = Math.max(interval * 3L, 60L);

        List<Measurement> sorted = new ArrayList<>(measurements);
        sorted.sort(Comparator.comparing(Measurement::getTimestamp));

        int total = 0;
        int failed = 0;
        int outageCount = 0;
        long longestOutageSeconds = 0;
        long downtimeInOutagesSeconds = 0;

        int currentFailRun = 0; // Anzahl aufeinanderfolgender Fehlschlaege im laufenden Lauf
        Instant prev = null;

        for (Measurement m : sorted)
            {
            Instant t = m.getTimestamp();
            boolean gap = prev != null && Duration.between(prev, t).getSeconds() > gapThreshold;
            if (gap)
                {
                // Offene Fehlschlag-Serie an der Lücke beenden – nicht ueber die Luecke ziehen.
                long[] closed = closeOutage(currentFailRun, interval, longestOutageSeconds);
                outageCount += (int) closed[0];
                downtimeInOutagesSeconds += closed[1];
                longestOutageSeconds = closed[2];
                currentFailRun = 0;
                }

            total++;
            if (!m.isSuccess())
                {
                failed++;
                currentFailRun++;
                } else
                {
                long[] closed = closeOutage(currentFailRun, interval, longestOutageSeconds);
                outageCount += (int) closed[0];
                downtimeInOutagesSeconds += closed[1];
                longestOutageSeconds = closed[2];
                currentFailRun = 0;
                }
            prev = t;
            }
        // letzte offene Fehlschlag-Serie abschliessen
        long[] closed = closeOutage(currentFailRun, interval, longestOutageSeconds);
        outageCount += (int) closed[0];
        downtimeInOutagesSeconds += closed[1];
        longestOutageSeconds = closed[2];

        int success = total - failed;
        double uptimePercent = total == 0 ? 0.0 : (success * 100.0 / total);

        long expectedSamples = Math.max(0, windowSeconds / interval - Math.max(0, maintenanceSamples));
        double coveragePercent = expectedSamples <= 0 ? 0.0
                : Math.min(100.0, total * 100.0 / expectedSamples);

        long measuredSeconds = (long) total * interval;
        long mtbf = outageCount == 0 ? measuredSeconds : measuredSeconds / outageCount;
        long mttr = outageCount == 0 ? 0 : downtimeInOutagesSeconds / outageCount;

        return new ReliabilityReport(uptimePercent, coveragePercent, outageCount,
                longestOutageSeconds, mtbf, mttr, total, failed);
    }

    /**
     * Schliesst eine Fehlschlag-Serie ab: liefert {@code [istAusfall(0/1), dauerSek, neuesLongest]}.
     * Eine Serie zaehlt erst ab {@link #OUTAGE_MIN_CONSECUTIVE} aufeinanderfolgenden Fehlschlaegen.
     */
    private static long[] closeOutage(int failRun, int interval, long currentLongest)
    {
        if (failRun >= OUTAGE_MIN_CONSECUTIVE)
            {
            long dur = (long) failRun * interval;
            return new long[]{1, dur, Math.max(currentLongest, dur)};
            }
        return new long[]{0, 0, currentLongest};
    }

    public double getUptimePercent()      { return uptimePercent; }
    public double getCoveragePercent()    { return coveragePercent; }
    public int    getOutageCount()        { return outageCount; }
    public long   getLongestOutageSeconds() { return longestOutageSeconds; }
    public long   getMtbfSeconds()        { return mtbfSeconds; }
    public long   getMttrSeconds()        { return mttrSeconds; }
    public int    getTotalSamples()       { return totalSamples; }
    public int    getFailedSamples()      { return failedSamples; }
    public boolean hasData()              { return totalSamples > 0; }
}
