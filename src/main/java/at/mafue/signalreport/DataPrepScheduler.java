package at.mafue.signalreport;

import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.config.DataPrepConfig;
import at.mafue.signalreport.storage.H2MeasurementRepository;
import at.mafue.signalreport.storage.RollupService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

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
 * Der manuelle Lauf hat eine Abkuehlphase von 5 Minuten (wie "Jetzt pruefen").
 */
public class DataPrepScheduler
{
    private static final Logger logger = LoggerFactory.getLogger(DataPrepScheduler.class);

    static final int HOURS_PER_TICK_OUTSIDE_WINDOW = 2;
    static final int RETENTION_DAYS_PER_RUN = 400;
    private static final long INITIAL_DELAY_MS = 30_000L;
    private static final long TICK_MS = 60_000L;
    private static final int MANUAL_COOLDOWN_MINUTES = 5;
    private static final long MANUAL_RUN_BUDGET_SECONDS = 2 * 3600L;

    private final H2MeasurementRepository repo;
    private final RollupService rollups;

    private volatile boolean stopRequested = false;
    private volatile boolean running = false;
    private volatile Instant lastRunStart;
    private volatile Instant lastRunEnd;
    private volatile int lastHoursRolled = -1;
    private volatile long lastRowsDeleted = -1;
    private volatile String lastError;
    private volatile LocalDate lastWindowRunDay;
    private volatile long lastManualRunMs = 0L;

    public DataPrepScheduler(H2MeasurementRepository repo)
    {
        this.repo = repo;
        this.rollups = new RollupService(repo);
    }

    public void start()
    {
        Thread t = new Thread(this::loop, "signalreport-dataprep");
        t.setDaemon(true);
        t.start();
    }

    /** Beendet laufende Arbeit an der naechsten Stunden-/Tagesgrenze und startet keine neue. */
    public void stop()
    {
        stopRequested = true;
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
            lastWindowRunDay = today;
            runFull(cfg, () -> !stopRequested && cfg.isWindowNow(Instant.now().atZone(zone).toLocalTime(), config.getMaintenanceWindow()));
            } else if (!running)
            {
            rollups.rollupCompletedHours(now, HOURS_PER_TICK_OUTSIDE_WINDOW, () -> !stopRequested);
            }
    }

    /** Reine Entscheidung (testbar): voller Lauf genau einmal pro Tag, sobald das Fenster offen ist. */
    static boolean shouldRunFull(boolean inWindow, LocalDate today, LocalDate lastRunDay)
    {
        return inWindow && !today.equals(lastRunDay);
    }

    /**
     * Startet den vollen Lauf sofort im Hintergrund (Button "Jetzt ausfuehren"), sofern die
     * Abkuehlphase abgelaufen ist. Liefert die verbleibende Abkuehlzeit in Sekunden (0 = gestartet).
     */
    public synchronized long triggerManualRun()
    {
        long now = System.currentTimeMillis();
        long cooldownMs = MANUAL_COOLDOWN_MINUTES * 60_000L;
        if (lastManualRunMs > 0 && (now - lastManualRunMs) < cooldownMs)
            {
            return (cooldownMs - (now - lastManualRunMs)) / 1000L;
            }
        if (running)
            {
            return 1L;
            }
        lastManualRunMs = now;
        Instant deadline = Instant.now().plusSeconds(MANUAL_RUN_BUDGET_SECONDS);
        Thread t = new Thread(() ->
        {
        Config config = Config.getInstance();
        DataPrepConfig cfg = config != null ? config.getDataPrep() : new DataPrepConfig();
        runFull(cfg, () -> !stopRequested && Instant.now().isBefore(deadline));
        }, "signalreport-dataprep-manual");
        t.setDaemon(true);
        t.start();
        return 0L;
    }

    /** Voller Lauf: Verdichtungs-Rueckstand komplett, dann Aufbewahrung; beides bricht ab, wenn keepGoing false liefert. */
    synchronized void runFull(DataPrepConfig cfg, java.util.function.BooleanSupplier keepGoing)
    {
        running = true;
        lastRunStart = Instant.now();
        lastError = null;
        try
            {
            int hours = rollups.rollupCompletedHours(Instant.now(), Integer.MAX_VALUE, keepGoing);
            long deleted = rollups.applyRetention(Instant.now(), cfg.getRetentionDays(), RETENTION_DAYS_PER_RUN, keepGoing);
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
            running = false;
            }
    }

    /** Statusdaten fuer die Einstellungs-Karte. */
    public Map<String, Object> status()
    {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("running", running);
        s.put("lastRunStartEpoch", lastRunStart != null ? lastRunStart.getEpochSecond() : 0L);
        s.put("lastRunEndEpoch", lastRunEnd != null ? lastRunEnd.getEpochSecond() : 0L);
        s.put("lastHoursRolled", lastHoursRolled);
        s.put("lastRowsDeleted", lastRowsDeleted);
        s.put("lastError", lastError != null ? lastError : "");
        try
            {
            Instant wm = repo.getRollupWatermark();
            s.put("rollupUntilEpoch", wm != null ? wm.plus(1, ChronoUnit.HOURS).getEpochSecond() : 0L);
            s.put("rawRows", repo.countMeasurements());
            s.put("hourlyRows", repo.countHourlyRollups());
            Instant next = rollups.nextHourToRollup();
            long pending = next == null ? 0 : Math.max(0, ChronoUnit.HOURS.between(next, Instant.now().truncatedTo(ChronoUnit.HOURS)));
            s.put("pendingHours", pending);
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
