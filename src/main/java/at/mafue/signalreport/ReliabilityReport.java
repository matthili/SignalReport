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

    private final List<Outage> outages;

    private ReliabilityReport(double uptimePercent, double coveragePercent, int outageCount,
                              long longestOutageSeconds, long mtbfSeconds, long mttrSeconds,
                              int totalSamples, int failedSamples, List<Outage> outages)
    {
        this.uptimePercent = uptimePercent;
        this.coveragePercent = coveragePercent;
        this.outageCount = outageCount;
        this.longestOutageSeconds = longestOutageSeconds;
        this.mtbfSeconds = mtbfSeconds;
        this.mttrSeconds = mttrSeconds;
        this.totalSamples = totalSamples;
        this.failedSamples = failedSamples;
        this.outages = outages;
    }

    /**
     * Ein aggregierter Ausfall: eine zusammenhaengende Folge von Fehlschlaegen
     * (mind. {@link #OUTAGE_MIN_CONSECUTIVE}) innerhalb eines Mess-Laufs.
     * {@code excluded} ist true, wenn alle zugehoerigen Messungen aus der
     * Auswertung ausgenommen wurden.
     */
    public static final class Outage
    {
        private final Instant start;
        private final Instant end;
        private final long durationSeconds;
        private final int sampleCount;
        private final boolean excluded;

        Outage(Instant start, Instant end, long durationSeconds, int sampleCount, boolean excluded)
        {
            this.start = start;
            this.end = end;
            this.durationSeconds = durationSeconds;
            this.sampleCount = sampleCount;
            this.excluded = excluded;
        }

        public Instant getStart()        { return start; }
        public Instant getEnd()          { return end; }
        public long getDurationSeconds() { return durationSeconds; }
        public int  getSampleCount()     { return sampleCount; }
        public boolean isExcluded()      { return excluded; }
    }

    /**
     * Berechnet die Kennzahlen aus den (typgleichen) Messungen eines Zeitfensters.
     * Liefert auch die Liste der aggregierten Ausfaelle (inkl. ausgenommener, mit Flag).
     * Kennzahlen (Verfuegbarkeit, Ausfallzahl, MTBF, MTTR) beruhen NUR auf nicht
     * ausgenommenen Messungen.
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

        // Vollständige Ausfall-Liste (inkl. ausgenommener, fuer die Anzeige)
        List<Outage> allOutages = detectOutages(sorted, interval, gapThreshold);

        // Kennzahlen nur ueber nicht ausgenommene Messungen
        int total = 0;
        int failed = 0;
        int excludedCount = 0;
        for (Measurement m : sorted)
            {
            if (m.isExcluded()) { excludedCount++; continue; }
            total++;
            if (!m.isSuccess()) failed++;
            }
        int success = total - failed;
        double uptimePercent = total == 0 ? 0.0 : (success * 100.0 / total);

        // Erwartete Stichproben ohne geplante (Wartung) und ausgenommene Zyklen –
        // dadurch senkt das Ausschliessen eines Ausfalls die Abdeckung nicht.
        long expectedSamples = Math.max(0,
                windowSeconds / interval - Math.max(0, maintenanceSamples) - excludedCount);
        double coveragePercent = expectedSamples <= 0 ? 0.0
                : Math.min(100.0, total * 100.0 / expectedSamples);

        int outageCount = 0;
        long longestOutageSeconds = 0;
        long downtimeSeconds = 0;
        for (Outage o : allOutages)
            {
            if (o.isExcluded()) continue;
            outageCount++;
            downtimeSeconds += o.getDurationSeconds();
            longestOutageSeconds = Math.max(longestOutageSeconds, o.getDurationSeconds());
            }

        long measuredSeconds = (long) total * interval;
        long mtbf = outageCount == 0 ? measuredSeconds : measuredSeconds / outageCount;
        long mttr = outageCount == 0 ? 0 : downtimeSeconds / outageCount;

        return new ReliabilityReport(uptimePercent, coveragePercent, outageCount,
                longestOutageSeconds, mtbf, mttr, total, failed, allOutages);
    }

    /**
     * Erkennt aggregierte Ausfaelle: zusammenhaengende Fehlschlag-Serien innerhalb
     * eines Mess-Laufs. Eine Lücke (Abstand &gt; {@code gapThreshold}) beendet einen
     * Lauf – ein Ausfall wird nie ueber eine Lücke hinweg zusammengezogen.
     */
    private static List<Outage> detectOutages(List<Measurement> sorted, int interval, long gapThreshold)
    {
        List<Outage> outages = new ArrayList<>();
        int failRun = 0;
        Instant runStart = null;
        Instant runEnd = null;
        boolean runAllExcluded = true;
        Instant prev = null;

        for (Measurement m : sorted)
            {
            Instant t = m.getTimestamp();
            boolean gap = prev != null && Duration.between(prev, t).getSeconds() > gapThreshold;
            if (gap)
                {
                addOutage(outages, failRun, runStart, runEnd, runAllExcluded, interval);
                failRun = 0; runStart = null; runAllExcluded = true;
                }

            if (!m.isSuccess())
                {
                if (failRun == 0) { runStart = t; runAllExcluded = true; }
                failRun++;
                runEnd = t;
                if (!m.isExcluded()) runAllExcluded = false;
                } else
                {
                addOutage(outages, failRun, runStart, runEnd, runAllExcluded, interval);
                failRun = 0; runStart = null; runAllExcluded = true;
                }
            prev = t;
            }
        addOutage(outages, failRun, runStart, runEnd, runAllExcluded, interval);
        return outages;
    }

    private static void addOutage(List<Outage> outages, int failRun, Instant start, Instant end,
                                  boolean allExcluded, int interval)
    {
        if (failRun >= OUTAGE_MIN_CONSECUTIVE && start != null && end != null)
            {
            long span = Duration.between(start, end).getSeconds();
            long dur = Math.max(span, interval);
            outages.add(new Outage(start, end, dur, failRun, allExcluded));
            }
    }

    public List<Outage> getOutages()      { return outages; }
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
