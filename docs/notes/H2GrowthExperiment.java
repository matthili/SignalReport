import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Random;

/**
 * Messprogramm: Dateiwachstum einer H2-2.4.240-Datenbank mit dem SignalReport-Schema
 * (Tabellen + Indizes 1:1 aus H2MeasurementRepository.createTablesOn) unter
 * verschiedenen Commit-Strategien. Pro "Zyklus" werden 5 Messzeilen eingefuegt und
 * nach jeder Zeile der hosts-MERGE ausgefuehrt -- exakt wie H2MeasurementRepository.save().
 *
 * Aufruf: java -cp h2-2.4.240.jar H2GrowthExperiment.java <cycles> <mode> <dir>
 *   mode = wd0-auto   : WRITE_DELAY=0, Autocommit pro Statement   (IST-Zustand SignalReport)
 *          wddef-auto : WRITE_DELAY Standard, Autocommit pro Statement
 *          wd0-tx     : WRITE_DELAY=0, eine Transaktion pro Zyklus
 *          wddef-tx   : WRITE_DELAY Standard, eine Transaktion pro Zyklus
 */
public class H2GrowthExperiment
{
    static final String INSERT = """
            INSERT INTO measurements
            (timestamp, target, latency_ms, success, type, local_ipv4, local_ipv6,
             external_ipv4, external_ipv6, host_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    static final String MERGE = """
            MERGE INTO hosts (host_hash, hostname, operating_system, first_seen, last_seen)
            KEY (host_hash)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;

    public static void main(String[] args) throws Exception
    {
        int cycles = Integer.parseInt(args[0]);
        String mode = args[1];
        Path dir = Paths.get(args[2]);
        Files.createDirectories(dir);
        String base = dir.resolve("exp-" + mode).toString();
        Path file = Paths.get(base + ".mv.db");
        Path lock = Paths.get(base + ".lock.db");
        Files.deleteIfExists(file);
        Files.deleteIfExists(Paths.get(base + ".trace.db"));

        boolean wd0 = mode.startsWith("wd0");
        boolean tx = mode.endsWith("-tx");
        String url = "jdbc:h2:" + base + ";DB_CLOSE_ON_EXIT=FALSE" + (wd0 ? ";WRITE_DELAY=0" : "");

        Class.forName("org.h2.Driver");
        String[][] rows = {
                {"8.8.8.8", "PING"}, {"google.com", "DNS"}, {"https://www.google.com", "HTTP"},
                {"192.168.1.1", "GW_NEAR"}, {"10.11.12.13", "GW_FAR"}};
        Random rnd = new Random(42);
        Instant ts = Instant.parse("2026-07-01T00:00:00Z");
        String hostHash = "0123456789abcdef0123456789abcdef";

        long t0 = System.nanoTime();
        long sizeOpen;
        boolean lockWhileOpen;
        try (Connection c = DriverManager.getConnection(url, "sa", ""))
            {
            createSchema(c);
            if (tx) c.setAutoCommit(false);
            try (PreparedStatement ins = c.prepareStatement(INSERT);
                 PreparedStatement merge = c.prepareStatement(MERGE))
                {
                for (int i = 0; i < cycles; i++)
                    {
                    for (String[] r : rows)
                        {
                        ins.setTimestamp(1, Timestamp.from(ts));
                        ins.setString(2, r[0]);
                        ins.setDouble(3, 5 + rnd.nextDouble() * 40);
                        ins.setBoolean(4, rnd.nextInt(200) != 0);
                        ins.setString(5, r[1]);
                        ins.setString(6, "192.168.1.50");
                        ins.setString(7, "fe80::1a2b:3c4d:5e6f:7a8b");
                        ins.setString(8, "84.112.33.199");
                        ins.setString(9, "2001:db8:1234:5678:9abc:def0:1234:5678");
                        ins.setString(10, hostHash);
                        ins.executeUpdate();

                        merge.setString(1, hostHash);
                        merge.setString(2, "tars");
                        merge.setString(3, "Windows 11 10.0");
                        merge.executeUpdate();
                        }
                    if (tx) c.commit();
                    ts = ts.plusSeconds(10);
                    }
                }
            lockWhileOpen = Files.exists(lock);
            sizeOpen = Files.size(file);
            }
        long t1 = System.nanoTime();
        long sizeClosed = Files.size(file);

        long rowCount = -1;
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement())
            {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM measurements"))
                {
                rs.next();
                rowCount = rs.getLong(1);
                }
            try
                {
                st.execute("SHUTDOWN COMPACT");
                } catch (SQLException ignored)
                {
                // SHUTDOWN schliesst die Verbindung selbst
                }
            } catch (SQLException ignored)
            {
            }
        long sizeCompact = Files.size(file);

        System.out.printf("RESULT mode=%s cycles=%d rows=%d commits/cycle=%d time=%.1fs "
                        + "size_open=%,d size_closed=%,d size_compact=%,d bytes/row_compact=%.1f lock_while_open=%s%n",
                mode, cycles, rowCount, tx ? 1 : 10, (t1 - t0) / 1e9,
                sizeOpen, sizeClosed, sizeCompact, sizeCompact / (double) rowCount, lockWhileOpen);
    }

    static void createSchema(Connection c) throws SQLException
    {
        String[] ddl = {
                """
                CREATE TABLE IF NOT EXISTS measurements (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    timestamp TIMESTAMP NOT NULL,
                    target VARCHAR(255) NOT NULL,
                    latency_ms DOUBLE NOT NULL,
                    success BOOLEAN NOT NULL,
                    type VARCHAR(20) NOT NULL,
                    local_ipv4 VARCHAR(45),
                    local_ipv6 VARCHAR(45),
                    external_ipv4 VARCHAR(45),
                    external_ipv6 VARCHAR(45),
                    host_hash VARCHAR(32) NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS hosts (
                    host_hash VARCHAR(32) PRIMARY KEY,
                    hostname VARCHAR(255) NOT NULL,
                    operating_system VARCHAR(255) NOT NULL,
                    first_seen TIMESTAMP NOT NULL,
                    last_seen TIMESTAMP NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS ip_changes (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    timestamp TIMESTAMP NOT NULL,
                    old_ip VARCHAR(45),
                    new_ip VARCHAR(45) NOT NULL,
                    change_type VARCHAR(20) NOT NULL,
                    host_hash VARCHAR(32) NOT NULL
                )
                """,
                "ALTER TABLE measurements ADD COLUMN IF NOT EXISTS excluded BOOLEAN DEFAULT FALSE",
                """
                CREATE TABLE IF NOT EXISTS service_checks (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    timestamp TIMESTAMP NOT NULL,
                    service_id VARCHAR(64) NOT NULL,
                    verdict VARCHAR(32) NOT NULL,
                    method VARCHAR(255),
                    http_status INT,
                    resolved_ip VARCHAR(45),
                    latency_ms DOUBLE
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_measurements_timestamp ON measurements(timestamp)",
                "CREATE INDEX IF NOT EXISTS idx_measurements_type ON measurements(type)",
                "CREATE INDEX IF NOT EXISTS idx_measurements_type_timestamp ON measurements(type, timestamp)",
                "CREATE INDEX IF NOT EXISTS idx_measurements_host_hash ON measurements(host_hash)",
                "CREATE INDEX IF NOT EXISTS idx_ip_changes_timestamp ON ip_changes(timestamp)",
                "CREATE INDEX IF NOT EXISTS idx_service_checks_service_ts ON service_checks(service_id, timestamp)"
        };
        try (Statement st = c.createStatement())
            {
            for (String s : ddl) st.execute(s);
            }
    }
}
