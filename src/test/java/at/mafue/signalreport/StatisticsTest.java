package at.mafue.signalreport;

import org.junit.jupiter.api.*;
import java.sql.SQLException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer die Statistik-Berechnung.
 * Im Gegensatz zu reinen Formel-Tests wird hier die tatsaechliche
 * Repository-Berechnung (SQL + Java) getestet.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatisticsTest {

    private H2MeasurementRepository repo;
    private static final String TEST_DB_PATH = "./data/test-statistics";

    @BeforeAll
    void setUp() throws SQLException {
        repo = new H2MeasurementRepository(TEST_DB_PATH);
    }

    @AfterAll
    void tearDown() throws SQLException {
        if (repo != null) {
            repo.close();
        }
        new java.io.File(TEST_DB_PATH + ".mv.db").delete();
        new java.io.File(TEST_DB_PATH + ".trace.db").delete();
    }

    @BeforeEach
    void clearData() throws SQLException {
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                "jdbc:h2:" + TEST_DB_PATH + ";DB_CLOSE_ON_EXIT=FALSE", "sa", "")) {
            try (java.sql.Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM measurements");
            }
        }
    }

    @Test
    void testStatisticsObjectCreation() {
        // Direkter Konstruktor-Test: Alle Felder korrekt gespeichert?
        H2MeasurementRepository.Statistics stats =
            new H2MeasurementRepository.Statistics(23.5, 45.2, 89.7, 0.5, 3.2);

        assertEquals(23.5, stats.getAvgLatency(), 0.01);
        assertEquals(45.2, stats.getP95Latency(), 0.01);
        assertEquals(89.7, stats.getMaxLatency(), 0.01);
        assertEquals(0.5, stats.getPacketLossPercent(), 0.01);
        assertEquals(3.2, stats.getJitter(), 0.01);
    }

    @Test
    void testP95FromRepository() throws SQLException {
        // 20 Messungen mit steigender Latenz: 10, 20, 30, ..., 200
        for (int i = 1; i <= 20; i++) {
            repo.save(new Measurement("8.8.8.8", i * 10.0, true, "PING"));
            sleepBriefly();
        }

        H2MeasurementRepository.Statistics stats = repo.calculateStatistics("PING", 1);

        // 95th Percentile bei 20 Werten = 19. Wert = 190.0
        assertTrue(stats.getP95Latency() >= 180.0,
            "95th Percentile sollte >= 180ms sein, war: " + stats.getP95Latency());
        assertTrue(stats.getP95Latency() <= 200.0,
            "95th Percentile sollte <= 200ms sein, war: " + stats.getP95Latency());
    }

    @Test
    void testJitterFromRepository() throws SQLException {
        // Konstante Latenz: Jitter muss nahe 0 sein
        for (int i = 0; i < 10; i++) {
            repo.save(new Measurement("8.8.8.8", 50.0, true, "PING"));
            sleepBriefly();
        }

        H2MeasurementRepository.Statistics stats = repo.calculateStatistics("PING", 1);
        assertEquals(0.0, stats.getJitter(), 1.0,
            "Jitter bei konstanter Latenz muss nahe 0 sein, war: " + stats.getJitter());
    }

    @Test
    void testJitterWithVaryingLatency() throws SQLException {
        // Stark schwankende Latenz: Jitter muss deutlich > 0 sein
        double[] latencies = {10.0, 100.0, 10.0, 100.0, 10.0, 100.0};
        for (double lat : latencies) {
            repo.save(new Measurement("8.8.8.8", lat, true, "PING"));
            sleepBriefly();
        }

        H2MeasurementRepository.Statistics stats = repo.calculateStatistics("PING", 1);
        assertTrue(stats.getJitter() > 50.0,
            "Jitter bei stark schwankender Latenz muss > 50ms sein, war: " + stats.getJitter());
    }

    @Test
    void testPacketLossFromRepository() throws SQLException {
        // 8 erfolgreiche + 2 fehlgeschlagene = 20% Paketverlust
        for (int i = 0; i < 8; i++) {
            repo.save(new Measurement("8.8.8.8", 25.0, true, "PING"));
            sleepBriefly();
        }
        for (int i = 0; i < 2; i++) {
            repo.save(new Measurement("8.8.8.8", 5000.0, false, "PING"));
            sleepBriefly();
        }

        H2MeasurementRepository.Statistics stats = repo.calculateStatistics("PING", 1);
        assertEquals(20.0, stats.getPacketLossPercent(), 1.0,
            "Paketverlust sollte ~20% sein");
    }

    @Test
    void testStatisticsWithEmptyDatabase() throws SQLException {
        // Leere Datenbank: Alle Werte muessen 0 sein, kein Fehler
        H2MeasurementRepository.Statistics stats = repo.calculateStatistics("PING", 1);

        assertEquals(0.0, stats.getAvgLatency(), 0.01, "Durchschnitt bei 0 Messungen muss 0 sein");
        assertEquals(0.0, stats.getP95Latency(), 0.01, "P95 bei 0 Messungen muss 0 sein");
        assertEquals(0.0, stats.getPacketLossPercent(), 0.01, "Paketverlust bei 0 Messungen muss 0 sein");
    }

    @Test
    void testStatisticsFiltersByType() throws SQLException {
        // PING-Messungen mit hoher Latenz
        for (int i = 0; i < 5; i++) {
            repo.save(new Measurement("8.8.8.8", 100.0, true, "PING"));
            sleepBriefly();
        }
        // DNS-Messungen mit niedriger Latenz
        for (int i = 0; i < 5; i++) {
            repo.save(new Measurement("google.com", 5.0, true, "DNS"));
            sleepBriefly();
        }

        H2MeasurementRepository.Statistics pingStats = repo.calculateStatistics("PING", 1);
        H2MeasurementRepository.Statistics dnsStats = repo.calculateStatistics("DNS", 1);

        assertTrue(pingStats.getAvgLatency() > 90.0,
            "PING-Durchschnitt sollte ~100ms sein");
        assertTrue(dnsStats.getAvgLatency() < 10.0,
            "DNS-Durchschnitt sollte ~5ms sein");
    }

    @Test
    void testMaxLatency() throws SQLException {
        repo.save(new Measurement("8.8.8.8", 10.0, true, "PING"));
        sleepBriefly();
        repo.save(new Measurement("8.8.8.8", 500.0, true, "PING"));
        sleepBriefly();
        repo.save(new Measurement("8.8.8.8", 25.0, true, "PING"));
        sleepBriefly();

        H2MeasurementRepository.Statistics stats = repo.calculateStatistics("PING", 1);
        assertEquals(500.0, stats.getMaxLatency(), 1.0,
            "Maximum muss die hoechste Latenz sein");
    }

    private void sleepBriefly() {
        try { Thread.sleep(5); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
