package at.mafue.signalreport.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Verdichtung der Rohmessungen zu Stundenwerten ({@code measurement_hourly}) und
 * Aufbewahrungsregel fuer die Rohdaten.
 * <p>
 * <b>Verdichtung:</b> Jede abgeschlossene Stunde wird einmal verdichtet (Wasserstandsmarke
 * {@value #STATE_LAST_HOUR} in {@code rollup_state}); das Ergebnis je (Stunde, Typ, Ziel) wird per
 * MERGE geschrieben, der Vorgang ist also beliebig wiederholbar. Lange Berichte, die Heatmap
 * und der Stundenwerte-Export lesen diese Tabelle statt Millionen Rohzeilen.
 * <p>
 * <b>Aufbewahrung:</b> Nach {@code retentionDays} werden erfolgreiche, unauffaellige
 * Rohmessungen geloescht, aber nur aus bereits verdichteten Stunden. Immer erhalten bleiben
 * fehlgeschlagene Messungen (die "Funde"), die jeweils erste erfolgreiche Messung nach einem
 * Fehlschlag (markiert das Ende eines Ausfalls), ausgenommene Messungen und Wartungs-Marker.
 * Geloescht wird tageweise; der Fortschritt steht in {@value #STATE_RETENTION_DONE_UNTIL}.
 */
public class RollupService
{
    private static final Logger logger = LoggerFactory.getLogger(RollupService.class);

    /** Letzte vollstaendig verdichtete Stunde (hour_start, ISO-8601). */
    public static final String STATE_LAST_HOUR = "rollup.lastHour";
    /** Tag, bis zu dem die Aufbewahrungsregel angewendet wurde (exklusiv, ISO-8601). */
    public static final String STATE_RETENTION_DONE_UNTIL = "retention.doneUntil";

    private final H2MeasurementRepository repo;

    public RollupService(H2MeasurementRepository repo)
    {
        this.repo = repo;
    }

    // ========================================================================
    //  Verdichtung
    // ========================================================================

    /** Die naechste zu verdichtende Stunde: nach der Wasserstandsmarke, sonst die aelteste Rohmessung. */
    public Instant nextHourToRollup() throws SQLException
    {
        Instant last = repo.getRollupWatermark();
        if (last != null)
            {
            return last.plus(1, ChronoUnit.HOURS);
            }
        Instant oldest = repo.findOldestMeasurement();
        return oldest == null ? null : oldest.truncatedTo(ChronoUnit.HOURS);
    }

    /**
     * Verdichtet bis zu {@code maxHours} abgeschlossene Stunden (Stunden vor der laufenden).
     *
     * @param keepGoing wird vor jeder Stunde befragt; false bricht ab (Zeitfenster zu Ende, Stopp)
     * @return Anzahl verdichteter Stunden
     */
    public int rollupCompletedHours(Instant now, int maxHours, BooleanSupplier keepGoing) throws SQLException
    {
        Instant limit = now.truncatedTo(ChronoUnit.HOURS);
        Instant hour = nextHourToRollup();
        int done = 0;
        while (hour != null && hour.isBefore(limit) && done < maxHours && keepGoing.getAsBoolean())
            {
            rollupHour(hour);
            repo.setRollupWatermark(hour);
            hour = hour.plus(1, ChronoUnit.HOURS);
            done++;
            }
        if (done > 0)
            {
            logger.info("Verdichtung: {} Stunde(n) bis {} verarbeitet.", done, hour.minus(1, ChronoUnit.HOURS));
            }
        return done;
    }

    /** Verdichtet genau eine Stunde (MERGE, also wiederholbar) und liefert die geschriebenen Stundenwerte. */
    public List<HourlyRollup> rollupHour(Instant hourStart) throws SQLException
    {
        Instant end = hourStart.plus(1, ChronoUnit.HOURS);
        List<RawRow> rows = repo.findRawRows(hourStart, end);
        List<HourlyRollup> rollups = computeRollups(hourStart, rows);
        for (HourlyRollup r : rollups)
            {
            repo.mergeHourlyRollup(r);
            }
        return rollups;
    }

    private record GroupKey(String type, String target)
    {
    }

    private static final class Accumulator
    {
        final String hostHash;
        int samples;
        int ok;
        int excluded;
        final List<Double> latencies = new ArrayList<>();   // chronologisch, nur erfolgreiche
        double max = Double.NEGATIVE_INFINITY;
        Instant maxAt;

        Accumulator(String hostHash)
        {
            this.hostHash = hostHash;
        }
    }

    /**
     * Reine Berechnung (testbar ohne DB): Rohzeilen einer Stunde in chronologischer
     * Reihenfolge -> ein Stundenwert je (Typ, Ziel).
     */
    static List<HourlyRollup> computeRollups(Instant hourStart, List<RawRow> rows)
    {
        Map<GroupKey, Accumulator> groups = new LinkedHashMap<>();
        for (RawRow r : rows)
            {
            Accumulator a = groups.computeIfAbsent(new GroupKey(r.type(), r.target()), k -> new Accumulator(r.hostHash()));
            if (r.excluded())
                {
                a.excluded++;
                continue;
                }
            a.samples++;
            if (r.success())
                {
                a.ok++;
                a.latencies.add(r.latencyMs());
                if (r.latencyMs() > a.max)
                    {
                    a.max = r.latencyMs();
                    a.maxAt = r.timestamp();
                    }
                }
            }

        List<HourlyRollup> result = new ArrayList<>();
        for (Map.Entry<GroupKey, Accumulator> e : groups.entrySet())
            {
            Accumulator a = e.getValue();
            double min = 0, avg = 0, median = 0, p95 = 0, max = 0, jitter = 0;
            Instant maxAt = null;
            if (!a.latencies.isEmpty())
                {
                List<Double> sorted = new ArrayList<>(a.latencies);
                Collections.sort(sorted);
                int n = sorted.size();
                min = sorted.get(0);
                max = sorted.get(n - 1);
                maxAt = a.maxAt;
                double sum = 0;
                for (double v : a.latencies) sum += v;
                avg = sum / n;
                median = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
                int p95Index = Math.max(0, Math.min((int) Math.ceil(n * 0.95) - 1, n - 1));
                p95 = sorted.get(p95Index);
                if (n > 1)
                    {
                    double diffs = 0;
                    for (int i = 1; i < n; i++) diffs += Math.abs(a.latencies.get(i) - a.latencies.get(i - 1));
                    jitter = diffs / (n - 1);
                    }
                }
            result.add(new HourlyRollup(hourStart, e.getKey().type(), e.getKey().target(), a.hostHash,
                    a.samples, a.ok, a.excluded, min, avg, median, p95, max, maxAt, jitter));
            }
        return result;
    }

    // ========================================================================
    //  Aufbewahrung
    // ========================================================================

    /**
     * Wendet die Aufbewahrungsregel an: loescht erfolgreiche, unauffaellige Rohmessungen, die
     * aelter als {@code retentionDays} sind und deren Stunde bereits verdichtet wurde.
     *
     * @param retentionDays 0 = nie loeschen
     * @param maxDays       hoechstens so viele Tage pro Lauf
     * @param keepGoing     wird vor jedem Tag befragt; false bricht ab
     * @return Anzahl geloeschter Zeilen (Primary)
     */
    public long applyRetention(Instant now, int retentionDays, int maxDays, BooleanSupplier keepGoing) throws SQLException
    {
        if (retentionDays <= 0)
            {
            return 0;
            }
        Instant watermark = repo.getRollupWatermark();
        if (watermark == null)
            {
            return 0; // noch nichts verdichtet -> nichts loeschen
            }
        Instant cutoff = now.minus(retentionDays, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
        Instant rolledUpUntil = watermark.plus(1, ChronoUnit.HOURS);
        Instant limit = cutoff.isBefore(rolledUpUntil) ? cutoff : rolledUpUntil;

        Instant day = repo.getRetentionDoneUntil();
        if (day == null)
            {
            Instant oldest = repo.findOldestMeasurement();
            if (oldest == null) return 0;
            day = oldest.truncatedTo(ChronoUnit.DAYS);
            }

        long deleted = 0;
        int days = 0;
        while (day.isBefore(limit) && days < maxDays && keepGoing.getAsBoolean())
            {
            Instant next = day.plus(1, ChronoUnit.DAYS);
            if (next.isAfter(limit)) next = limit;
            deleted += repo.deleteAggregatedOkRows(day, next);
            repo.setRetentionDoneUntil(next);
            day = next;
            days++;
            }
        if (days > 0)
            {
            logger.info("Aufbewahrung: {} Tag(e) bearbeitet, {} Rohzeilen geloescht (bis {}).", days, deleted, day);
            }
        return deleted;
    }
}
