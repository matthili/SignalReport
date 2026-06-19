package at.mafue.signalreport;

import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.config.ServiceReachabilityConfig;
import at.mafue.signalreport.measurement.Measurement;
import at.mafue.signalreport.network.ServiceReachabilityProbe;
import at.mafue.signalreport.network.ServiceReachabilityResult;
import at.mafue.signalreport.storage.H2MeasurementRepository;
import at.mafue.signalreport.storage.ServiceCheck;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * Steuert die periodische Dienst-Erreichbarkeitspruefung in einem eigenen,
 * langsamen Takt (Standard alle 6 Stunden), getrennt vom 30-Sekunden-Messtakt.
 * <p>
 * <b>Leitungs-Gate:</b> Vor jedem Lauf wird der laufende 30-Sekunden-Monitor
 * befragt -- gibt es im Frische-Fenster keine erfolgreiche PING-/HTTP-Messung,
 * gilt die Leitung als nicht bestaetigt; der Lauf wird uebersprungen und ein
 * einzelner {@code LINE_DOWN}-Marker geschrieben (analog zum Wartungs-Marker).
 * So wird ein Leitungsausfall nie faelschlich als Dienst-Sperre verbucht.
 * <p>
 * Der Lauf laesst sich auch manuell ausloesen ({@link #triggerManualRun()}, fuer
 * den "Jetzt pruefen"-Button) -- mit einer Abkuehlphase von 5 Minuten.
 */
public class ServiceReachabilityScheduler
{
    private static final Logger logger = LoggerFactory.getLogger(ServiceReachabilityScheduler.class);

    private static final long INITIAL_DELAY_MS = 20_000L;          // dem 30s-Monitor Zeit fuer erste Daten geben
    private static final long DISABLED_POLL_MS = 60_000L;          // im Aus-Zustand schnell auf Aktivierung reagieren
    private static final long GATE_FRESHNESS_SECONDS = 300L;       // 5 min Frische-Fenster fuer das Leitungs-Gate
    private static final int MANUAL_COOLDOWN_MINUTES = 5;
    private static final String LINE_DOWN_SERVICE_ID = "*";        // globaler Marker, keinem Dienst zugeordnet

    private final H2MeasurementRepository repo;
    private final ServiceReachabilityProbe probe;
    private volatile long lastManualRunMs = 0L;

    public ServiceReachabilityScheduler(H2MeasurementRepository repo)
    {
        this.repo = repo;
        this.probe = new ServiceReachabilityProbe();
    }

    /** Startet den periodischen Hintergrund-Thread (Daemon). */
    public void start()
    {
        Thread t = new Thread(this::scheduledLoop, "signalreport-reachability");
        t.setDaemon(true);
        t.start();
    }

    private void scheduledLoop()
    {
        if (!sleepMillis(INITIAL_DELAY_MS))
            {
            return;
            }
        while (true)
            {
            long nextSleep = DISABLED_POLL_MS;
            try
                {
                ServiceReachabilityConfig cfg = Config.getInstance().getServiceReachability();
                if (cfg.isEnabled())
                    {
                    runOnce(cfg);
                    nextSleep = cfg.getIntervalMinutes() * 60_000L;
                    }
                } catch (Exception e)
                {
                logger.error("Fehler im Dienst-Erreichbarkeits-Lauf: {}", e.getMessage());
                }
            if (!sleepMillis(nextSleep))
                {
                return;
                }
            }
    }

    /**
     * Loest einen sofortigen Lauf aus (im Hintergrund), sofern die Abkuehlphase
     * abgelaufen ist. Liefert die verbleibende Cooldown-Zeit in Sekunden
     * (0 = Lauf wurde gestartet). Fuer den "Jetzt pruefen"-Button.
     */
    public synchronized long triggerManualRun()
    {
        long now = System.currentTimeMillis();
        long cooldownMs = MANUAL_COOLDOWN_MINUTES * 60_000L;
        if (lastManualRunMs > 0 && (now - lastManualRunMs) < cooldownMs)
            {
            return (cooldownMs - (now - lastManualRunMs)) / 1000L;
            }
        lastManualRunMs = now;
        Thread t = new Thread(() ->
        {
        try
            {
            runOnce(Config.getInstance().getServiceReachability());
            } catch (Exception e)
            {
            logger.error("Manueller Erreichbarkeits-Lauf fehlgeschlagen: {}", e.getMessage());
            }
        }, "signalreport-reachability-manual");
        t.setDaemon(true);
        t.start();
        return 0L;
    }

    /** Ein vollstaendiger Lauf: Leitungs-Gate, dann alle aktiven Dienste proben und speichern. */
    public void runOnce(ServiceReachabilityConfig cfg)
    {
        if (!isInternetUpNow())
            {
            logger.info("Dienst-Erreichbarkeit: Leitung aktuell nicht bestaetigt -> Lauf uebersprungen (LINE_DOWN).");
            saveLineDownMarker();
            return;
            }

        List<ServiceReachabilityResult> results = probe.probeAll(cfg);
        Instant now = Instant.now();
        int saved = 0;
        for (ServiceReachabilityResult r : results)
            {
            try
                {
                repo.saveServiceCheck(new ServiceCheck(now, r.getServiceId(), r.getVerdict().name(),
                        r.getMethod(), r.getHttpStatus(), r.getResolvedIp(), r.getLatencyMs()));
                saved++;
                } catch (SQLException e)
                {
                logger.error("Service-Check ({}) nicht gespeichert: {}", r.getServiceId(), e.getMessage());
                }
            }
        logger.info("Dienst-Erreichbarkeit: {} von {} Diensten geprueft und gespeichert.", saved, results.size());
    }

    /**
     * Leitungs-Gate: gilt als "up", wenn der 30-Sekunden-Monitor im Frische-Fenster
     * mindestens eine erfolgreiche PING- oder HTTP-Messung hinterlassen hat.
     */
    private boolean isInternetUpNow()
    {
        try
            {
            Instant since = Instant.now().minusSeconds(GATE_FRESHNESS_SECONDS);
            return internetConfirmedUp(repo.findSince(since));
            } catch (Exception e)
            {
            logger.warn("Leitungs-Gate-Pruefung fehlgeschlagen: {}", e.getMessage());
            return false;
            }
    }

    /**
     * Reine Entscheidung des Leitungs-Gates (testbar, ohne DB): die Leitung gilt als
     * bestaetigt, sobald eine erfolgreiche PING- oder HTTP-Messung vorliegt. Erfolgreiche
     * Gateway-, Wartungs- oder DNS-Eintraege zaehlen bewusst nicht als Internet-Beleg.
     */
    static boolean internetConfirmedUp(List<Measurement> recent)
    {
        for (Measurement m : recent)
            {
            if (m.isSuccess() && ("PING".equals(m.getType()) || "HTTP".equals(m.getType())))
                {
                return true;
                }
            }
        return false;
    }

    private void saveLineDownMarker()
    {
        try
            {
            repo.saveServiceCheck(new ServiceCheck(Instant.now(), LINE_DOWN_SERVICE_ID, "LINE_DOWN",
                    "Internet-Leitung beim geplanten Lauf nicht bestaetigt", -1, null, 0.0));
            } catch (SQLException e)
            {
            logger.error("LINE_DOWN-Marker nicht gespeichert: {}", e.getMessage());
            }
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
