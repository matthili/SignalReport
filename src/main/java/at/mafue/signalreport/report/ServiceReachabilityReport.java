package at.mafue.signalreport.report;

import at.mafue.signalreport.storage.ServiceCheck;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Verdichtet die gespeicherten Erreichbarkeits-Pruefungen eines Dienstes zu
 * Episoden -- zusammenhaengende Laeufe gleichen Verdikts -- analog dazu, wie
 * {@link ReliabilityReport} Fehlmessungen zu Ausfaellen zusammenfasst.
 * <p>
 * Das ergibt die Langzeit-Erzaehlung "erreichbar bis 1. Maerz, dann gesperrt bis
 * 11. Maerz, danach wieder erreichbar": Jeder Verdikt-Wechsel beendet eine Episode
 * und beginnt die naechste; Luecken innerhalb gleichen Verdikts werden absorbiert
 * (die Stichprobenzahl je Episode zeigt, wie dicht der Lauf belegt ist).
 */
public final class ServiceReachabilityReport
{
    private static final String VERDICT_REACHABLE = "REACHABLE";
    private static final String VERDICT_NOT_CHECKED = "NOT_CHECKED";

    private final String serviceId;
    private final String currentVerdict;
    private final Instant currentSince;
    private final double reachablePercent;
    private final int totalChecks;
    private final List<Episode> episodes;

    private ServiceReachabilityReport(String serviceId, String currentVerdict, Instant currentSince,
                                      double reachablePercent, int totalChecks, List<Episode> episodes)
    {
        this.serviceId = serviceId;
        this.currentVerdict = currentVerdict;
        this.currentSince = currentSince;
        this.reachablePercent = reachablePercent;
        this.totalChecks = totalChecks;
        this.episodes = episodes;
    }

    /**
     * Eine Episode: ein zusammenhaengender Lauf von Pruefungen mit demselben
     * Verdikt. {@code durationSeconds} ist die Spanne von der ersten bis zur
     * letzten Pruefung des Laufs, {@code sampleCount} die Anzahl der Pruefungen.
     */
    public static final class Episode
    {
        private final String verdict;
        private final Instant start;
        private final Instant end;
        private final long durationSeconds;
        private final int sampleCount;

        Episode(String verdict, Instant start, Instant end, long durationSeconds, int sampleCount)
        {
            this.verdict = verdict;
            this.start = start;
            this.end = end;
            this.durationSeconds = durationSeconds;
            this.sampleCount = sampleCount;
        }

        public String getVerdict()       { return verdict; }
        public Instant getStart()        { return start; }
        public Instant getEnd()          { return end; }
        public long getDurationSeconds() { return durationSeconds; }
        public int  getSampleCount()     { return sampleCount; }
    }

    /**
     * Berechnet Episoden und Kennzahlen aus den Pruefungen genau eines Dienstes
     * (beliebige Reihenfolge; wird chronologisch sortiert).
     */
    public static ServiceReachabilityReport compute(String serviceId, List<ServiceCheck> checks)
    {
        List<ServiceCheck> sorted = new ArrayList<>(checks);
        sorted.sort(Comparator.comparing(ServiceCheck::getTimestamp));

        List<Episode> episodes = new ArrayList<>();
        String runVerdict = null;
        Instant start = null;
        Instant end = null;
        int count = 0;
        int reachable = 0;

        for (ServiceCheck c : sorted)
            {
            String v = c.getVerdict();
            if (VERDICT_REACHABLE.equals(v))
                {
                reachable++;
                }

            if (runVerdict == null)
                {
                runVerdict = v;
                start = c.getTimestamp();
                end = c.getTimestamp();
                count = 1;
                } else if (runVerdict.equals(v))
                {
                end = c.getTimestamp();
                count++;
                } else
                {
                episodes.add(makeEpisode(runVerdict, start, end, count));
                runVerdict = v;
                start = c.getTimestamp();
                end = c.getTimestamp();
                count = 1;
                }
            }
        if (runVerdict != null)
            {
            episodes.add(makeEpisode(runVerdict, start, end, count));
            }

        int total = sorted.size();
        double reachablePercent = total == 0 ? 0.0 : (reachable * 100.0 / total);
        String currentVerdict = total == 0 ? VERDICT_NOT_CHECKED : sorted.get(total - 1).getVerdict();
        Instant currentSince = episodes.isEmpty() ? null : episodes.get(episodes.size() - 1).getStart();

        return new ServiceReachabilityReport(serviceId, currentVerdict, currentSince,
                reachablePercent, total, episodes);
    }

    private static Episode makeEpisode(String verdict, Instant start, Instant end, int count)
    {
        long duration = Math.max(0, Duration.between(start, end).getSeconds());
        return new Episode(verdict, start, end, duration, count);
    }

    public String getServiceId()        { return serviceId; }
    public String getCurrentVerdict()   { return currentVerdict; }
    public Instant getCurrentSince()    { return currentSince; }
    public double getReachablePercent() { return reachablePercent; }
    public int    getTotalChecks()      { return totalChecks; }
    public List<Episode> getEpisodes()  { return episodes; }
    public boolean hasData()            { return totalChecks > 0; }
}
