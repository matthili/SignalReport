package at.mafue.signalreport.storage;

import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H2-Integrationstests fuer die Tabelle {@code service_checks}: Speichern,
 * Zeitraum-Abfrage und "neueste Pruefung pro Dienst".
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceCheckRepositoryTest
{
    private static final String TEST_DB_PATH = "./data/test-service-checks";
    private H2MeasurementRepository repo;

    @BeforeAll
    void setUp() throws SQLException
    {
        repo = new H2MeasurementRepository(TEST_DB_PATH);
    }

    @AfterAll
    void tearDown() throws SQLException
    {
        if (repo != null)
            {
            repo.close();
            }
        new java.io.File(TEST_DB_PATH + ".mv.db").delete();
        new java.io.File(TEST_DB_PATH + ".trace.db").delete();
        new java.io.File(TEST_DB_PATH + "-shadow.mv.db").delete();
        new java.io.File(TEST_DB_PATH + "-shadow.trace.db").delete();
    }

    @BeforeEach
    void clear() throws SQLException
    {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:h2:" + TEST_DB_PATH + ";DB_CLOSE_ON_EXIT=FALSE", "sa", "");
             Statement stmt = conn.createStatement())
            {
            stmt.execute("DELETE FROM service_checks");
            }
    }

    @Test
    void testSaveAndFindSincePreservesFields() throws SQLException
    {
        Instant now = Instant.now();
        repo.saveServiceCheck(new ServiceCheck(now.minusSeconds(600), "facebook", "REACHABLE",
                "HTTP 200", 200, "31.13.84.36", 42.5));
        repo.saveServiceCheck(new ServiceCheck(now.minusSeconds(300), "facebook", "DNS_BLOCKED",
                "isp NXDOMAIN", -1, null, 0.0));

        List<ServiceCheck> checks = repo.findServiceChecksSince("facebook", now.minusSeconds(3600));

        assertEquals(2, checks.size());
        // chronologisch aufsteigend
        ServiceCheck first = checks.get(0);
        assertEquals("REACHABLE", first.getVerdict());
        assertEquals("HTTP 200", first.getMethod());
        assertEquals(200, first.getHttpStatus());
        assertEquals("31.13.84.36", first.getResolvedIp());
        assertEquals(42.5, first.getLatencyMs(), 0.001);

        ServiceCheck second = checks.get(1);
        assertEquals("DNS_BLOCKED", second.getVerdict());
        assertEquals(-1, second.getHttpStatus());
        assertNull(second.getResolvedIp());
    }

    @Test
    void testSinceFilterExcludesOlder() throws SQLException
    {
        Instant now = Instant.now();
        repo.saveServiceCheck(new ServiceCheck(now.minusSeconds(7200), "wikipedia", "REACHABLE",
                "alt", 200, "1.1.1.1", 10.0));
        repo.saveServiceCheck(new ServiceCheck(now.minusSeconds(60), "wikipedia", "REACHABLE",
                "neu", 200, "1.1.1.1", 11.0));

        List<ServiceCheck> recent = repo.findServiceChecksSince("wikipedia", now.minusSeconds(3600));

        assertEquals(1, recent.size(), "nur die juengere Pruefung liegt im Fenster");
        assertEquals("neu", recent.get(0).getMethod());
    }

    @Test
    void testFindLatestPerService() throws SQLException
    {
        Instant now = Instant.now();
        repo.saveServiceCheck(new ServiceCheck(now.minusSeconds(600), "facebook", "REACHABLE",
                "alt", 200, "31.13.84.36", 20.0));
        repo.saveServiceCheck(new ServiceCheck(now.minusSeconds(300), "facebook", "SNI_BLOCKED",
                "neu", -1, "31.13.84.36", 0.0));
        repo.saveServiceCheck(new ServiceCheck(now.minusSeconds(480), "wikipedia", "REACHABLE",
                "wiki", 200, "1.2.3.4", 15.0));

        List<ServiceCheck> latest = repo.findLatestServiceChecks();

        assertEquals(2, latest.size(), "je Dienst genau eine (die neueste) Pruefung");
        ServiceCheck fb = latest.stream().filter(c -> "facebook".equals(c.getServiceId()))
                .findFirst().orElseThrow();
        assertEquals("SNI_BLOCKED", fb.getVerdict(), "fuer facebook gilt die neueste Pruefung");

        ServiceCheck wiki = latest.stream().filter(c -> "wikipedia".equals(c.getServiceId()))
                .findFirst().orElseThrow();
        assertEquals("REACHABLE", wiki.getVerdict());
    }
}
