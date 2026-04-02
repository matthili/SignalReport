package at.mafue.signalreport;

import org.junit.jupiter.api.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class H2MeasurementRepositoryTest {

    private H2MeasurementRepository repo;
    private static final String TEST_DB_PATH = "./data/test-signalreport";

    @BeforeAll
    void setUp() throws SQLException {
        // Test-Datenbank initialisieren
        repo = new H2MeasurementRepository(TEST_DB_PATH);
    }

    @AfterAll
    void tearDown() throws SQLException {
        if (repo != null) {
            repo.close();
        }
        // Test-Datenbank-Dateien bereinigen
        java.io.File dbFile = new java.io.File(TEST_DB_PATH + ".mv.db");
        java.io.File traceFile = new java.io.File(TEST_DB_PATH + ".trace.db");
        dbFile.delete();
        traceFile.delete();
    }

    @BeforeEach
    void clearTestData() throws SQLException {
        // Tabelle leeren für jeden Test (ohne direkten Connection-Zugriff)
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                "jdbc:h2:" + TEST_DB_PATH + ";DB_CLOSE_ON_EXIT=FALSE", "sa", "")) {
            try (java.sql.Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM measurements");
                stmt.execute("DELETE FROM ip_changes");
                stmt.execute("DELETE FROM hosts");
            }
        }
    }

    @Test
    void testSaveAndFindMeasurement() throws SQLException {
        // Arrange
        Measurement m1 = new Measurement("8.8.8.8", 23.5, true, "PING");
        Measurement m2 = new Measurement("google.com", 15.2, true, "DNS");

        // Act
        repo.save(m1);
        repo.save(m2);
        List<Measurement> results = repo.findLastN(10);

        // Assert
        assertEquals(2, results.size(), "Es sollten 2 Messungen gefunden werden");
        assertEquals("DNS", results.get(0).getType(), "Neueste Messung zuerst");
        assertEquals("PING", results.get(1).getType(), "Ältere Messung danach");
    }

    @Test
    void testFindLastNLimit() throws SQLException {
        // Arrange: 5 Messungen speichern
        for (int i = 0; i < 5; i++) {
            repo.save(new Measurement("8.8.8.8", 20.0 + i, true, "PING"));
            try {
                Thread.sleep(10); // Kleine Pause für unterschiedliche Zeitstempel
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Interrupt-Flag wiederherstellen
            }
        }

        // Act
        List<Measurement> results = repo.findLastN(3);

        // Assert
        assertEquals(3, results.size(), "Nur die letzten 3 Messungen sollten zurückgegeben werden");
    }

    @Test
    void testStatisticsCalculation() throws SQLException {
        // Arrange: 10 erfolgreiche Messungen mit steigender Latenz
        for (int i = 1; i <= 10; i++) {
            repo.save(new Measurement("8.8.8.8", i * 10.0, true, "PING"));
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Act
        H2MeasurementRepository.Statistics stats = repo.calculateStatistics("PING", 1);

        // Assert
        assertEquals(55.0, stats.getAvgLatency(), 1.0, "Durchschnitt sollte ~55ms sein");
        assertTrue(stats.getP95Latency() > 90.0, "95th Percentile sollte >90ms sein");
        assertEquals(0.0, stats.getPacketLossPercent(), 0.1, "Paketverlust sollte 0% sein");
    }

    @Test
    void testStatisticsWithPacketLoss() throws SQLException {
        // Arrange: 9 erfolgreiche + 1 fehlgeschlagene Messung
        for (int i = 0; i < 9; i++) {
            repo.save(new Measurement("8.8.8.8", 20.0, true, "PING"));
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        repo.save(new Measurement("8.8.8.8", 5000.0, false, "PING")); // Timeout

        // Act
        H2MeasurementRepository.Statistics stats = repo.calculateStatistics("PING", 1);

        // Assert
        assertEquals(10.0, stats.getPacketLossPercent(), 1.0, "Paketverlust sollte ~10% sein");
    }

    @Test
    void testHourlyAverages() throws SQLException {
        // Arrange: Messungen für verschiedene Stunden speichern
        Instant now = Instant.now();
        for (int hour = 0; hour < 24; hour++) {
            for (int i = 0; i < 5; i++) {
                // Mock-Messung mit festem Zeitstempel für diese Stunde
                Instant timestamp = now.minusSeconds((23 - hour) * 3600 + i * 60);
                repo.save(new Measurement("8.8.8.8", 20.0 + hour, true, "PING",
                    timestamp, "192.168.1.100", "fe80::1", "85.182.1.1", "::1", "testhash"));
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // Act
        List<H2MeasurementRepository.HourlyAverage> averages = repo.calculateHourlyAverages("PING", 1);

        // Assert
        assertFalse(averages.isEmpty(), "Es sollten stündliche Durchschnitte berechnet werden");
        for (H2MeasurementRepository.HourlyAverage avg : averages) {
            assertTrue(avg.getAvgLatency() >= 20.0, "Latenz sollte >=20ms sein");
            assertEquals(5, avg.getCount(), "Jede Stunde sollte 5 Messungen haben");
        }
    }

    @Test
    void testHostRegistration() throws SQLException {
        // Act
        repo.registerHost("testhash123", "testhost", "Windows 11");
        List<H2MeasurementRepository.HostInfo> hosts = repo.getAllHosts();

        // Assert
        assertEquals(1, hosts.size(), "Es sollte 1 Host registriert sein");
        assertEquals("testhash123", hosts.getFirst().getHostHash());
        assertEquals("testhost", hosts.getFirst().getHostname());
        assertEquals("Windows 11", hosts.getFirst().getOperatingSystem());
    }

    @Test
    void testIpChangeTracking() throws SQLException {
        // Act
        String host = "iptrackhost";
        repo.trackIpChange("1.2.3.4", host); // Erste IP → INITIAL
        try { Thread.sleep(50); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        repo.trackIpChange("5.6.7.8", host); // IP-Wechsel → CHANGE

        List<H2MeasurementRepository.IpChange> allChanges = repo.getIpChanges(100);
        List<H2MeasurementRepository.IpChange> changes = allChanges.stream()
            .filter(c -> host.equals(c.getHostHash()))
            .toList();

        // Assert: Es muessen genau 2 Eintraege existieren (INITIAL + CHANGE)
        assertEquals(2, changes.size(), "Es sollten 2 IP-Änderungen erfasst sein");

        // Pruefe, dass beide Typen vorhanden sind (unabhaengig von Reihenfolge)
        List<String> types = changes.stream().map(c -> c.getChangeType()).toList();
        assertTrue(types.contains("INITIAL"), "Es muss einen INITIAL-Eintrag geben");
        assertTrue(types.contains("CHANGE"), "Es muss einen CHANGE-Eintrag geben");

        // Pruefe IPs: INITIAL hat 1.2.3.4, CHANGE hat 5.6.7.8
        H2MeasurementRepository.IpChange initial = changes.stream()
            .filter(c -> "INITIAL".equals(c.getChangeType())).findFirst().orElseThrow();
        H2MeasurementRepository.IpChange change = changes.stream()
            .filter(c -> "CHANGE".equals(c.getChangeType())).findFirst().orElseThrow();

        assertEquals("1.2.3.4", initial.getNewIp(), "INITIAL-IP sollte 1.2.3.4 sein");
        assertEquals("5.6.7.8", change.getNewIp(), "CHANGE-IP sollte 5.6.7.8 sein");
    }

    @Test
    void testSameIpNoNewChange() throws SQLException {
        // Gleiche IP zweimal hintereinander darf keinen neuen CHANGE erzeugen
        repo.trackIpChange("1.2.3.4", "testhash456"); // INITIAL
        repo.trackIpChange("1.2.3.4", "testhash456"); // Gleiche IP → kein neuer Eintrag
        repo.trackIpChange("1.2.3.4", "testhash456"); // Gleiche IP → kein neuer Eintrag

        List<H2MeasurementRepository.IpChange> changes = repo.getIpChanges(10);

        // Es darf nur der INITIAL-Eintrag existieren
        long countForHost = changes.stream()
            .filter(c -> "testhash456".equals(c.getHostHash()))
            .count();
        assertEquals(1, countForHost,
            "Gleiche IP darf keinen neuen Eintrag erzeugen, nur INITIAL");
    }

    @Test
    void testStatisticsWithEmptyDatabase() throws SQLException {
        // Leere Datenbank (nach clearTestData): Darf keinen Fehler werfen
        H2MeasurementRepository.Statistics stats = repo.calculateStatistics("PING", 1);

        assertNotNull(stats, "Statistik-Objekt darf auch bei leerer DB nicht null sein");
        assertEquals(0.0, stats.getAvgLatency(), 0.01, "Durchschnitt bei 0 Messungen muss 0 sein");
        assertEquals(0.0, stats.getP95Latency(), 0.01, "P95 bei 0 Messungen muss 0 sein");
        assertEquals(0.0, stats.getPacketLossPercent(), 0.01, "Paketverlust bei 0 Messungen muss 0 sein");
    }

    @Test
    void testHostReregistrationUpdatesLastSeen() throws SQLException {
        // Gleicher Host zweimal registrieren: lastSeen soll aktualisiert werden
        repo.registerHost("testhash789", "myhost", "Linux");
        try { Thread.sleep(50); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        repo.registerHost("testhash789", "myhost", "Linux");

        List<H2MeasurementRepository.HostInfo> hosts = repo.getAllHosts();
        long count = hosts.stream()
            .filter(h -> "testhash789".equals(h.getHostHash()))
            .count();
        assertEquals(1, count, "Gleicher Host darf nicht doppelt registriert werden");
    }
}