package at.mafue.signalreport.storage;

import at.mafue.signalreport.measurement.Measurement;
import org.junit.jupiter.api.*;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verdichtung zu Stundenwerten und Aufbewahrungsregel. Die Statistik wird ohne Datenbank
 * geprueft ({@link RollupService#computeRollups}), Wasserstandsmarke, Wiederholbarkeit und
 * Loeschregel mit einer echten H2-Datei unter ./data/test-rollup.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RollupServiceTest
{
    private static final String DB = "./data/test-rollup";
    private static final String HOST = "rolluphost";

    private H2MeasurementRepository repo;
    private RollupService service;

    @BeforeAll
    void setUp() throws SQLException
    {
        deleteFiles();
        repo = new H2MeasurementRepository(DB);
        service = new RollupService(repo);
    }

    @AfterAll
    void tearDown() throws SQLException
    {
        if (repo != null)
            {
            repo.close();
            }
        deleteFiles();
    }

    @BeforeEach
    void clearTables() throws SQLException
    {
        for (String base : new String[]{DB, DB + "-shadow"})
            {
            try (Connection c = DriverManager.getConnection("jdbc:h2:" + base + ";DB_CLOSE_ON_EXIT=FALSE", "sa", "");
                 Statement st = c.createStatement())
                {
                st.execute("DELETE FROM measurements");
                st.execute("DELETE FROM measurement_hourly");
                st.execute("DELETE FROM rollup_state");
                st.execute("DELETE FROM hosts");
                }
            }
    }

    private static void deleteFiles()
    {
        for (String suffix : new String[]{".mv.db", ".trace.db", "-shadow.mv.db", "-shadow.trace.db"})
            {
            new File(DB + suffix).delete();
            }
    }

    // ------------------------------------------------------------------------
    //  Statistik ohne Datenbank
    // ------------------------------------------------------------------------

    @Test
    void testComputeRollupsStatisticsPerTypeAndTarget()
    {
        Instant h = Instant.parse("2026-09-01T10:00:00Z");
        List<RawRow> rows = List.of(
                new RawRow(h.plusSeconds(10), "PING", "8.8.8.8", 10.0, true, false, HOST),
                new RawRow(h.plusSeconds(15), "DNS", "google.com", 7.0, true, false, HOST),
                new RawRow(h.plusSeconds(20), "PING", "8.8.8.8", 20.0, true, false, HOST),
                new RawRow(h.plusSeconds(30), "PING", "8.8.8.8", 5000.0, false, false, HOST), // Fehlschlag: keine Latenz
                new RawRow(h.plusSeconds(40), "PING", "8.8.8.8", 40.0, true, false, HOST),
                new RawRow(h.plusSeconds(50), "PING", "8.8.8.8", 30.0, true, false, HOST),
                new RawRow(h.plusSeconds(60), "PING", "8.8.8.8", 999.0, true, true, HOST));  // ausgenommen: zaehlt nirgends

        List<HourlyRollup> result = RollupService.computeRollups(h, rows);

        assertEquals(2, result.size(), "ein Stundenwert je (Typ, Ziel)");
        HourlyRollup ping = result.stream().filter(r -> "PING".equals(r.getType())).findFirst().orElseThrow();
        assertEquals(h, ping.getHourStart());
        assertEquals("8.8.8.8", ping.getTarget());
        assertEquals(HOST, ping.getHostHash());
        assertEquals(5, ping.getSampleCount(), "4 erfolgreiche + 1 Fehlschlag; ausgenommene nicht");
        assertEquals(4, ping.getOkCount());
        assertEquals(1, ping.getFailedCount());
        assertEquals(1, ping.getExcludedCount());
        assertEquals(10.0, ping.getMinMs(), 1e-9);
        assertEquals(40.0, ping.getMaxMs(), 1e-9);
        assertEquals(h.plusSeconds(40), ping.getMaxAt(), "Zeitpunkt des Maximums");
        assertEquals(25.0, ping.getAvgMs(), 1e-9);
        assertEquals(25.0, ping.getMedianMs(), 1e-9, "Median von 10,20,30,40");
        assertEquals(40.0, ping.getP95Ms(), 1e-9, "P95 bei 4 Werten = groesster Wert");
        // Jitter: Betrag der Spruenge in zeitlicher Reihenfolge 10->20->40->30 = (10+20+10)/3
        assertEquals(40.0 / 3.0, ping.getJitterMs(), 1e-9);

        HourlyRollup dns = result.stream().filter(r -> "DNS".equals(r.getType())).findFirst().orElseThrow();
        assertEquals(1, dns.getSampleCount());
        assertEquals(7.0, dns.getMedianMs(), 1e-9);
        assertEquals(7.0, dns.getP95Ms(), 1e-9);
        assertEquals(0.0, dns.getJitterMs(), 1e-9, "ein Wert hat keinen Jitter");
    }

    @Test
    void testComputeRollupsWithOnlyFailures()
    {
        Instant h = Instant.parse("2026-09-01T10:00:00Z");
        List<RawRow> rows = List.of(
                new RawRow(h.plusSeconds(10), "HTTP", "https://example.com", 5000.0, false, false, HOST),
                new RawRow(h.plusSeconds(20), "HTTP", "https://example.com", 5000.0, false, false, HOST));

        HourlyRollup http = RollupService.computeRollups(h, rows).get(0);
        assertEquals(2, http.getSampleCount());
        assertEquals(0, http.getOkCount());
        assertEquals(2, http.getFailedCount());
        assertEquals(0.0, http.getMinMs(), 1e-9);
        assertEquals(0.0, http.getAvgMs(), 1e-9);
        assertEquals(0.0, http.getMaxMs(), 1e-9);
        assertNull(http.getMaxAt());
    }

    @Test
    void testComputeRollupsEmpty()
    {
        assertTrue(RollupService.computeRollups(Instant.parse("2026-09-01T10:00:00Z"), List.of()).isEmpty());
    }

    // ------------------------------------------------------------------------
    //  Verdichtung mit Datenbank
    // ------------------------------------------------------------------------

    @Test
    void testRollupCompletedHoursStopsBeforeCurrentHour() throws SQLException
    {
        Instant now = Instant.now();
        Instant current = now.truncatedTo(ChronoUnit.HOURS);
        Instant h0 = current.minus(2, ChronoUnit.HOURS);
        Instant h1 = current.minus(1, ChronoUnit.HOURS);
        repo.saveAll(List.of(ping(h0.plusSeconds(5), 10.0, true), ping(h0.plusSeconds(15), 20.0, true)));
        repo.saveAll(List.of(ping(h1.plusSeconds(5), 30.0, true)));
        repo.saveAll(List.of(ping(current.plusSeconds(1), 40.0, true)));   // laufende Stunde

        assertNull(repo.getRollupWatermark(), "noch nichts verdichtet");
        assertEquals(h0, service.nextHourToRollup(), "Start bei der aeltesten Messung");

        int done = service.rollupCompletedHours(now, Integer.MAX_VALUE, () -> true);

        assertEquals(2, done, "nur abgeschlossene Stunden");
        assertEquals(h1, repo.getRollupWatermark());
        assertEquals(current, service.nextHourToRollup());
        List<HourlyRollup> rollups = repo.findHourlyRollups(null, h0, current.plus(1, ChronoUnit.HOURS));
        assertEquals(2, rollups.size(), "laufende Stunde darf keinen Stundenwert haben");
        assertEquals(2, rollups.get(0).getSampleCount());
        assertEquals(15.0, rollups.get(0).getAvgMs(), 1e-9);
        assertEquals(1, rollups.get(1).getSampleCount());
        assertEquals(2, repo.countHourlyRollups());

        // zweiter Aufruf: nichts mehr zu tun
        assertEquals(0, service.rollupCompletedHours(now, Integer.MAX_VALUE, () -> true));

        // Typfilter
        assertEquals(2, repo.findHourlyRollups("PING", h0, current).size());
        assertTrue(repo.findHourlyRollups("DNS", h0, current).isEmpty());
    }

    @Test
    void testRollupHourIsRepeatable() throws SQLException
    {
        Instant h0 = Instant.parse("2026-09-01T10:00:00Z");
        repo.saveAll(List.of(ping(h0.plusSeconds(5), 10.0, true), ping(h0.plusSeconds(15), 20.0, true)));

        service.rollupHour(h0);
        service.rollupHour(h0);

        List<HourlyRollup> rollups = repo.findHourlyRollups(null, h0, h0.plus(1, ChronoUnit.HOURS));
        assertEquals(1, rollups.size(), "MERGE: zweiter Lauf ueberschreibt statt zu verdoppeln");
        assertEquals(2, rollups.get(0).getSampleCount());
    }

    @Test
    void testMaxHoursLimitAdvancesWatermarkStepwise() throws SQLException
    {
        Instant now = Instant.now();
        Instant current = now.truncatedTo(ChronoUnit.HOURS);
        Instant h0 = current.minus(3, ChronoUnit.HOURS);
        repo.saveAll(List.of(ping(h0.plusSeconds(5), 10.0, true)));
        repo.saveAll(List.of(ping(h0.plus(2, ChronoUnit.HOURS).plusSeconds(5), 30.0, true)));

        assertEquals(1, service.rollupCompletedHours(now, 1, () -> true));
        assertEquals(h0, repo.getRollupWatermark());
        assertEquals(2, service.rollupCompletedHours(now, 10, () -> true), "h0+1 (leer) und h0+2");
        assertEquals(current.minus(1, ChronoUnit.HOURS), repo.getRollupWatermark());
        assertEquals(2, repo.countHourlyRollups(), "leere Stunden erzeugen keine Zeile");
    }

    @Test
    void testKeepGoingFalseStopsImmediately() throws SQLException
    {
        Instant now = Instant.now();
        repo.saveAll(List.of(ping(now.minus(5, ChronoUnit.HOURS), 10.0, true)));
        assertEquals(0, service.rollupCompletedHours(now, Integer.MAX_VALUE, () -> false));
        assertNull(repo.getRollupWatermark());
    }

    @Test
    void testHourlyAveragesMergeRollupsAndRawData() throws SQLException
    {
        Instant now = Instant.now();
        Instant current = now.truncatedTo(ChronoUnit.HOURS);
        Instant h0 = current.minus(2, ChronoUnit.HOURS);
        Instant h1 = current.minus(1, ChronoUnit.HOURS);
        repo.saveAll(List.of(ping(h0.plusSeconds(5), 10.0, true), ping(h0.plusSeconds(15), 20.0, true)));
        repo.saveAll(List.of(ping(h1.plusSeconds(5), 30.0, true)));
        repo.saveAll(List.of(ping(now.minusSeconds(1), 40.0, true)));     // laufende Stunde, nur roh
        service.rollupCompletedHours(now, Integer.MAX_VALUE, () -> true);

        List<HourlyAverage> averages = repo.calculateHourlyAverages("PING", 1);

        assertEquals(3, averages.size(), "zwei Stunden aus der Verdichtung + laufende Stunde aus den Rohdaten");
        int totalCount = averages.stream().mapToInt(HourlyAverage::getCount).sum();
        assertEquals(4, totalCount);

        Statistics stats = repo.calculateStatisticsFromRollups("PING", h0, current);
        assertEquals(20.0, stats.getAvgLatency(), 1e-6, "gewichtetes Mittel (10+20+30)/3");
        assertEquals(0.0, stats.getPacketLossPercent(), 1e-6);
    }

    // ------------------------------------------------------------------------
    //  Aufbewahrung
    // ------------------------------------------------------------------------

    @Test
    void testRetentionKeepsFindingsAndCondensesOkStretches() throws SQLException
    {
        // ganze Sekunden, damit die Zeitstempel nach dem Lesen aus H2 (Mikrosekunden) vergleichbar bleiben
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant a = now.minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).plus(10, ChronoUnit.HOURS);
        Measurement r1 = ping(a, 10.0, true);                      // erste Zeile ueberhaupt: bleibt
        Measurement r2 = ping(a.plusSeconds(10), 11.0, true);      // OK nach OK: loeschen
        Measurement r3 = ping(a.plusSeconds(20), 5000.0, false);   // Fund: bleibt
        Measurement r4 = ping(a.plusSeconds(30), 12.0, true);      // erste OK nach Fehlschlag (Ausfall-Ende): bleibt
        Measurement r5 = ping(a.plusSeconds(40), 13.0, true);      // OK nach OK: loeschen
        Measurement r6 = ping(a.plusSeconds(50), 14.0, true);      // wird ausgenommen markiert: bleibt
        Measurement r7 = ping(a.plusSeconds(60), 15.0, true);      // OK nach OK: loeschen
        Measurement maint = new Measurement("maintenance", 0.0, true, "MAINTENANCE", a.plusSeconds(25),
                "192.168.1.100", "fe80::1", "85.182.1.1", "::1", HOST);
        Measurement d1 = dns(a, 7.0);                              // erste DNS-Zeile: bleibt
        Measurement d2 = dns(a.plusSeconds(10), 8.0);              // OK nach OK: loeschen
        Measurement y1 = ping(now.minus(90, ChronoUnit.MINUTES), 20.0, true);   // juenger als die Frist: bleibt
        Measurement y2 = ping(now.minus(89, ChronoUnit.MINUTES), 21.0, true);
        repo.saveAll(List.of(r1, r2, r3, r4, r5, r6, r7, maint, d1, d2, y1, y2));
        markExcluded(r6.getTimestamp());
        assertEquals(12, repo.countMeasurements());

        // Frist 0 = nie loeschen
        assertEquals(0, service.applyRetention(now, 0, 400, () -> true));
        // noch nichts verdichtet -> nichts loeschen
        assertEquals(0, service.applyRetention(now, 7, 400, () -> true));
        assertEquals(12, repo.countMeasurements());

        service.rollupCompletedHours(now, Integer.MAX_VALUE, () -> true);
        long deleted = service.applyRetention(now, 7, 400, () -> true);

        assertEquals(4, deleted, "r2, r5, r7 und d2");
        assertEquals(8, repo.countMeasurements());
        Set<Instant> remaining = repo.findAll().stream().map(Measurement::getTimestamp).collect(Collectors.toSet());
        assertTrue(remaining.containsAll(List.of(r1.getTimestamp(), r3.getTimestamp(), r4.getTimestamp(),
                r6.getTimestamp(), maint.getTimestamp(), d1.getTimestamp(), y1.getTimestamp(), y2.getTimestamp())));
        assertFalse(remaining.contains(r2.getTimestamp()));
        assertFalse(remaining.contains(r5.getTimestamp()));
        assertFalse(remaining.contains(r7.getTimestamp()));
        assertFalse(remaining.contains(d2.getTimestamp()));

        Instant cutoff = now.minus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
        assertEquals(cutoff, repo.getRetentionDoneUntil(), "Fortschritt bis zur Frist gemerkt");
        assertEquals(0, service.applyRetention(now, 7, 400, () -> true), "zweiter Lauf hat nichts mehr zu tun");
        assertEquals(8, repo.countMeasurements());

        // Stundenwerte bleiben vollstaendig
        HourlyRollup hour = repo.findHourlyRollups("PING", a.truncatedTo(ChronoUnit.HOURS), a.plus(1, ChronoUnit.HOURS)).get(0);
        assertEquals(6, hour.getSampleCount(), "7 PING-Zeilen minus 1 ausgenommene");
        assertEquals(1, hour.getFailedCount());
        assertEquals(1, hour.getExcludedCount());
    }

    @Test
    void testRetentionOnlyTouchesRolledUpHours() throws SQLException
    {
        Instant now = Instant.now();
        Instant a = now.minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).plus(10, ChronoUnit.HOURS);
        Instant b = a.plus(2, ChronoUnit.HOURS);
        repo.saveAll(List.of(ping(a, 10.0, true), ping(a.plusSeconds(10), 11.0, true),
                ping(b, 12.0, true), ping(b.plusSeconds(10), 13.0, true)));

        // nur die erste Stunde verdichten
        assertEquals(1, service.rollupCompletedHours(now, 1, () -> true));
        long deleted = service.applyRetention(now, 7, 400, () -> true);
        assertEquals(1, deleted, "nur die zweite OK-Zeile der verdichteten Stunde");
        assertEquals(3, repo.countMeasurements(), "unverdichtete Stunde bleibt unangetastet");
        assertEquals(a.plus(1, ChronoUnit.HOURS), repo.getRetentionDoneUntil());

        // Rest verdichten, dann greift die Regel auch dort
        service.rollupCompletedHours(now, Integer.MAX_VALUE, () -> true);
        deleted = service.applyRetention(now, 7, 400, () -> true);
        assertEquals(2, deleted, "beide OK-Zeilen der zweiten Stunde folgen auf eine OK-Zeile");
        assertEquals(1, repo.countMeasurements(), "von einer durchgehend guten Strecke bleibt die erste Zeile");
    }

    @Test
    void testRetentionMaxDaysLimitsOneRun() throws SQLException
    {
        Instant now = Instant.now();
        Instant a = now.minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).plus(10, ChronoUnit.HOURS);
        repo.saveAll(List.of(ping(a, 10.0, true), ping(a.plusSeconds(10), 11.0, true)));
        repo.saveAll(List.of(ping(a.plus(1, ChronoUnit.DAYS), 10.0, true), ping(a.plus(1, ChronoUnit.DAYS).plusSeconds(10), 11.0, true)));
        service.rollupCompletedHours(now, Integer.MAX_VALUE, () -> true);

        assertEquals(1, service.applyRetention(now, 7, 1, () -> true), "nur der erste Tag: zweite OK-Zeile von Tag 1");
        assertEquals(a.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS), repo.getRetentionDoneUntil());
        assertEquals(3, repo.countMeasurements(), "Tag 2 noch unangetastet");
        // naechster Lauf: Tag 2. Die gute Strecke setzt sich ueber die Tagesgrenze fort (Vortag als Kontext),
        // also sind beide OK-Zeilen von Tag 2 Folgezeilen und werden geloescht.
        assertEquals(2, service.applyRetention(now, 7, 1, () -> true), "naechster Lauf: zweiter Tag");
        assertEquals(1, repo.countMeasurements(), "von der durchgehend guten Strecke bleibt die erste Zeile");
        assertEquals(a.truncatedTo(ChronoUnit.DAYS).plus(2, ChronoUnit.DAYS), repo.getRetentionDoneUntil());
    }

    // ------------------------------------------------------------------------

    private static Measurement ping(Instant ts, double latency, boolean ok)
    {
        return new Measurement("8.8.8.8", latency, ok, "PING", ts, "192.168.1.100", "fe80::1", "85.182.1.1", "::1", HOST);
    }

    private static Measurement dns(Instant ts, double latency)
    {
        return new Measurement("google.com", latency, true, "DNS", ts, "192.168.1.100", "fe80::1", "85.182.1.1", "::1", HOST);
    }

    private static void markExcluded(Instant ts) throws SQLException
    {
        for (String base : new String[]{DB, DB + "-shadow"})
            {
            try (Connection c = DriverManager.getConnection("jdbc:h2:" + base + ";DB_CLOSE_ON_EXIT=FALSE", "sa", "");
                 PreparedStatement ps = c.prepareStatement("UPDATE measurements SET excluded = TRUE WHERE timestamp = ?"))
                {
                ps.setTimestamp(1, Timestamp.from(ts));
                assertEquals(1, ps.executeUpdate());
                }
            }
    }
}
