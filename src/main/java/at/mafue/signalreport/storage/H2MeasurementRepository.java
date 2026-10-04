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
 * 37,7 KB Dateiwachstum pro Messzeile statt 92,6 Byte Nutzdaten, siehe
 * docs/notes/2026-10-04_Datenbank-Analyse.md.
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

        try (Statement stmt = c.createStatement())
            {
            stmt.execute(measurementsTable);
            stmt.execute(hostsTable);
            stmt.execute(ipChangesTable);
            stmt.execute(addExcluded);
            stmt.execute(serviceChecksTable);
            stmt.execute(indexTimestamp);
            stmt.execute(indexTypeTimestamp);
            stmt.execute(indexHostHash);
            stmt.execute(indexIpChangesTimestamp);
            stmt.execute(indexServiceChecks);
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

    public List<HourlyAverage> calculateHourlyAverages(String type, int days) throws SQLException
    {
        String sql = """
                SELECT
                    EXTRACT(HOUR FROM timestamp) AS hour_of_day,
                    AVG(latency_ms) AS avg_latency,
                    COUNT(*) AS measurement_count
                FROM measurements
                WHERE type = ?
                  AND success = true
                  AND timestamp >= DATEADD('DAY', ?, CURRENT_TIMESTAMP)
                GROUP BY EXTRACT(HOUR FROM timestamp)
                ORDER BY hour_of_day
                """;

        return readWithFallback(c ->
        {
        List<HourlyAverage> results = new ArrayList<>();
        try (PreparedStatement pstmt = c.prepareStatement(sql))
            {
            pstmt.setString(1, type);
            pstmt.setInt(2, -days);
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
