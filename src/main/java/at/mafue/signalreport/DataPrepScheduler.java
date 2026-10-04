package at.mafue.signalreport;

import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.config.DataPrepConfig;
import at.mafue.signalreport.storage.H2MeasurementRepository;
import at.mafue.signalreport.storage.RollupService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Steuert die Daten-Aufbereitung im Hintergrund (Daemon-Thread, Minutentakt):
 * <ul>
 *   <li><b>Laufend:</b> die jeweils abgeschlossenen Stunden werden verdichtet, hoechstens
 *       {@value #HOURS_PER_TICK_OUTSIDE_WINDOW} pro Minute, damit der Messbetrieb nie
 *       lange auf die Datenbank wartet.</li>
 *   <li><b>Im Aufbereitungsfenster</b> (einmal pro Tag) oder auf Knopfdruck: Rueckstand
 *       der Verdichtung komplett abarbeiten, danach die Aufbewahrungsregel anwenden.
 *       Beides bricht am Ende des Fensters ab und macht beim naechsten Mal weiter.</li>
 * </ul>
 * Jeder Lauf arbeitet auf einem <b>Nebenkanal</b> des Repositories (eigene H2-Verbindungen,
 * {@link H2MeasurementRepository#openSideChannel()}) und legt nach jeder Stunde eine kurze Pause
 * ein. Bis 2.2.0 lief alles auf den Hauptverbindungen; ein Voll-Lauf ueber Monate blockierte
 * damit Messschleife und Web-Oberflaeche minutenlang. Es laeuft immer hoechstens ein Lauf
 * ({@code running}, ohne blockierende Sperre: wer nicht drankommt, bekommt sofort Bescheid).
 * Der manuelle Lauf hat eine Abkuehlphase von 5 Minuten (wie "Jetzt pruefen").
 */
public class DataPrepScheduler
{
    private static final Logger logger = LoggerFactory.getLogger(DataPrepScheduler.class);

    static final int HOURS_PER_TICK_OUTSIDE_WINDOW = 2;
    static final int RETENTION_DAYS_PER_RUN = 400;
    /** Pause nach jeder verdichteten Stunde bzw. jedem Aufbewahrungstag. */
    static final long PAUSE_MILLIS = 10L;
    private static final long INITIAL_DELAY_MS = 30_000L;
    private static final long TICK_MS = 60_000L;
    private static final int MANUAL_COOLDOWN_MINUTES = 5;
    private static final long MANUAL_RUN_BUDGET_SECONDS = 2 * 3600L;
    /** Beim Stopp hoechstens so lange auf das Ende eines laufenden Laufs warten. */
    private static final long STOP_WAIT_MS = 15_000L;

    private final H2MeasurementRepository repo;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile boolean stopRequested = false;
    private volatile Instant lastRunStart;
    private volatile Instant lastRunEnd;
    private volatile int lastHoursRolled = -1;
    private volatile long lastRowsDeleted = -1;
    private volatile String lastError;
    private volatile LocalDate lastWindowRunDay;
    private long lastManualRunMs = 0L;

    // Fortschritt des laufenden Laufs (fuer Status-Karte und Log)
    private volatile int runHoursDone;
    private volatile long runHoursTotal;
    private volatile long runRowsDeleted;

    public DataPrepScheduler(H2MeasurementRepository repo)
    {
        this.repo = repo;
    }

    public void start()
    {
        Thread t = new Thread(this::loop, "signalreport-dataprep");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Beendet laufende Arbeit an der naechsten Stunden-/Tagesgrenze, startet keine neue und
     * wartet kurz auf das Ende, damit der Nebenkanal geschlossen ist, bevor die Datenbank
     * geschlossen wird.
     */
    public void stop()
    {
        stopRequested = true;
        long deadline = System.currentTimeMillis() + STOP_WAIT_MS;
        while (running.get() && System.currentTimeMillis() < deadline)
            {
            if (!sleepMillis(50))
                {
                return;
                }
            }
        if (running.get())
            {
            logger.warn("Daten-Aufbereitung laeuft beim Stopp noch; sie bricht an der naechsten Grenze ab.");
            }
    }

    private void loop()
    {
        if (!sleepMillis(INITIAL_DELAY_MS)) return;
        while (!stopRequested)
            {
            try
                {
                tick(Instant.now());
                } catch (Exception e)
                {
                lastError = e.getMessage();
                logger.error("Fehler in der Daten-Aufbereitung: {}", e.getMessage());
                }
            if (!sleepMillis(TICK_MS)) return;
            }
    }

    /** Ein Takt: im Fenster der volle Tageslauf, sonst nur die leichte Verdichtung. */
    void tick(Instant now) throws Exception
    {
        Config config = Config.getInstance();
        if (config == null) return;
        DataPrepConfig cfg = config.getDataPrep();
        if (!cfg.isEnabled()) return;

        ZoneId zone = ZoneId.systemDefault();
        LocalTime localNow = now.atZone(zone).toLocalTime();
        LocalDate today = now.atZone(zone).toLocalDate();
        boolean inWindow = cfg.isWindowNow(localNow, config.getMaintenanceWindow());

        if (shouldRunFull(inWindow, today, lastWindowRunDay))
            {
            boolean started = runFull(cfg, () -> !stopRequested
                    && cfg.isWindowNow(Instant.now().atZone(zone).toLocalTime(), config.getMaintenanceWindow()));
            if (started)
                {
                lastWindowRunDay = today;   // sonst naechste Minute erneut versuchen
                }
            } else
            {
            rollupIncrement(now);
            }
    }

    /** Leichter Schritt ausserhalb des Fensters: hoechstens 2 Stunden, nur wenn gerade kein Lauf laeuft. */
    private void rollupIncrement(Instant now) throws SQLException
    {
        if (!running.compareAndSet(false, true))
            {
            return;
            }
        try (H2MeasurementRepository side = repo.openSideChannel())
            {
            new RollupService(side, PAUSE_MILLIS).rollupCompletedHours(now, HOURS_PER_TICK_OUTSIDE_WINDOW, () -> !stopRequested);
            } finally
            {
            running.set(false);
            }
    }

    /** Reine Entscheidung (testbar): voller Lauf genau einmal pro Tag, sobald das Fenster offen ist. */
    static boolean shouldRunFull(boolean inWindow, LocalDate today, LocalDate lastRunDay)
    {
        return inWindow && !today.equals(lastRunDay);
    }

    /**
     * Startet den vollen Lauf sofort im Hintergrund (Button "Jetzt ausfuehren"), sofern die
     * Abkuehlphase abgelaufen ist und kein Lauf aktiv ist. Kehrt immer sofort zurueck.
     *
     * @return verbleibende Abkuehlzeit in Sekunden (0 = gestartet; 1 = es laeuft bereits ein Lauf)
     */
    public long triggerManualRun()
    {
        long now = System.currentTimeMillis();
        long cooldownMs = MANUAL_COOLDOWN_MINUTES * 60_000L;
        synchronized (this)   // schuetzt nur die Abkuehlphase, wird nie waehrend eines Laufs gehalten
            {
            if (lastManualRunMs > 0 && (now - lastManualRunMs) < cooldownMs)
                {
                return (cooldownMs - (now - lastManualRunMs)) / 1000L;
                }
            if (running.get())
                {
                return 1L;
                }
            lastManualRunMs = now;
            }
        Instant deadline = Instant.now().plusSeconds(MANUAL_RUN_BUDGET_SECONDS);
        Thread t = new Thread(() ->
        {
        Config config = Config.getInstance();
        DataPrepConfig cfg = config != null ? config.getDataPrep() : new DataPrepConfig();
        if (!runFull(cfg, () -> !stopRequested && Instant.now().isBefore(deadline)))
            {
            logger.info("Daten-Aufbereitung: manueller Start uebersprungen, es laeuft bereits ein Lauf.");
            }
        }, "signalreport-dataprep-manual");
        t.setDaemon(true);
        t.start();
        return 0L;
    }

    /**
     * Voller Lauf auf einem Nebenkanal: Verdichtungs-Rueckstand komplett, dann Aufbewahrung;
     * beides bricht ab, wenn keepGoing false liefert.
     *
     * @return false, wenn bereits ein Lauf aktiv war (dann passiert nichts)
     */
    boolean runFull(DataPrepConfig cfg, BooleanSupplier keepGoing)
    {
        if (!running.compareAndSet(false, true))
            {
            return false;
            }
        lastRunStart = Instant.now();
        lastError = null;
        runHoursDone = 0;
        runHoursTotal = 0;
        runRowsDeleted = 0;
        try (H2MeasurementRepository side = repo.openSideChannel())
            {
            RollupService rollups = new RollupService(side, PAUSE_MILLIS);
            runHoursTotal = rollups.pendingHours(Instant.now());
            logger.info("Daten-Aufbereitung: voller Lauf gestartet, {} Stunde(n) ausstehend.", runHoursTotal);
            int hours = rollups.rollupCompletedHours(Instant.now(), Integer.MAX_VALUE, keepGoing, done -> runHoursDone = done);
            long deleted = rollups.applyRetention(Instant.now(), cfg.getRetentionDays(), RETENTION_DAYS_PER_RUN, keepGoing,
                    total -> runRowsDeleted = total);
            lastHoursRolled = hours;
            lastRowsDeleted = deleted;
            logger.info("Daten-Aufbereitung: {} Stunde(n) verdichtet, {} Rohzeilen geloescht.", hours, deleted);
            } catch (Exception e)
            {
            lastError = e.getMessage();
            logger.error("Daten-Aufbereitung fehlgeschlagen: {}", e.getMessage());
            } finally
            {
            lastRunEnd = Instant.now();
            running.set(false);
            }
        return true;
    }

    /** Statusdaten fuer die Einstellungs-Karte (liest ueber den Hauptkanal, kurze Abfragen). */
    public Map<String, Object> status()
    {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("running", running.get());
        s.put("lastRunStartEpoch", lastRunStart != null ? lastRunStart.getEpochSecond() : 0L);
        s.put("lastRunEndEpoch", lastRunEnd != null ? lastRunEnd.getEpochSecond() : 0L);
        s.put("lastHoursRolled", lastHoursRolled);
        s.put("lastRowsDeleted", lastRowsDeleted);
        s.put("lastError", lastError != null ? lastError : "");
        s.put("runHoursDone", runHoursDone);
        s.put("runHoursTotal", runHoursTotal);
        s.put("runRowsDeleted", runRowsDeleted);
        try
            {
            Instant wm = repo.getRollupWatermark();
            s.put("rollupUntilEpoch", wm != null ? wm.plus(1, ChronoUnit.HOURS).getEpochSecond() : 0L);
            s.put("rawRows", repo.countMeasurements());
            s.put("hourlyRows", repo.countHourlyRollups());
            s.put("pendingHours", new RollupService(repo).pendingHours(Instant.now()));
            } catch (Exception e)
            {
            s.put("statusError", e.getMessage());
            }
        return s;
    }

    private static boolean sleepMillis(long ms)
    {
        try
            {
            Thread.sleep(ms);
            return true;
            } catch (InterruptedException e)
            {
            Thread.currentThread().interrupt();
            return false;
            }
    }
}
