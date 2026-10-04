package at.mafue.signalreport.storage;

import at.mafue.signalreport.measurement.Measurement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Neuaufbau der Twin-Datenbank: Vereinigung von Primary und Shadow, Stunden-Fallback bei
 * unlesbaren Bereichen, Dateitausch mit Quarantaene, Marker-gesteuerter Neuaufbau beim Start.
 * Arbeitet mit echten H2-Dateien unter ./data/test-rebuild*.
 */
class DatabaseRebuilderTest
{
    private static final String BASE = "./data/test-rebuild";
    private static final Instant T0 = Instant.parse("2026-08-18T10:30:00Z");

    private final List<String> progress = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanFiles() throws IOException
    {
        for (String suffix : new String[]{".mv.db", "-shadow.mv.db", "-rebuild.mv.db", ".trace.db",
                "-shadow.trace.db", "-rebuild.trace.db", DatabaseRebuilder.MARKER_SUFFIX})
            {
            Files.deleteIfExists(Paths.get(BASE + suffix));
            }
        deleteTestQuarantine();
        try (Stream<Path> reports = Files.list(Paths.get("./data")))
            {
            reports.filter(p -> p.getFileName().toString().startsWith("test-rebuild_rebuild-report_"))
                    .forEach(p -> p.toFile().delete());
            }
    }

    @Test
    void testRebuildUnitesPrimaryAndShadowAndSwapsFiles() throws Exception
    {
        // Arrange: Twin-DB mit 3 gemeinsamen Messungen, Host, Dienst-Pruefung
        H2MeasurementRepository repo = new H2MeasurementRepository(BASE);
        repo.saveAll(List.of(
                row(T0, "8.8.8.8", "PING", true),
                row(T0, "google.com", "DNS", true),
                row(T0, "https://example.com", "HTTP", false)));
        repo.saveServiceCheck(new ServiceCheck(T0, "web-example", "REACHABLE", "HTTP 200", 200, "93.184.216.34", 120.0));
        repo.trackIpChange("1.2.3.4", "rebuildhost");
        repo.close();

        // nur in der Primary: eine zusaetzliche Zeile; nur in der Shadow: eine andere + excluded-Flag
        insertRaw(BASE, T0.plusSeconds(10), "8.8.8.8", "PING", 15.0, true, false);
        insertRaw(BASE + "-shadow", T0.plusSeconds(20), "8.8.8.8", "PING", 16.0, true, false);
        markExcluded(BASE + "-shadow", T0, "DNS");
        long oldPrimarySize = Files.size(Paths.get(BASE + ".mv.db"));

        // Act
        RebuildReport report = new DatabaseRebuilder(BASE, progress::add).rebuild(true);

        // Assert: Vereinigung = 3 gemeinsame + 1 nur Primary + 1 nur Shadow
        assertEquals(5, report.unionMeasurements, report.toText());
        assertTrue(report.swapped);
        assertNotNull(report.quarantineDir);
        assertTrue(Files.exists(report.quarantineDir.resolve("test-rebuild.mv.db")), "alte Primary in Quarantaene");
        assertTrue(Files.exists(report.quarantineDir.resolve("test-rebuild-shadow.mv.db")), "alte Shadow in Quarantaene");
        assertEquals(0, report.unreadableRangeCount());
        assertEquals(1, report.hosts);
        assertEquals(1, report.serviceChecks);
        assertEquals(1, report.ipChanges);
        assertNotNull(report.reportFile);
        assertTrue(Files.exists(report.reportFile));
        assertTrue(report.toText().contains("Vereinigung"));

        // neue Dateien: Primary und Shadow identisch gross, Primary nicht groesser als die alte
        long newPrimary = Files.size(Paths.get(BASE + ".mv.db"));
        assertEquals(newPrimary, Files.size(Paths.get(BASE + "-shadow.mv.db")), "Shadow ist eine Kopie der neuen Primary");
        assertTrue(newPrimary <= oldPrimarySize, "neue Datei darf nicht groesser sein als die alte");

        // Inhalt ueber das Repository pruefen
        H2MeasurementRepository rebuilt = new H2MeasurementRepository(BASE);
        try
            {
            List<Measurement> all = rebuilt.findAll();
            assertEquals(5, all.size());
            Measurement dns = all.stream().filter(m -> "DNS".equals(m.getType())).findFirst().orElseThrow();
            assertTrue(dns.isExcluded(), "excluded-Flag aus der Shadow muss uebernommen werden (ODER)");
            assertEquals(1, rebuilt.getAllHosts().size());
            assertEquals(1, rebuilt.findLatestServiceChecks().size());
            } finally
            {
            rebuilt.close();
            }
    }

    @Test
    void testHourFallbackSkipsOnlyUnreadableHourOfOneSource() throws Exception
    {
        H2MeasurementRepository repo = new H2MeasurementRepository(BASE);
        repo.saveAll(List.of(row(T0, "8.8.8.8", "PING", true)));                       // 10:30 in beiden
        repo.saveAll(List.of(row(T0.plus(3, ChronoUnit.HOURS), "8.8.8.8", "PING", true))); // 13:30 in beiden
        repo.close();
        insertRaw(BASE, T0.plusSeconds(600), "8.8.8.8", "PING", 11.0, true, false);                  // 10:40 nur Primary (lesbar)
        insertRaw(BASE, T0.plus(3, ChronoUnit.HOURS).plusSeconds(600), "8.8.8.8", "PING", 12.0, true, false); // 13:40 nur Primary (unlesbare Stunde)

        Instant badHourStart = Instant.parse("2026-08-18T13:00:00Z");
        Instant badHourEnd = Instant.parse("2026-08-18T14:00:00Z");

        // Simulierte Korruption: der ganze Tag und die Stunde 13-14 Uhr sind in der Primary unlesbar
        DatabaseRebuilder rebuilder = new DatabaseRebuilder(BASE, progress::add)
        {
            @Override
            protected List<MeasurementRow> readMeasurements(SourceDb src, Instant from, Instant to) throws SQLException
            {
                if ("Primary".equals(src.name))
                    {
                    boolean wholeDay = ChronoUnit.HOURS.between(from, to) >= 24;
                    boolean badHour = !from.isBefore(badHourStart) && !to.isAfter(badHourEnd);
                    if (wholeDay || badHour)
                        {
                        throw new SQLException("File corrupted while reading record (simuliert)", "90030", 90030);
                        }
                    }
                return super.readMeasurements(src, from, to);
            }
        };

        RebuildReport report = rebuilder.rebuild(true);

        // 2 gemeinsame + 10:40 (nur Primary, lesbare Stunde) = 3; 13:40 geht verloren, weil nur in der unlesbaren Stunde der Primary
        assertEquals(3, report.unionMeasurements, report.toText());
        RebuildReport.SourceSummary primary = report.sources.stream().filter(s -> "Primary".equals(s.name)).findFirst().orElseThrow();
        assertEquals(1, primary.unreadableRanges.size(), report.toText());
        assertTrue(primary.unreadableRanges.get(0).startsWith("2026-08-18 13:00 bis 2026-08-18 14:00"), primary.unreadableRanges.get(0));
        assertTrue(progress.stream().anyMatch(p -> p.contains("stundenweise")), String.join("\n", progress));
    }

    @Test
    void testMarkerTriggersRebuildAtRepositoryStart() throws Exception
    {
        H2MeasurementRepository repo = new H2MeasurementRepository(BASE);
        repo.saveAll(List.of(row(T0, "8.8.8.8", "PING", true), row(T0, "google.com", "DNS", true)));
        repo.close();

        DatabaseRebuilder.requestRebuild(BASE, "Testfall: simulierter Lesefehler");
        assertTrue(DatabaseRebuilder.isRebuildRequested(BASE));

        // Act: der Konstruktor fuehrt den Neuaufbau aus, bevor er die Twins oeffnet
        H2MeasurementRepository restarted = new H2MeasurementRepository(BASE);
        try
            {
            assertFalse(DatabaseRebuilder.isRebuildRequested(BASE), "Marker muss nach dem Neuaufbau entfernt sein");
            assertEquals(2, restarted.findAll().size(), "Daten muessen den Neuaufbau unveraendert ueberstehen");
            } finally
            {
            restarted.close();
            }
        assertTrue(Files.exists(Paths.get("./data/quarantine")), "alte Dateien liegen in der Quarantaene");
    }

    @Test
    void testRefusesWithoutReadableSource()
    {
        DatabaseRebuilder rebuilder = new DatabaseRebuilder("./data/test-rebuild-gibt-es-nicht", progress::add);
        DatabaseRebuilder.RebuildException ex = assertThrows(DatabaseRebuilder.RebuildException.class,
                () -> rebuilder.rebuild(true));
        assertTrue(ex.getMessage().contains("Keine Datenbank-Datei lesbar"), ex.getMessage());
    }

    // ------------------------------------------------------------------------

    private static Measurement row(Instant ts, String target, String type, boolean ok)
    {
        return new Measurement(target, 20.0, ok, type, ts, "192.168.1.100", "fe80::1", "85.182.1.1", "::1", "rebuildhost");
    }

    private static void insertRaw(String base, Instant ts, String target, String type, double latency,
                                  boolean ok, boolean excluded) throws SQLException
    {
        try (Connection c = DriverManager.getConnection("jdbc:h2:" + base + ";DB_CLOSE_ON_EXIT=FALSE", "sa", "");
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO measurements (timestamp, target, latency_ms, success, type, local_ipv4, local_ipv6,
                      external_ipv4, external_ipv6, host_hash, excluded) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""))
            {
            ps.setTimestamp(1, Timestamp.from(ts));
            ps.setString(2, target);
            ps.setDouble(3, latency);
            ps.setBoolean(4, ok);
            ps.setString(5, type);
            ps.setString(6, "192.168.1.100");
            ps.setString(7, "fe80::1");
            ps.setString(8, "85.182.1.1");
            ps.setString(9, "::1");
            ps.setString(10, "rebuildhost");
            ps.setBoolean(11, excluded);
            ps.executeUpdate();
            }
    }

    private static void markExcluded(String base, Instant ts, String type) throws SQLException
    {
        try (Connection c = DriverManager.getConnection("jdbc:h2:" + base + ";DB_CLOSE_ON_EXIT=FALSE", "sa", "");
             PreparedStatement ps = c.prepareStatement("UPDATE measurements SET excluded = TRUE WHERE timestamp = ? AND type = ?"))
            {
            ps.setTimestamp(1, Timestamp.from(ts));
            ps.setString(2, type);
            assertEquals(1, ps.executeUpdate());
            }
    }

    /** Entfernt nur Quarantaene-Ordner, die Dateien dieses Tests enthalten. */
    private static void deleteTestQuarantine() throws IOException
    {
        Path quarantine = Paths.get("./data/quarantine");
        if (!Files.isDirectory(quarantine)) return;
        try (Stream<Path> dirs = Files.list(quarantine))
            {
            for (Path dir : dirs.filter(d -> d.getFileName().toString().startsWith("rebuild_")).toList())
                {
                boolean ours;
                try (Stream<Path> files = Files.list(dir))
                    {
                    ours = files.anyMatch(f -> f.getFileName().toString().startsWith("test-rebuild"));
                    }
                if (ours)
                    {
                    try (Stream<Path> walk = Files.walk(dir))
                        {
                        walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                        }
                    }
                }
            }
    }
}
