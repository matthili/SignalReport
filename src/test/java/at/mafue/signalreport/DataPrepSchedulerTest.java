package at.mafue.signalreport;

import at.mafue.signalreport.config.DataPrepConfig;
import at.mafue.signalreport.measurement.Measurement;
import at.mafue.signalreport.storage.H2MeasurementRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Steuerung der Daten-Aufbereitung: Entscheidung "voller Lauf einmal pro Tag im Fenster",
 * der volle Lauf selbst (Verdichtung + Statusdaten) und die Abkuehlphase des manuellen Starts.
 * Arbeitet mit einer echten H2-Datei unter ./data/test-dataprep.
 */
class DataPrepSchedulerTest
{
    private static final String DB = "./data/test-dataprep";
    private H2MeasurementRepository repo;

    @BeforeEach
    void openRepo() throws SQLException
    {
        deleteFiles();
        repo = new H2MeasurementRepository(DB);
    }

    @AfterEach
    void closeRepo() throws SQLException
    {
        if (repo != null)
            {
            repo.close();
            }
        deleteFiles();
    }

    private static void deleteFiles()
    {
        for (String suffix : new String[]{".mv.db", ".trace.db", "-shadow.mv.db", "-shadow.trace.db"})
            {
            new File(DB + suffix).delete();
            }
    }

    private static Measurement m(Instant ts, double latency)
    {
        return new Measurement("8.8.8.8", latency, true, "PING", ts,
                "192.168.1.100", "fe80::1", "85.182.1.1", "::1", "dataprephost");
    }

    @Test
    void testShouldRunFullOncePerDayInsideWindow()
    {
        LocalDate today = LocalDate.of(2026, 10, 4);
        LocalDate yesterday = today.minusDays(1);

        assertTrue(DataPrepScheduler.shouldRunFull(true, today, null), "erster Lauf im Fenster");
        assertTrue(DataPrepScheduler.shouldRunFull(true, today, yesterday), "neuer Tag -> wieder laufen");
        assertFalse(DataPrepScheduler.shouldRunFull(true, today, today), "heute schon gelaufen");
        assertFalse(DataPrepScheduler.shouldRunFull(false, today, null), "ausserhalb des Fensters nie");
        assertFalse(DataPrepScheduler.shouldRunFull(false, today, yesterday));
    }

    @Test
    void testOutsideWindowOnlyFewHoursPerTick()
    {
        // Schutz des Messbetriebs: ausserhalb des Fensters hoechstens 2 Stunden pro Minute
        assertEquals(2, DataPrepScheduler.HOURS_PER_TICK_OUTSIDE_WINDOW);
    }

    @Test
    void testRunFullRollsUpBacklogAndReportsStatus() throws Exception
    {
        Instant now = Instant.now();
        repo.saveAll(List.of(m(now.minus(3, ChronoUnit.HOURS), 10.0)));
        repo.saveAll(List.of(m(now.minus(2, ChronoUnit.HOURS), 20.0)));

        DataPrepScheduler scheduler = new DataPrepScheduler(repo);
        DataPrepConfig cfg = new DataPrepConfig();
        cfg.setRetentionDays(0); // nie loeschen

        scheduler.runFull(cfg, () -> true);

        Map<String, Object> status = scheduler.status();
        assertEquals(Boolean.FALSE, status.get("running"));
        assertEquals("", status.get("lastError"));
        // abgeschlossene Stunden ab der aeltesten Messung: now-3h, now-2h, now-1h (die laufende nicht)
        assertEquals(3, ((Number) status.get("lastHoursRolled")).intValue());
        assertEquals(0L, ((Number) status.get("lastRowsDeleted")).longValue());
        assertEquals(2L, ((Number) status.get("rawRows")).longValue());
        assertEquals(2L, ((Number) status.get("hourlyRows")).longValue(), "zwei Stunden mit Daten -> zwei Stundenwerte");
        assertEquals(0L, ((Number) status.get("pendingHours")).longValue());
        assertTrue(((Number) status.get("lastRunStartEpoch")).longValue() > 0);
        assertTrue(((Number) status.get("lastRunEndEpoch")).longValue() >= ((Number) status.get("lastRunStartEpoch")).longValue());
        assertEquals(now.truncatedTo(ChronoUnit.HOURS).getEpochSecond(),
                ((Number) status.get("rollupUntilEpoch")).longValue(), "Stundenwerte reichen bis zum Beginn der laufenden Stunde");
        assertEquals(now.truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS), repo.getRollupWatermark());
    }

    @Test
    void testManualRunStartsOnceAndThenCoolsDown() throws Exception
    {
        DataPrepScheduler scheduler = new DataPrepScheduler(repo);

        assertEquals(0L, scheduler.triggerManualRun(), "erster Start muss sofort moeglich sein");
        long remaining = scheduler.triggerManualRun();
        assertTrue(remaining > 0 && remaining <= 300, "zweiter Start innerhalb von 5 Minuten wird abgewiesen, Rest: " + remaining);

        // auf das Ende des Hintergrund-Laufs warten (leere DB -> sofort fertig)
        long deadline = System.currentTimeMillis() + 10_000;
        Map<String, Object> status = scheduler.status();
        while (((Number) status.get("lastRunEndEpoch")).longValue() == 0L && System.currentTimeMillis() < deadline)
            {
            Thread.sleep(50);
            status = scheduler.status();
            }
        assertTrue(((Number) status.get("lastRunEndEpoch")).longValue() > 0L, "manueller Lauf muss abgeschlossen sein");
        assertEquals(Boolean.FALSE, status.get("running"));
        assertEquals("", status.get("lastError"));
        assertEquals(0, ((Number) status.get("lastHoursRolled")).intValue(), "ohne Messungen gibt es nichts zu verdichten");
    }
}
