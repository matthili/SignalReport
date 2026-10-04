package at.mafue.signalreport.storage;

import at.mafue.signalreport.measurement.Measurement;
import at.mafue.signalreport.network.HostIdentifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Repository fuer Messungen mit Twin-Database-Architektur.
 * <p>
 * Es werden zwei H2-Datenbanken parallel gefuehrt: eine Primary (Default-Quelle
 * fuer Lese-Operationen) und eine Shadow (Spiegelung aller Schreiboperationen).
 * Bei Korruption einer der beiden DBs wird sie beim naechsten Start automatisch
 * aus der noch intakten DB rekonstruiert. Damit ueberlebt SignalReport abrupte
 * Prozess-Terminierungen (Windows-Update-Neustart, Stromausfall) ohne nennens-
 * werten Datenverlust.
 * <p>
 * Schutz-Stufen:
 * 1. Eine Transaktion pro Messrunde ({@link #saveAll(List)}) bei H2-Standard-WRITE_DELAY
 * (500 ms): H2 schreibt die Commits gebuendelt und kompaktiert die Datei laufend im
 * Hintergrund. Bis 2.0.1 stand WRITE_DELAY=0 in der URL; das schaltet den
 * MVStore-Hintergrund-Thread und damit jede Kompaktierung ab (H2 2.4.240,
 * FileStore.setAutoCommitDelay startet ihn nur fuer Werte groesser 0). Gemessen:
 * 37,7 KB Dateiwachstum pro Messzeile statt 92,6 Byte Nutzdaten (siehe docs/Architecture.md).
 * 2. Twin-Spiegelung - jede Aenderung wird in beide DBs geschrieben.
 * 3. Auto-Recovery - beim Start werden DBs, die sich nicht oeffnen lassen, in
 * Quarantaene verschoben und aus der intakten DB per File-Copy wiederhergestellt.
 * 4. Selbstheilung im Betrieb - scheitert ein Lesezugriff auf der Primary an einer
 * Korruption (H2 90030, z. B. eine einzelne unlesbare Seite, die das Oeffnen nicht
 * verhindert), wird die Abfrage auf der Shadow wiederholt und ein Neuaufbau fuer den
 * naechsten Start vorgemerkt ({@link DatabaseRebuilder}: Vereinigung beider Dateien in
 * eine frische, kompakte Datei; alte Dateien wandern in die Quarantaene).
 */
public class H2MeasurementRepository
{
    private static final Logger logger = LoggerFactory.getLogger(H2MeasurementRepository.class);

    private final String primaryDbPath;
    private final String shadowDbPath;
    private final String primaryJdbcUrl;
    private final String shadowJdbcUrl;

    private Connection primary;
    private Connection shadow;

    public H2MeasurementRepository(String dbPath) throws SQLException
    {
        this.primaryDbPath = dbPath;
        this.shadowDbPath = dbPath + "-shadow";
        // WRITE_DELAY bleibt auf dem H2-Standard (500 ms): nur dann laeuft der
        // MVStore-Hintergrund-Thread, der Commits buendelt und die Datei kompaktiert.
        // WRITE_DELAY=0 (bis 2.0.1) liess die Dateien ungebremst wachsen, siehe Klassen-Javadoc.
        String jdbcSuffix = ";DB_CLOSE_ON_EXIT=FALSE";
        this.primaryJdbcUrl = "jdbc:h2:" + primaryDbPath + jdbcSuffix;
        this.shadowJdbcUrl = "jdbc:h2:" + shadowDbPath + jdbcSuffix;

        try
            {
            Class.forName("org.h2.Driver");
            Files.createDirectories(Paths.get("./data"));
            } catch (ClassNotFoundException | IOException e)
            {
            throw new SQLException("Fehler bei der Datenbank-Initialisierung", e);
            }

        // Angeforderter Neuaufbau (Marker-Datei, gesetzt nach einem Korruptions-Lesefehler im
        // Betrieb): vor dem Oeffnen der Twins aus den vorhandenen Dateien neu aufbauen.
        if (DatabaseRebuilder.isRebuildRequested(primaryDbPath))
            {
            logger.warn("Neuaufbau der Datenbank angefordert ({}). Baue aus den vorhandenen Dateien neu auf...",
                    DatabaseRebuilder.markerFile(primaryDbPath));
            try
                {
                RebuildReport report = new DatabaseRebuilder(primaryDbPath, logger::info).rebuild(true);
                logger.warn("Neuaufbau abgeschlossen:\n{}", report.toText());
                } catch (Exception e)
                {
                logger.error("Neuaufbau fehlgeschlagen: {} – oeffne die vorhandenen Dateien.", e.getMessage());
                DatabaseRebuilder.markFailed(primaryDbPath, e.getMessage());
                }
            }

        openTwins();
        createSchema(primary);
        createSchema(shadow);
    }

    // ========================================================================
    //  Twin-Setup, Recovery, Quarantaene
    // ========================================================================

    /**
     * Oeffnet beide Datenbanken. Korrupte DBs werden in Quarantaene verschoben
     * und aus der noch intakten DB per File-Copy rekonstruiert.
     */
    private void openTwins() throws SQLException
    {
        // Migrations-Schritt: existiert eine Primary aus einer Vorgaenger-Version,
        // aber noch keine Shadow, dann wird die Shadow beim ersten Start als
        // exakte Kopie der Primary angelegt. Damit haben beide DBs ab dem Update
        // denselben Datenstand und die Twin-Redundanz greift sofort.
        if (Files.exists(primaryDbFile()) && !Files.exists(shadowDbFile()))
            {
            logger.info("Twin-DB-Migration: kopiere Primary nach Shadow (einmaliger Vorgang)...");
            try
                {
                Files.copy(primaryDbFile(), shadowDbFile(), StandardCopyOption.REPLACE_EXISTING);
                logger.info("Shadow-DB initial aus Primary erstellt.");
                } catch (IOException ex)
                {
                logger.warn("Shadow-Initialkopie fehlgeschlagen ({}). Shadow startet leer; Twin-Redundanz greift dann erst nach dem ersten Neustart.", ex.getMessage());
                }
            }

        Connection p = null, s = null;
        SQLException pErr = null, sErr = null;

        try
            {
            p = DriverManager.getConnection(primaryJdbcUrl, "sa", "");
            } catch (SQLException e)
            {
            pErr = e;
            }

        try
            {
            s = DriverManager.getConnection(shadowJdbcUrl, "sa", "");
            } catch (SQLException e)
            {
            sErr = e;
            }

        // Schnellster Pfad: beide OK
        if (pErr == null && sErr == null)
            {
            this.primary = p;
            this.shadow = s;
            return;
            }

        // Nicht-Korruptions-Fehler durchreichen (z. B. fehlende Berechtigungen)
        if (pErr != null && !isCorruptionError(pErr))
            {
            silentlyClose(s);
            throw pErr;
            }
        if (sErr != null && !isCorruptionError(sErr))
            {
            silentlyClose(p);
            throw sErr;
            }

        // Ab hier sind alle Fehler Korruptions-Fehler.
        if (pErr != null && sErr == null)
            {
            logger.error("Primary-DB korrupt - stelle aus Shadow wieder her: {}", pErr.getMessage());
            silentlyClose(s);
            quarantineFile(primaryDbFile());
            copyFile(shadowDbFile(), primaryDbFile());
            this.primary = DriverManager.getConnection(primaryJdbcUrl, "sa", "");
            this.shadow = DriverManager.getConnection(shadowJdbcUrl, "sa", "");
            logger.info("Primary-DB wiederhergestellt.");
            return;
            }

        if (pErr == null && sErr != null)
            {
            logger.error("Shadow-DB korrupt - stelle aus Primary wieder her: {}", sErr.getMessage());
            silentlyClose(p);
            quarantineFile(shadowDbFile());
            copyFile(primaryDbFile(), shadowDbFile());
            this.primary = DriverManager.getConnection(primaryJdbcUrl, "sa", "");
            this.shadow = DriverManager.getConnection(shadowJdbcUrl, "sa", "");
            logger.info("Shadow-DB wiederhergestellt.");
            return;
            }

        // Beide korrupt - extrem unwahrscheinlich, aber kein Datenverlust ueber den
        // aktuellen Stand hinaus. Quarantaene + frischer Start auf beiden Seiten.
        logger.error("Beide Datenbanken korrupt - starte mit leeren DBs.");
        quarantineFile(primaryDbFile());
        quarantineFile(shadowDbFile());
        this.primary = DriverManager.getConnection(primaryJdbcUrl, "sa", "");
        this.shadow = DriverManager.getConnection(shadowJdbcUrl, "sa", "");
    }

    private boolean isCorruptionError(SQLException e)
    {
        // H2 wirft 90030 ("File corrupted") bei MVStore-Korruption
        if (e.getErrorCode() == 90030)
            {
            return true;
            }
        String msg = e.getMessage();
        return msg != null && (msg.contains("File corrupted") || msg.contains("Datei fehlerhaft"));
    }

    private Path primaryDbFile()
    {
        return Paths.get(primaryDbPath + ".mv.db");
    }

    private Path shadowDbFile()
    {
        return Paths.get(shadowDbPath + ".mv.db");
    }

    private void quarantineFile(Path file)
    {
        try
            {
            if (!Files.exists(file))
                {
                return;
                }
            Path parent = file.getParent();
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Path quarantineDir = (parent != null ? parent : Paths.get(".")).resolve("quarantine");
            Files.createDirectories(quarantineDir);
            String name = file.getFileName().toString().replace(".mv.db", "");
            Path target = quarantineDir.resolve(name + "-corrupt_" + timestamp + ".mv.db");
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            logger.info("Korrupte DB-Datei in Quarantaene verschoben: {}", target);
            } catch (IOException ex)
            {
            logger.error("Konnte korrupte DB-Datei nicht in Quarantaene verschieben: {}", ex.getMessage());
            }
    }

    private void copyFile(Path source, Path target) throws SQLException
    {
        try
            {
            if (!Files.exists(source))
                {
                throw new SQLException("Quell-DB existiert nicht: " + source);
                }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            logger.info("DB-Datei kopiert: {} -> {}", source.getFileName(), target.getFileName());
            } catch (IOException ex)
            {
            throw new SQLException("DB-Kopie fehlgeschlagen: " + ex.getMessage(), ex);
            }
    }

    private void silentlyClose(Connection c)
    {
        if (c == null) return;
        try
            {
            c.close();
            } catch (SQLException ignored)
            {
            }
    }

    /**
     * Wird aufgerufen, wenn ein Schreibvorgang auf die Shadow-DB im laufenden
     * Betrieb scheitert (sehr selten). Die Shadow-DB wird deaktiviert und beim
     * naechsten Start automatisch aus der Primary rekonstruiert.
     */
    private void disableShadow(SQLException reason)
    {
        if (shadow == null) return;
        logger.error("Shadow-DB Laufzeit-Fehler: {}", reason.getMessage());
        logger.warn("Shadow-DB wird deaktiviert. Reparatur erfolgt beim naechsten Neustart.");
        silentlyClose(shadow);
        shadow = null;
    }

    /**
     * Funktionales Interface fuer Schreiboperationen, die auf eine bestimmte
     * Connection ausgefuehrt werden sollen.
     */
    @FunctionalInterface
    private interface ConnectionAction
    {
        void apply(Connection c) throws SQLException;
    }

    /**
     * Fuehrt die uebergebene Schreiboperation auf Primary und Shadow aus.
     * Fehler auf der Primary werden propagiert, Fehler auf der Shadow fuehren
     * zum Deaktivieren der Shadow-DB (Reparatur beim naechsten Start).
     */
    private void writeOnBoth(ConnectionAction action) throws SQLException
    {
        // Primary zuerst (autoritative Quelle)
        action.apply(primary);

        // Shadow danach (Best-Effort)
        if (shadow != null)
            {
            try
                {
                action.apply(shadow);
                } catch (SQLException ex)
                {
                disableShadow(ex);
                }
            }
    }

    /**
     * Funktionales Interface fuer Leseoperationen auf einer bestimmten Connection.
     */
    @FunctionalInterface
    private interface ReadAction<T>
    {
        T apply(Connection c) throws SQLException;
    }

    /**
     * Fuehrt eine Leseoperation auf der Primary aus. Scheitert sie an einer Korruption
     * (H2 90030), wird sie auf der Shadow wiederholt und ein Neuaufbau fuer den naechsten
     * Start vorgemerkt. Andere Fehler werden unveraendert durchgereicht. So bleiben
     * Berichte und Exporte auch mit einer beschaedigten Primary benutzbar.
     */
    private <T> T readWithFallback(ReadAction<T> action) throws SQLException
    {
        try
            {
            return action.apply(primary);
            } catch (SQLException e)
            {
            if (!isCorruptionError(e) || shadow == null)
                {
                throw e;
                }
            logger.error("Lesefehler auf der Primary-DB (Korruption): {}", e.getMessage());
            logger.warn("Weiche fuer diese Abfrage auf die Shadow-DB aus; Neuaufbau beim naechsten Start vorgemerkt ({}).",
                    DatabaseRebuilder.markerFile(primaryDbPath));
            DatabaseRebuilder.requestRebuild(primaryDbPath, "Lesefehler Primary: " + e.getMessage());
            return action.apply(shadow);
            }
    }

    // ========================================================================
    //  Schema
    // ========================================================================

    /** Legt Tabellen und Indizes an bzw. migriert sie; auch fuer den Neuaufbau ({@link DatabaseRebuilder}). */
    static void createSchema(Connection c) throws SQLException
    {
        String measurementsTable = """
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
                """;

        String hostsTable = """
                CREATE TABLE IF NOT EXISTS hosts (
                    host_hash VARCHAR(32) PRIMARY KEY,
                    hostname VARCHAR(255) NOT NULL,
                    operating_system VARCHAR(255) NOT NULL,
                    first_seen TIMESTAMP NOT NULL,
                    last_seen TIMESTAMP NOT NULL
                )
                """;

        String ipChangesTable = """
                CREATE TABLE IF NOT EXISTS ip_changes (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    timestamp TIMESTAMP NOT NULL,
                    old_ip VARCHAR(45),
                    new_ip VARCHAR(45) NOT NULL,
                    change_type VARCHAR(20) NOT NULL,
                    host_hash VARCHAR(32) NOT NULL
                )
                """;

        String indexTimestamp = "CREATE INDEX IF NOT EXISTS idx_measurements_timestamp ON measurements(timestamp)";
        // Migration: idx_measurements_type entfaellt. Er ist ein Praefix von
        // idx_measurements_type_timestamp (beide type-Abfragen filtern zusaetzlich auf
        // timestamp) und kostete pro INSERT einen weiteren B-Baum-Schreibvorgang.
        String dropIndexType = "DROP INDEX IF EXISTS idx_measurements_type";
        String indexTypeTimestamp = "CREATE INDEX IF NOT EXISTS idx_measurements_type_timestamp ON measurements(type, timestamp)";
        String indexHostHash = "CREATE INDEX IF NOT EXISTS idx_measurements_host_hash ON measurements(host_hash)";
        String indexIpChangesTimestamp = "CREATE INDEX IF NOT EXISTS idx_ip_changes_timestamp ON ip_changes(timestamp)";

        // Migration: Spalte fuer aus der Ausfalls-Auswertung ausgenommene Messungen.
        // Idempotent – fuegt die Spalte nur hinzu, wenn sie noch fehlt (bestehende DBs).
        String addExcluded = "ALTER TABLE measurements ADD COLUMN IF NOT EXISTS excluded BOOLEAN DEFAULT FALSE";

        // Tabelle fuer die periodische Dienst-Erreichbarkeitspruefung. CREATE ... IF NOT
        // EXISTS ist zugleich die Migration: bestehende DBs erhalten sie beim naechsten Start.
        String serviceChecksTable = """
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
                """;
        String indexServiceChecks =
                "CREATE INDEX IF NOT EXISTS idx_service_checks_service_ts ON service_checks(service_id, timestamp)";

        // Stundenwerte (Verdichtung) und Zustand der Aufbereitung (Wasserstandsmarken).
        // CREATE ... IF NOT EXISTS ist zugleich die Migration fuer bestehende DBs.
        String hourlyTable = """
                CREATE TABLE IF NOT EXISTS measurement_hourly (
                    hour_start TIMESTAMP NOT NULL,
                    type VARCHAR(20) NOT NULL,
                    target VARCHAR(255) NOT NULL,
                    host_hash VARCHAR(32),
                    sample_count INT NOT NULL,
                    ok_count INT NOT NULL,
                    excluded_count INT NOT NULL,
                    min_ms DOUBLE,
                    avg_ms DOUBLE,
                    median_ms DOUBLE,
                    p95_ms DOUBLE,
                    max_ms DOUBLE,
                    max_at TIMESTAMP,
                    jitter_ms DOUBLE,
                    PRIMARY KEY (hour_start, type, target)
                )
                """;
        String indexHourlyTypeHour =
                "CREATE INDEX IF NOT EXISTS idx_hourly_type_hour ON measurement_hourly(type, hour_start)";
        String stateTable = """
                CREATE TABLE IF NOT EXISTS rollup_state (
                    state_key VARCHAR(64) PRIMARY KEY,
                    state_value VARCHAR(255)
                )
                """;

        try (Statement stmt = c.createStatement())
            {
            stmt.execute(measurementsTable);
            stmt.execute(hostsTable);
            stmt.execute(ipChangesTable);
            stmt.execute(addExcluded);
            stmt.execute(serviceChecksTable);
            stmt.execute(hourlyTable);
            stmt.execute(stateTable);
            stmt.execute(indexTimestamp);
            stmt.execute(indexTypeTimestamp);
            stmt.execute(indexHostHash);
            stmt.execute(indexIpChangesTimestamp);
            stmt.execute(indexServiceChecks);
            stmt.execute(indexHourlyTypeHour);
            }

        // Den alten Index separat entfernen: Scheitert das (z. B. weil in einer vorgeschaedigten
        // Datei eine seiner Seiten unlesbar ist), darf das den Start nicht verhindern. Der
        // Index wird nur noch nicht gebraucht, er stoert nicht.
        try (Statement stmt = c.createStatement())
            {
            stmt.execute(dropIndexType);
            } catch (SQLException e)
            {
            logger.warn("Alter Index idx_measurements_type konnte nicht entfernt werden: {}", e.getMessage());
            }
    }

    // ========================================================================
    //  Schreiboperationen (Twin-Spiegelung)
    // ========================================================================

    public void registerHost(String hostHash, String hostname, String os) throws SQLException
    {
        String sql = """
                MERGE INTO hosts (host_hash, hostname, operating_system, first_seen, last_seen)
                KEY (host_hash)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """;

        writeOnBoth(c ->
        {
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setString(1, hostHash);
            pstmt.setString(2, hostname);
            pstmt.setString(3, os);
            pstmt.executeUpdate();
            }
        });
    }

    public void trackIpChange(String currentIp, String hostHash) throws SQLException
    {
        if (currentIp == null || currentIp.equals("unknown"))
            {
            return;
            }

        // Lese-Operation (Primary, bei Korruption Shadow)
        String lastIp = getLastKnownIp(hostHash);

        if (lastIp == null)
            {
            recordIpChange(null, currentIp, "INITIAL", hostHash);
            } else if (!lastIp.equals(currentIp))
            {
            recordIpChange(lastIp, currentIp, "CHANGE", hostHash);
            }
    }

    private String getLastKnownIp(String hostHash) throws SQLException
    {
        String sql = """
                SELECT new_ip FROM ip_changes
                WHERE host_hash = ?
                ORDER BY timestamp DESC
                LIMIT 1
                """;

        return readWithFallback(c ->
        {
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setString(1, hostHash);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next())
                {
                return rs.getString("new_ip");
                }
            return null;
            }
        });
    }

    private void recordIpChange(String oldIp, String newIp, String changeType, String hostHash) throws SQLException
    {
        String sql = """
                INSERT INTO ip_changes (timestamp, old_ip, new_ip, change_type, host_hash)
                VALUES (CURRENT_TIMESTAMP, ?, ?, ?, ?)
                """;

        writeOnBoth(c ->
        {
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setString(1, oldIp);
            pstmt.setString(2, newIp);
            pstmt.setString(3, changeType);
            pstmt.setString(4, hostHash);
            pstmt.executeUpdate();
            }
        });
    }

    /** Speichert eine einzelne Messung (eigene Transaktion). Fuer Messrunden {@link #saveAll(List)} verwenden. */
    public void save(Measurement m) throws SQLException
    {
        saveAll(List.of(m));
    }

    /**
     * Speichert alle Messungen einer Messrunde in EINER Transaktion pro DB (ein Commit
     * statt zwei pro Messung) und aktualisiert den Host-Eintrag einmal pro Runde.
     * Weniger Commits bedeuten bei H2/MVStore weniger Chunks und damit weniger
     * Dateiwachstum; die Runde ist zudem atomar (alles oder nichts).
     */
    public void saveAll(List<Measurement> batch) throws SQLException
    {
        if (batch == null || batch.isEmpty())
            {
            return;
            }

        String insertSql = """
                INSERT INTO measurements
                (timestamp, target, latency_ms, success, type, local_ipv4, local_ipv6,
                 external_ipv4, external_ipv6, host_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        String mergeHostSql = """
                MERGE INTO hosts (host_hash, hostname, operating_system, first_seen, last_seen)
                KEY (host_hash)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """;

        String hostname = HostIdentifier.getHostname();
        String os = HostIdentifier.getOperatingSystem();

        // Host-Hashes der Runde (praktisch immer genau einer)
        Set<String> hostHashes = new LinkedHashSet<>();
        for (Measurement m : batch)
            {
            hostHashes.add(m.getHostHash());
            }

        writeOnBoth(c ->
        {
        boolean previousAutoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try
            {
            try (PreparedStatement pstmt = c.prepareStatement(insertSql))
                {
                for (Measurement m : batch)
                    {
                    pstmt.setTimestamp(1, Timestamp.from(m.getTimestamp()));
                    pstmt.setString(2, m.getTarget());
                    pstmt.setDouble(3, m.getLatencyMs());
                    pstmt.setBoolean(4, m.isSuccess());
                    pstmt.setString(5, m.getType());
                    pstmt.setString(6, m.getLocalIPv4());
                    pstmt.setString(7, m.getLocalIPv6());
                    pstmt.setString(8, m.getExternalIPv4());
                    pstmt.setString(9, m.getExternalIPv6());
                    pstmt.setString(10, m.getHostHash());
                    pstmt.addBatch();
                    }
                pstmt.executeBatch();
                }
            try (PreparedStatement pstmt = c.prepareStatement(mergeHostSql))
                {
                for (String hostHash : hostHashes)
                    {
                    pstmt.setString(1, hostHash);
                    pstmt.setString(2, hostname);
                    pstmt.setString(3, os);
                    pstmt.executeUpdate();
                    }
                }
            c.commit();
            } catch (SQLException e)
            {
            try
                {
                c.rollback();
                } catch (SQLException ignored)
                {
                }
            throw e;
            } finally
            {
            c.setAutoCommit(previousAutoCommit);
            }
        });
    }

    /**
     * Markiert alle Messungen im Zeitfenster [from, to] als (nicht) aus der
     * Ausfalls-Auswertung ausgenommen. Die Daten bleiben erhalten, werden aber
     * je nach Flag bei Verfuegbarkeit/Ausfaellen mitgezaehlt oder nicht.
     * Gibt die Anzahl der betroffenen Zeilen (Primary) zurueck.
     */
    public int excludeRange(Instant from, Instant to, boolean excluded) throws SQLException
    {
        String sql = "UPDATE measurements SET excluded = ? WHERE timestamp >= ? AND timestamp <= ?";
        int[] affected = {0};
        writeOnBoth(c ->
        {
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setBoolean(1, excluded);
            pstmt.setTimestamp(2, Timestamp.from(from));
            pstmt.setTimestamp(3, Timestamp.from(to));
            int n = pstmt.executeUpdate();
            if (c == primary) affected[0] = n;
            }
        });
        return affected[0];
    }

    // ========================================================================
    //  Lese-Operationen (Primary; bei Korruption Ausweichen auf die Shadow)
    // ========================================================================

    public List<IpChange> getIpChanges(int limit) throws SQLException
    {
        String sql = """
                SELECT timestamp, old_ip, new_ip, change_type, host_hash
                FROM ip_changes
                ORDER BY timestamp DESC
                LIMIT ?
                """;

        return readWithFallback(c ->
        {
        List<IpChange> results = new ArrayList<>();
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setInt(1, limit);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next())
                {
                results.add(new IpChange(
                        rs.getTimestamp("timestamp").toInstant(),
                        rs.getString("old_ip"),
                        rs.getString("new_ip"),
                        rs.getString("change_type"),
                        rs.getString("host_hash")
                ));
                }
            }
        return results;
        });
    }

    public List<IpChangeStats> getIpChangeStatistics() throws SQLException
    {
        String sql = """
                SELECT
                    host_hash,
                    COUNT(*) as change_count,
                    MIN(timestamp) as first_change,
                    MAX(timestamp) as last_change
                FROM ip_changes
                GROUP BY host_hash
                ORDER BY last_change DESC
                """;

        return readWithFallback(c ->
        {
        List<IpChangeStats> results = new ArrayList<>();
        try (Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery(sql))
            {
            while (rs.next())
                {
                results.add(new IpChangeStats(
                        rs.getString("host_hash"),
                        rs.getInt("change_count"),
                        rs.getTimestamp("first_change").toInstant(),
                        rs.getTimestamp("last_change").toInstant()
                ));
                }
            }
        return results;
        });
    }

    public List<Measurement> findLastN(int n) throws SQLException
    {
        String sql = """
                SELECT timestamp, target, latency_ms, success, type,
                       local_ipv4, local_ipv6, external_ipv4, external_ipv6, host_hash, excluded
                FROM measurements
                ORDER BY timestamp DESC
                LIMIT ?
                """;

        return readWithFallback(c ->
        {
        List<Measurement> results = new ArrayList<>();
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setInt(1, n);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next())
                {
                results.add(readMeasurement(rs));
                }
            }
        return results;
        });
    }

    public List<Measurement> findSince(Instant since) throws SQLException
    {
        String sql = """
                SELECT timestamp, target, latency_ms, success, type,
                       local_ipv4, local_ipv6, external_ipv4, external_ipv6, host_hash, excluded
                FROM measurements
                WHERE timestamp >= ?
                ORDER BY timestamp ASC
                """;

        return readWithFallback(c ->
        {
        List<Measurement> results = new ArrayList<>();
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setTimestamp(1, java.sql.Timestamp.from(since));
            ResultSet rs = pstmt.executeQuery();
            while (rs.next())
                {
                results.add(readMeasurement(rs));
                }
            }
        return results;
        });
    }

    public List<Measurement> findAll() throws SQLException
    {
        String sql = """
                SELECT timestamp, target, latency_ms, success, type,
                       local_ipv4, local_ipv6, external_ipv4, external_ipv6, host_hash, excluded
                FROM measurements
                ORDER BY timestamp ASC
                """;

        return readWithFallback(c ->
        {
        List<Measurement> results = new ArrayList<>();
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            ResultSet rs = pstmt.executeQuery();
            while (rs.next())
                {
                results.add(readMeasurement(rs));
                }
            }
        return results;
        });
    }

    private Measurement readMeasurement(ResultSet rs) throws SQLException
    {
        Instant timestamp = rs.getTimestamp(1).toInstant();
        String target = rs.getString(2);
        double latency = rs.getDouble(3);
        boolean success = rs.getBoolean(4);
        String type = rs.getString(5);
        String localIPv4 = rs.getString(6);
        String localIPv6 = rs.getString(7);
        String externalIPv4 = rs.getString(8);
        String externalIPv6 = rs.getString(9);
        String hostHash = rs.getString(10);
        Measurement m = new Measurement(target, latency, success, type, timestamp,
                localIPv4, localIPv6, externalIPv4, externalIPv6, hostHash);
        m.setExcluded(rs.getBoolean(11));
        return m;
    }

    public List<HostInfo> getAllHosts() throws SQLException
    {
        String sql = "SELECT host_hash, hostname, operating_system, first_seen, last_seen FROM hosts ORDER BY last_seen DESC";

        return readWithFallback(c ->
        {
        List<HostInfo> results = new ArrayList<>();
        try (Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery(sql))
            {
            while (rs.next())
                {
                results.add(new HostInfo(
                        rs.getString("host_hash"),
                        rs.getString("hostname"),
                        rs.getString("operating_system"),
                        rs.getTimestamp("first_seen").toInstant(),
                        rs.getTimestamp("last_seen").toInstant()
                ));
                }
            }
        return results;
        });
    }

    /** Rohwerte einer Statistik-Abfrage: Latenzen der erfolgreichen Messungen, Gesamt- und Fehlzahl. */
    private record RawStatistics(List<Double> latencies, int total, int failed)
    {
    }

    public Statistics calculateStatistics(String type, int hours) throws SQLException
    {
        String sql = """
                SELECT latency_ms, success
                FROM measurements
                WHERE type = ?
                  AND excluded = FALSE
                  AND timestamp >= DATEADD('HOUR', ?, CURRENT_TIMESTAMP)
                ORDER BY timestamp ASC
                """;

        // Rohwerte lesen (Primary, bei Korruption Shadow); die Auswertung folgt im Speicher
        RawStatistics raw = readWithFallback(c ->
        {
        List<Double> lat = new ArrayList<>();
        int total = 0;
        int failed = 0;
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setString(1, type);
            pstmt.setInt(2, -hours);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next())
                {
                double latency = rs.getDouble("latency_ms");
                boolean success = rs.getBoolean("success");

                total++;
                if (success)
                    {
                    lat.add(latency);
                    } else
                    {
                    failed++;
                    }
                }
            }
        return new RawStatistics(lat, total, failed);
        });
        List<Double> latencies = raw.latencies();
        int total = raw.total();
        int failed = raw.failed();

        if (latencies.isEmpty())
            {
            return new Statistics(0, 0, 0, failed > 0 ? 100.0 : 0, 0);
            }

        double avgLatency = latencies.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        // Jitter: mittlere Abweichung aufeinanderfolgender Messungen (chronologisch)
        double jitter = 0;
        if (latencies.size() > 1)
            {
            double sumDiff = 0;
            for (int i = 1; i < latencies.size(); i++)
                {
                sumDiff += Math.abs(latencies.get(i) - latencies.get(i - 1));
                }
            jitter = sumDiff / (latencies.size() - 1);
            }

        // P95 und Max: benoetigen sortierte Werte
        List<Double> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        int p95Index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        p95Index = Math.max(0, Math.min(p95Index, sorted.size() - 1));
        double p95Latency = sorted.get(p95Index);

        double maxLatency = sorted.get(sorted.size() - 1);

        double packetLossPercent = total > 0 ? (failed * 100.0) / total : 0;

        return new Statistics(avgLatency, p95Latency, maxLatency, packetLossPercent, jitter);
    }

    /**
     * Stunden-Mittelwerte (Heatmap) der letzten {@code days} Tage. Verdichtete Stunden
     * kommen aus {@code measurement_hourly}, der noch nicht verdichtete Rest (hoechstens die
     * laufende und die letzte Stunde) aus den Rohdaten; beides wird nach Messungszahl
     * gewichtet zusammengefuehrt.
     */
    public List<HourlyAverage> calculateHourlyAverages(String type, int days) throws SQLException
    {
        Instant now = Instant.now();
        Instant since = now.minus(days, java.time.temporal.ChronoUnit.DAYS);
        Instant watermark = getRollupWatermark();
        Instant rolledUntil = watermark == null ? null : watermark.plus(1, java.time.temporal.ChronoUnit.HOURS);

        if (rolledUntil == null || !rolledUntil.isAfter(since))
            {
            return calculateHourlyAveragesRaw(type, since, now);
            }

        // Summen je Tagesstunde: Rollups fuer [since, rolledUntil), Rohdaten fuer [rolledUntil, now)
        double[] weightedSum = new double[24];
        long[] counts = new long[24];
        for (HourlyAverage a : calculateHourlyAveragesFromRollups(type, since, rolledUntil))
            {
            weightedSum[a.getHourOfDay()] += a.getAvgLatency() * a.getCount();
            counts[a.getHourOfDay()] += a.getCount();
            }
        if (rolledUntil.isBefore(now))
            {
            for (HourlyAverage a : calculateHourlyAveragesRaw(type, rolledUntil, now))
                {
                weightedSum[a.getHourOfDay()] += a.getAvgLatency() * a.getCount();
                counts[a.getHourOfDay()] += a.getCount();
                }
            }
        List<HourlyAverage> results = new ArrayList<>();
        for (int h = 0; h < 24; h++)
            {
            if (counts[h] > 0)
                {
                results.add(new HourlyAverage(h, weightedSum[h] / counts[h], (int) Math.min(Integer.MAX_VALUE, counts[h])));
                }
            }
        return results;
    }

    /** Stunden-Mittelwerte direkt aus den Rohdaten eines Zeitraums [from, to). */
    List<HourlyAverage> calculateHourlyAveragesRaw(String type, Instant from, Instant to) throws SQLException
    {
        String sql = """
                SELECT
                    EXTRACT(HOUR FROM timestamp) AS hour_of_day,
                    AVG(latency_ms) AS avg_latency,
                    COUNT(*) AS measurement_count
                FROM measurements
                WHERE type = ?
                  AND success = true
                  AND timestamp >= ? AND timestamp < ?
                GROUP BY EXTRACT(HOUR FROM timestamp)
                ORDER BY hour_of_day
                """;

        return readWithFallback(c ->
        {
        List<HourlyAverage> results = new ArrayList<>();
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setString(1, type);
            pstmt.setTimestamp(2, Timestamp.from(from));
            pstmt.setTimestamp(3, Timestamp.from(to));
            ResultSet rs = pstmt.executeQuery();
            while (rs.next())
                {
                results.add(new HourlyAverage(
                        rs.getInt("hour_of_day"),
                        rs.getDouble("avg_latency"),
                        rs.getInt("measurement_count")
                ));
                }
            }
        return results;
        });
    }

    /** Stunden-Mittelwerte (Heatmap) aus den Stundenwerten, nach erfolgreichen Messungen gewichtet. */
    public List<HourlyAverage> calculateHourlyAveragesFromRollups(String type, Instant from, Instant to) throws SQLException
    {
        String sql = """
                SELECT
                    EXTRACT(HOUR FROM hour_start) AS hour_of_day,
                    SUM(avg_ms * ok_count) AS weighted_sum,
                    SUM(ok_count) AS ok_total
                FROM measurement_hourly
                WHERE type = ?
                  AND hour_start >= ? AND hour_start < ?
                GROUP BY EXTRACT(HOUR FROM hour_start)
                ORDER BY hour_of_day
                """;

        return readWithFallback(c ->
        {
        List<HourlyAverage> results = new ArrayList<>();
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setString(1, type);
            pstmt.setTimestamp(2, Timestamp.from(from));
            pstmt.setTimestamp(3, Timestamp.from(to));
            ResultSet rs = pstmt.executeQuery();
            while (rs.next())
                {
                long okTotal = rs.getLong("ok_total");
                if (okTotal <= 0) continue;
                results.add(new HourlyAverage(rs.getInt("hour_of_day"),
                        rs.getDouble("weighted_sum") / okTotal, (int) Math.min(Integer.MAX_VALUE, okTotal)));
                }
            }
        return results;
        });
    }

    // ========================================================================
    //  Stundenwerte (Verdichtung) und Aufbereitungs-Zustand
    // ========================================================================

    /** Rohzeilen eines Zeitraums [from, to) in chronologischer Reihenfolge (fuer die Verdichtung). */
    public List<RawRow> findRawRows(Instant from, Instant to) throws SQLException
    {
        String sql = """
                SELECT timestamp, type, target, latency_ms, success, excluded, host_hash
                FROM measurements
                WHERE timestamp >= ? AND timestamp < ?
                ORDER BY timestamp ASC
                """;
        return readWithFallback(c ->
        {
        List<RawRow> rows = new ArrayList<>();
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setTimestamp(1, Timestamp.from(from));
            pstmt.setTimestamp(2, Timestamp.from(to));
            ResultSet rs = pstmt.executeQuery();
            while (rs.next())
                {
                rows.add(new RawRow(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3),
                        rs.getDouble(4), rs.getBoolean(5), rs.getBoolean(6), rs.getString(7)));
                }
            }
        return rows;
        });
    }

    /**
     * Ruft die Messungen eines Zeitraums nacheinander ab, ohne sie alle im Speicher zu halten
     * (streamender CSV-Export). {@code typeFilter} null = alle Typen.
     */
    public void forEachMeasurement(Instant from, Instant to, String typeFilter,
                                   java.util.function.Consumer<Measurement> consumer) throws SQLException
    {
        String sql = """
                SELECT timestamp, target, latency_ms, success, type,
                       local_ipv4, local_ipv6, external_ipv4, external_ipv6, host_hash, excluded
                FROM measurements
                WHERE timestamp >= ? AND timestamp < ?
                """ + (typeFilter != null ? " AND type = ?" : "") + " ORDER BY timestamp ASC";
        readWithFallback(c ->
        {
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setTimestamp(1, Timestamp.from(from));
            pstmt.setTimestamp(2, Timestamp.from(to));
            if (typeFilter != null) pstmt.setString(3, typeFilter);
            pstmt.setFetchSize(1000);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next())
                {
                consumer.accept(readMeasurement(rs));
                }
            }
        return null;
        });
    }

    /** Aelteste Messung (null bei leerer Tabelle). */
    public Instant findOldestMeasurement() throws SQLException
    {
        return readWithFallback(c ->
        {
        try (Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MIN(timestamp) FROM measurements"))
            {
            if (rs.next())
                {
                Timestamp t = rs.getTimestamp(1);
                return t != null ? t.toInstant() : null;
                }
            return null;
            }
        });
    }

    public long countMeasurements() throws SQLException
    {
        return countRows("measurements");
    }

    public long countHourlyRollups() throws SQLException
    {
        return countRows("measurement_hourly");
    }

    private long countRows(String table) throws SQLException
    {
        return readWithFallback(c ->
        {
        try (Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table))
            {
            return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    /** Schreibt einen Stundenwert (MERGE ueber den Primaerschluessel, Twin-gespiegelt). */
    public void mergeHourlyRollup(HourlyRollup r) throws SQLException
    {
        String sql = """
                MERGE INTO measurement_hourly
                (hour_start, type, target, host_hash, sample_count, ok_count, excluded_count,
                 min_ms, avg_ms, median_ms, p95_ms, max_ms, max_at, jitter_ms)
                KEY (hour_start, type, target)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        writeOnBoth(c ->
        {
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setTimestamp(1, Timestamp.from(r.getHourStart()));
            pstmt.setString(2, r.getType());
            pstmt.setString(3, r.getTarget());
            pstmt.setString(4, r.getHostHash());
            pstmt.setInt(5, r.getSampleCount());
            pstmt.setInt(6, r.getOkCount());
            pstmt.setInt(7, r.getExcludedCount());
            pstmt.setDouble(8, r.getMinMs());
            pstmt.setDouble(9, r.getAvgMs());
            pstmt.setDouble(10, r.getMedianMs());
            pstmt.setDouble(11, r.getP95Ms());
            pstmt.setDouble(12, r.getMaxMs());
            if (r.getMaxAt() != null) pstmt.setTimestamp(13, Timestamp.from(r.getMaxAt()));
            else pstmt.setNull(13, Types.TIMESTAMP);
            pstmt.setDouble(14, r.getJitterMs());
            pstmt.executeUpdate();
            }
        });
    }

    /** Stundenwerte eines Zeitraums [from, to) chronologisch; {@code type} null = alle Typen. */
    public List<HourlyRollup> findHourlyRollups(String type, Instant from, Instant to) throws SQLException
    {
        String sql = "SELECT hour_start, type, target, host_hash, sample_count, ok_count, excluded_count, "
                + "min_ms, avg_ms, median_ms, p95_ms, max_ms, max_at, jitter_ms FROM measurement_hourly "
                + "WHERE hour_start >= ? AND hour_start < ?" + (type != null ? " AND type = ?" : "")
                + " ORDER BY hour_start ASC, type ASC, target ASC";
        return readWithFallback(c ->
        {
        List<HourlyRollup> results = new ArrayList<>();
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setTimestamp(1, Timestamp.from(from));
            pstmt.setTimestamp(2, Timestamp.from(to));
            if (type != null) pstmt.setString(3, type);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next())
                {
                results.add(readHourlyRollup(rs));
                }
            }
        return results;
        });
    }

    /** Die {@code limit} Stunden mit den groessten erfolgreichen Latenzen im Zeitraum (alle Typen). */
    public List<HourlyRollup> findWorstHours(Instant from, Instant to, int limit) throws SQLException
    {
        String sql = "SELECT hour_start, type, target, host_hash, sample_count, ok_count, excluded_count, "
                + "min_ms, avg_ms, median_ms, p95_ms, max_ms, max_at, jitter_ms FROM measurement_hourly "
                + "WHERE hour_start >= ? AND hour_start < ? AND ok_count > 0 ORDER BY max_ms DESC LIMIT ?";
        return readWithFallback(c ->
        {
        List<HourlyRollup> results = new ArrayList<>();
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setTimestamp(1, Timestamp.from(from));
            pstmt.setTimestamp(2, Timestamp.from(to));
            pstmt.setInt(3, limit);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next())
                {
                results.add(readHourlyRollup(rs));
                }
            }
        return results;
        });
    }

    private static HourlyRollup readHourlyRollup(ResultSet rs) throws SQLException
    {
        Timestamp maxAt = rs.getTimestamp(13);
        return new HourlyRollup(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getDouble(8), rs.getDouble(9), rs.getDouble(10),
                rs.getDouble(11), rs.getDouble(12), maxAt != null ? maxAt.toInstant() : null, rs.getDouble(14));
    }

    /**
     * Statistik eines Typs ueber [from, to) aus den Stundenwerten. Durchschnitt, Maximum und
     * Paketverlust sind exakt (gewichtete Summen); 95. Perzentil und Jitter sind nach
     * Messungszahl gewichtete Mittel der Stundenwerte, also Naeherungen.
     */
    public Statistics calculateStatisticsFromRollups(String type, Instant from, Instant to) throws SQLException
    {
        String sql = """
                SELECT SUM(sample_count) AS samples, SUM(ok_count) AS oks,
                       SUM(avg_ms * ok_count) AS weighted_avg, SUM(p95_ms * ok_count) AS weighted_p95,
                       SUM(jitter_ms * ok_count) AS weighted_jitter, MAX(max_ms) AS max_ms
                FROM measurement_hourly
                WHERE type = ? AND hour_start >= ? AND hour_start < ?
                """;
        return readWithFallback(c ->
        {
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setString(1, type);
            pstmt.setTimestamp(2, Timestamp.from(from));
            pstmt.setTimestamp(3, Timestamp.from(to));
            ResultSet rs = pstmt.executeQuery();
            if (!rs.next() || rs.getLong("samples") == 0)
                {
                return new Statistics(0, 0, 0, 0, 0);
                }
            long samples = rs.getLong("samples");
            long oks = rs.getLong("oks");
            double loss = samples > 0 ? (samples - oks) * 100.0 / samples : 0;
            if (oks == 0)
                {
                return new Statistics(0, 0, 0, loss, 0);
                }
            return new Statistics(rs.getDouble("weighted_avg") / oks, rs.getDouble("weighted_p95") / oks,
                    rs.getDouble("max_ms"), loss, rs.getDouble("weighted_jitter") / oks);
            }
        });
    }

    /** Fehlgeschlagene Messungen eines Typs im Zeitraum (bleiben durch die Aufbewahrung immer erhalten). */
    public List<Measurement> findFailures(String type, Instant from, Instant to) throws SQLException
    {
        String sql = """
                SELECT timestamp, target, latency_ms, success, type,
                       local_ipv4, local_ipv6, external_ipv4, external_ipv6, host_hash, excluded
                FROM measurements
                WHERE type = ? AND timestamp >= ? AND timestamp < ? AND success = FALSE
                ORDER BY timestamp ASC
                """;
        return readWithFallback(c ->
        {
        List<Measurement> results = new ArrayList<>();
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setString(1, type);
            pstmt.setTimestamp(2, Timestamp.from(from));
            pstmt.setTimestamp(3, Timestamp.from(to));
            ResultSet rs = pstmt.executeQuery();
            while (rs.next())
                {
                results.add(readMeasurement(rs));
                }
            }
        return results;
        });
    }

    /** Erste erfolgreiche Messung eines Typs nach {@code after} (Ende eines Ausfalls), oder null. */
    public Measurement findFirstSuccessAfter(String type, Instant after, Instant limit) throws SQLException
    {
        String sql = """
                SELECT timestamp, target, latency_ms, success, type,
                       local_ipv4, local_ipv6, external_ipv4, external_ipv6, host_hash, excluded
                FROM measurements
                WHERE type = ? AND timestamp > ? AND timestamp < ? AND success = TRUE
                ORDER BY timestamp ASC
                LIMIT 1
                """;
        return readWithFallback(c ->
        {
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setString(1, type);
            pstmt.setTimestamp(2, Timestamp.from(after));
            pstmt.setTimestamp(3, Timestamp.from(limit));
            ResultSet rs = pstmt.executeQuery();
            return rs.next() ? readMeasurement(rs) : null;
            }
        });
    }

    /**
     * Aufbewahrungsregel fuer einen Tag [day, next): loescht erfolgreiche, nicht ausgenommene
     * Rohmessungen (keine Wartungs-Marker), deren Vorgaenger (gleicher Typ und Ziel) ebenfalls
     * erfolgreich war. Die erste erfolgreiche Messung nach einem Fehlschlag bleibt damit als
     * Ausfall-Ende erhalten. Das Fenster fuer den Vorgaenger-Vergleich beginnt einen Tag
     * frueher, damit die Tagesgrenze keinen Vorgaenger verschluckt.
     *
     * @return Anzahl geloeschter Zeilen (Primary)
     */
    public int deleteAggregatedOkRows(Instant day, Instant next) throws SQLException
    {
        String sql = """
                DELETE FROM measurements WHERE id IN (
                    SELECT id FROM (
                        SELECT id, timestamp, success, excluded, type,
                               LAG(success) OVER (PARTITION BY type, target ORDER BY timestamp) AS prev_success
                        FROM measurements
                        WHERE timestamp >= ? AND timestamp < ?
                    ) t
                    WHERE t.timestamp >= ?
                      AND t.success = TRUE
                      AND t.excluded = FALSE
                      AND t.type <> 'MAINTENANCE'
                      AND t.prev_success = TRUE
                )
                """;
        int[] affected = {0};
        writeOnBoth(c ->
        {
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setTimestamp(1, Timestamp.from(day.minus(1, java.time.temporal.ChronoUnit.DAYS)));
            pstmt.setTimestamp(2, Timestamp.from(next));
            pstmt.setTimestamp(3, Timestamp.from(day));
            int n = pstmt.executeUpdate();
            if (c == primary) affected[0] = n;
            }
        });
        return affected[0];
    }

    public Instant getRollupWatermark() throws SQLException
    {
        return parseInstant(getState(RollupService.STATE_LAST_HOUR));
    }

    public void setRollupWatermark(Instant hourStart) throws SQLException
    {
        setState(RollupService.STATE_LAST_HOUR, hourStart.toString());
    }

    public Instant getRetentionDoneUntil() throws SQLException
    {
        return parseInstant(getState(RollupService.STATE_RETENTION_DONE_UNTIL));
    }

    public void setRetentionDoneUntil(Instant until) throws SQLException
    {
        setState(RollupService.STATE_RETENTION_DONE_UNTIL, until.toString());
    }

    private static Instant parseInstant(String value)
    {
        if (value == null || value.isBlank()) return null;
        try
            {
            return Instant.parse(value);
            } catch (Exception e)
            {
            return null;
            }
    }

    public String getState(String key) throws SQLException
    {
        return readWithFallback(c ->
        {
        try (PreparedStatement pstmt = c.prepareStatement("SELECT state_value FROM rollup_state WHERE state_key = ?"))
            {
            pstmt.setString(1, key);
            ResultSet rs = pstmt.executeQuery();
            return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    public void setState(String key, String value) throws SQLException
    {
        writeOnBoth(c ->
        {
        try (PreparedStatement pstmt = c.prepareStatement(
                "MERGE INTO rollup_state (state_key, state_value) KEY (state_key) VALUES (?, ?)"))
            {
            pstmt.setString(1, key);
            pstmt.setString(2, value);
            pstmt.executeUpdate();
            }
        });
    }

    // ========================================================================
    //  Dienst-Erreichbarkeit (service_checks)
    // ========================================================================

    /** Speichert eine Erreichbarkeits-Pruefung (Twin-Spiegelung wie alle Schreibvorgaenge). */
    public void saveServiceCheck(ServiceCheck check) throws SQLException
    {
        String sql = """
                INSERT INTO service_checks
                (timestamp, service_id, verdict, method, http_status, resolved_ip, latency_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        writeOnBoth(c ->
        {
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setTimestamp(1, Timestamp.from(check.getTimestamp()));
            pstmt.setString(2, check.getServiceId());
            pstmt.setString(3, check.getVerdict());
            pstmt.setString(4, check.getMethod());
            pstmt.setInt(5, check.getHttpStatus());
            pstmt.setString(6, check.getResolvedIp());
            pstmt.setDouble(7, check.getLatencyMs());
            pstmt.executeUpdate();
            }
        });
    }

    /** Pruefungen eines Dienstes ab einem Zeitpunkt, chronologisch (fuer die Episoden-Bildung). */
    public List<ServiceCheck> findServiceChecksSince(String serviceId, Instant since) throws SQLException
    {
        String sql = """
                SELECT timestamp, service_id, verdict, method, http_status, resolved_ip, latency_ms
                FROM service_checks
                WHERE service_id = ? AND timestamp >= ?
                ORDER BY timestamp ASC
                """;

        return readWithFallback(c ->
        {
        List<ServiceCheck> results = new ArrayList<>();
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setString(1, serviceId);
            pstmt.setTimestamp(2, Timestamp.from(since));
            ResultSet rs = pstmt.executeQuery();
            while (rs.next())
                {
                results.add(readServiceCheck(rs));
                }
            }
        return results;
        });
    }

    /** Jeweils die neueste Pruefung pro Dienst (fuer die "aktueller Status"-Anzeige). */
    public List<ServiceCheck> findLatestServiceChecks() throws SQLException
    {
        String sql = """
                SELECT timestamp, service_id, verdict, method, http_status, resolved_ip, latency_ms
                FROM (
                    SELECT timestamp, service_id, verdict, method, http_status, resolved_ip, latency_ms,
                           ROW_NUMBER() OVER (PARTITION BY service_id ORDER BY timestamp DESC) AS rn
                    FROM service_checks
                ) latest
                WHERE rn = 1
                ORDER BY service_id ASC
                """;

        return readWithFallback(c ->
        {
        List<ServiceCheck> results = new ArrayList<>();
        try (Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery(sql))
            {
            while (rs.next())
                {
                results.add(readServiceCheck(rs));
                }
            }
        return results;
        });
    }

    private ServiceCheck readServiceCheck(ResultSet rs) throws SQLException
    {
        return new ServiceCheck(
                rs.getTimestamp("timestamp").toInstant(),
                rs.getString("service_id"),
                rs.getString("verdict"),
                rs.getString("method"),
                rs.getInt("http_status"),
                rs.getString("resolved_ip"),
                rs.getDouble("latency_ms")
        );
    }

    // ========================================================================
    //  Cleanup
    // ========================================================================

    public void close() throws SQLException
    {
        SQLException firstError = null;
        try
            {
            if (primary != null && !primary.isClosed()) primary.close();
            } catch (SQLException e)
            {
            firstError = e;
            }
        try
            {
            if (shadow != null && !shadow.isClosed()) shadow.close();
            } catch (SQLException e)
            {
            if (firstError == null) firstError = e;
            }
        if (firstError != null) throw firstError;
    }
}
