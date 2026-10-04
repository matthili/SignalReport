package at.mafue.signalreport.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

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
 * <p>
 * <b>Ruecksicht auf den Messbetrieb:</b> Lange Laeufe (Erstverdichtung von Monaten) gehoeren
 * auf einen Nebenkanal des Repositories ({@link H2MeasurementRepository#openSideChannel()}),
 * damit sie Messschleife und Web-Anfragen nicht blockieren; zusaetzlich legt der Dienst nach
 * jeder Stunde und jedem Tag eine kurze Pause ein ({@code pauseMillis}).
 */
public class RollupService
{
    private static final Logger logger = LoggerFactory.getLogger(RollupService.class);

    /** Letzte vollstaendig verdichtete Stunde (hour_start, ISO-8601). */
    public static final String STATE_LAST_HOUR = "rollup.lastHour";
    /** Tag, bis zu dem die Aufbewahrungsregel angewendet wurde (exklusiv, ISO-8601). */
    public static final String STATE_RETENTION_DONE_UNTIL = "retention.doneUntil";
    /** Alle so viele Stunden eine Fortschrittszeile im Log. */
    static final int LOG_EVERY_HOURS = 100;

    private final H2MeasurementRepository repo;
    private final long pauseMillis;

    public RollupService(H2MeasurementRepository repo)
    {
        this(repo, 0L);
    }

    /** @param pauseMillis kurze Pause nach jeder verdichteten Stunde und jedem bearbeiteten Tag (0 = keine) */
    public RollupService(H2MeasurementRepository repo, long pauseMillis)
    {
        this.repo = repo;
        this.pauseMillis = Math.max(0L, pauseMillis);
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

    /** Anzahl abgeschlossener, noch nicht verdichteter Stunden. */
    public long pendingHours(Instant now) throws SQLException
    {
        Instant next = nextHourToRollup();
        if (next == null)
            {
            return 0;
            }
        return Math.max(0, ChronoUnit.HOURS.between(next, now.truncatedTo(ChronoUnit.HOURS)));
    }

    public int rollupCompletedHours(Instant now, int maxHours, BooleanSupplier keepGoing) throws SQLException
    {
        return rollupCompletedHours(now, maxHours, keepGoing, null);
    }

    /**
     * Verdichtet bis zu {@code maxHours} abgeschlossene Stunden (Stunden vor der laufenden).
     *
     * @param keepGoing wird vor jeder Stunde befragt; false bricht ab (Zeitfenster zu Ende, Stopp)
     * @param onHour    Fortschritt (Anzahl verarbeiteter Stunden) nach jeder Stunde, darf null sein
     * @return Anzahl verdichteter Stunden
     */
    public int rollupCompletedHours(Instant now, int maxHours, BooleanSupplier keepGoing, IntConsumer onHour) throws SQLException
    {
        Instant limit = now.truncatedTo(ChronoUnit.HOURS);
        Instant hour = nextHourToRollup();
        long pending = hour == null ? 0 : Math.max(0, ChronoUnit.HOURS.between(hour, limit));
        int done = 0;
        while (hour != null && hour.isBefore(limit) && done < maxHours && keepGoing.getAsBoolean())
            {
            rollupHour(hour);
            repo.setRollupWatermark(hour);
            hour = hour.plus(1, ChronoUnit.HOURS);
            done++;
            if (onHour != null)
                {
                onHour.accept(done);
                }
            if (done % LOG_EVERY_HOURS == 0)
                {
                logger.info("Verdichtung: {} von {} Stunden verarbeitet (bis {}).", done, pending, hour);
                }
            if (!pause())
                {
                break;
                }
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
        double[] latencies = new double[64];   // chronologisch, nur erfolgreiche
        int n;
        double max = Double.NEGATIVE_INFINITY;
        Instant maxAt;

        Accumulator(String hostHash)
        {
            this.hostHash = hostHash;
        }

        void add(double value)
        {
            if (n == latencies.length)
                {
                latencies = Arrays.copyOf(latencies, n * 2);
                }
            latencies[n++] = value;
        }
    }

    /**
     * Reine Berechnung (testbar ohne DB): Rohzeilen einer Stunde in chronologischer
     * Reihenfolge -> ein Stundenwert je (Typ, Ziel).
     * <p>
     * Sortiert wird ueber ein primitives Array ({@code Arrays.sort(double[])}, Dual-Pivot-Quicksort),
     * bewusst nicht ueber TimSort auf Objektlisten: Darin stuerzte der JIT des JDK 26 der
     * Referenzinstallation beim Datenbank-Neuaufbau ab (Ursache nicht abschliessend geklaert).
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
                a.add(r.latencyMs());
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
            int n = a.n;
            if (n > 0)
                {
                double[] sorted = Arrays.copyOf(a.latencies, n);
                Arrays.sort(sorted);
                min = sorted[0];
                max = sorted[n - 1];
                maxAt = a.maxAt;
                double sum = 0;
                for (int i = 0; i < n; i++) sum += a.latencies[i];
                avg = sum / n;
                median = n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
                int p95Index = Math.max(0, Math.min((int) Math.ceil(n * 0.95) - 1, n - 1));
                p95 = sorted[p95Index];
                if (n > 1)
                    {
                    double diffs = 0;
                    for (int i = 1; i < n; i++) diffs += Math.abs(a.latencies[i] - a.latencies[i - 1]);
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

    public long applyRetention(Instant now, int retentionDays, int maxDays, BooleanSupplier keepGoing) throws SQLException
    {
        return applyRetention(now, retentionDays, maxDays, keepGoing, null);
    }

    /**
     * Wendet die Aufbewahrungsregel an: loescht erfolgreiche, unauffaellige Rohmessungen, die
     * aelter als {@code retentionDays} sind und deren Stunde bereits verdichtet wurde.
     *
     * @param retentionDays 0 = nie loeschen
     * @param maxDays       hoechstens so viele Tage pro Lauf
     * @param keepGoing     wird vor jedem Tag befragt; false bricht ab
     * @param onDay         Fortschritt (bisher geloeschte Zeilen) nach jedem Tag, darf null sein
     * @return Anzahl geloeschter Zeilen (Primary)
     */
    public long applyRetention(Instant now, int retentionDays, int maxDays, BooleanSupplier keepGoing, LongConsumer onDay) throws SQLException
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
            H2MeasurementRepository.DeleteOutcome out = repo.deleteAggregatedOkRows(day, next, keepGoing);
            deleted += out.deleted();
            if (!out.completed())
                {
                // Abbruch mitten im Tag (Fenster zu Ende, Stopp): Fortschritt nicht vorruecken, der Tag
                // wird beim naechsten Lauf neu ausgewertet (die Kandidaten werden jedes Mal neu bestimmt).
                logger.info("Aufbewahrung: angehalten bei {} nach {} geloeschten Rohzeilen; Fortsetzung beim naechsten Lauf.", day, out.deleted());
                if (onDay != null) onDay.accept(deleted);
                break;
                }
            repo.setRetentionDoneUntil(next);
            logger.info("Aufbewahrung: {} bis {} bearbeitet, {} Rohzeilen geloescht.", day, next, out.deleted());
            day = next;
            days++;
            if (onDay != null)
                {
                onDay.accept(deleted);
                }
            if (!pause())
                {
                break;
                }
            }
        if (days > 0)
            {
            logger.info("Aufbewahrung: {} Tag(e) bearbeitet, {} Rohzeilen geloescht (bis {}).", days, deleted, day);
            }
        return deleted;
    }

    /** Kurze Pause, damit andere Threads an die Datenbank kommen; false bei Interrupt (Stopp). */
    private boolean pause()
    {
        if (pauseMillis <= 0)
            {
            return true;
            }
        try
            {
            Thread.sleep(pauseMillis);
            return true;
            } catch (InterruptedException e)
            {
            Thread.currentThread().interrupt();
            return false;
            }
    }
}
