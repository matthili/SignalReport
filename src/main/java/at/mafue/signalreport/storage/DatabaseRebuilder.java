package at.mafue.signalreport.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Neuaufbau der Twin-Datenbank aus den vorhandenen Dateien.
 * <p>
 * Hintergrund: Bis Version 2.0.1 wuchsen die H2-Dateien durch {@code WRITE_DELAY=0} ohne
 * Kompaktierung auf ein Vielfaches der Nutzdaten (auf dem Referenzsystem 71 GB fuer
 * ~440 MB Daten), und eine einzelne unlesbare Seite in der Primary machte Jahresbericht
 * und Komplett-Export unmoeglich. Dieser Neuaufbau liest Primary und Shadow <b>nur
 * lesend</b> (laeuft der Dienst noch, scheitert das an der exklusiven H2-Dateisperre),
 * vereinigt beide Datenbestaende Tag fuer Tag und schreibt sie in eine frische, kompakte
 * Datei mit dem aktuellen Schema. Scheitert ein Tag in einer Quelle, wird stundenweise
 * gelesen; unlesbare Stunden werden im Bericht ausgewiesen und aus der anderen Quelle
 * abgedeckt.
 * <p>
 * Zum Schluss werden die alten Dateien nach {@code quarantine/rebuild_<Zeit>/} verschoben
 * (nie geloescht), die neue Datei als Primary eingesetzt und als Shadow kopiert.
 * <p>
 * Aufrufwege: {@code java -jar signalreport.jar rebuild-db} (manuell, Dienst gestoppt) oder
 * automatisch beim Start, wenn die Marker-Datei {@code <pfad>.REBUILD_REQUIRED} existiert;
 * die setzt {@link H2MeasurementRepository}, sobald ein Lesezugriff im Betrieb an einer
 * Korruption der Primary scheitert.
 */
public class DatabaseRebuilder
{
    private static final Logger logger = LoggerFactory.getLogger(DatabaseRebuilder.class);

    public static final String MARKER_SUFFIX = ".REBUILD_REQUIRED";
    private static final String READ_ONLY_SUFFIX =
            ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE;TRACE_LEVEL_FILE=0;DB_CLOSE_ON_EXIT=FALSE";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter RANGE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private static final int BATCH_SIZE = 1000;

    private final String dbPath;
    private final Consumer<String> progress;

    /** Fehler, der den Neuaufbau als Ganzes verhindert (z. B. keine lesbare Quelle). */
    public static class RebuildException extends Exception
    {
        public RebuildException(String message)
        {
            super(message);
        }

        public RebuildException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    /** Eine Quelle mit ihrer read-only Connection und dem Berichts-Eintrag. */
    protected static final class SourceDb
    {
        final String name;
        final String jdbcBase;
        final RebuildReport.SourceSummary summary;
        Connection connection;
        boolean hasExcludedColumn;

        SourceDb(String name, String jdbcBase, Path file)
        {
            this.name = name;
            this.jdbcBase = jdbcBase;
            this.summary = new RebuildReport.SourceSummary(name, file);
        }
    }

    /** Eine Messzeile, wie sie in der Zieldatenbank landet (ohne technische id). */
    record MeasurementRow(Instant timestamp, String target, double latencyMs, boolean success, String type,
                          String localIPv4, String localIPv6, String externalIPv4, String externalIPv6,
                          String hostHash, boolean excluded)
    {
        RowKey key()
        {
            return new RowKey(timestamp, target, type, hostHash);
        }

        MeasurementRow withExcluded(boolean ex)
        {
            return new MeasurementRow(timestamp, target, latencyMs, success, type,
                    localIPv4, localIPv6, externalIPv4, externalIPv6, hostHash, ex);
        }
    }

    /** Fachlicher Schluessel einer Messung: identisch in Primary und Shadow. */
    record RowKey(Instant timestamp, String target, String type, String hostHash)
    {
    }

    public DatabaseRebuilder(String dbPath, Consumer<String> progress)
    {
        this.dbPath = dbPath;
        this.progress = progress != null ? progress : s -> {};
    }

    // ========================================================================
    //  Marker-Datei (Neuaufbau beim naechsten Start)
    // ========================================================================

    public static Path markerFile(String dbPath)
    {
        return Paths.get(dbPath + MARKER_SUFFIX);
    }

    public static boolean isRebuildRequested(String dbPath)
    {
        return Files.exists(markerFile(dbPath));
    }

    /** Merkt einen Neuaufbau fuer den naechsten Start vor (haengt den Grund an die Marker-Datei an). */
    public static void requestRebuild(String dbPath, String reason)
    {
        try
            {
            String line = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + " " + reason
                    + System.lineSeparator();
            Files.writeString(markerFile(dbPath), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e)
            {
            logger.error("Marker-Datei fuer den Neuaufbau konnte nicht geschrieben werden: {}", e.getMessage());
            }
    }

    public static void clearRequest(String dbPath)
    {
        try
            {
            Files.deleteIfExists(markerFile(dbPath));
            } catch (IOException e)
            {
            logger.warn("Marker-Datei konnte nicht entfernt werden: {}", e.getMessage());
            }
    }

    /**
     * Nach einem fehlgeschlagenen automatischen Neuaufbau: Marker entfernen (sonst wuerde jeder
     * Start minutenlang dasselbe versuchen) und den Grund in {@code <pfad>.REBUILD_FAILED} ablegen.
     */
    public static void markFailed(String dbPath, String reason)
    {
        try
            {
            String line = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + " " + reason
                    + System.lineSeparator();
            Files.writeString(Paths.get(dbPath + ".REBUILD_FAILED"), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e)
            {
            logger.warn("REBUILD_FAILED-Datei konnte nicht geschrieben werden: {}", e.getMessage());
            }
        clearRequest(dbPath);
    }

    // ========================================================================
    //  Neuaufbau
    // ========================================================================

    /**
     * Fuehrt den Neuaufbau aus.
     *
     * @param swap true: alte Dateien in Quarantaene verschieben und die neue Datei als
     *             Primary (+ Shadow-Kopie) einsetzen; false: nur die neue Datei
     *             {@code <pfad>-rebuild.mv.db} erzeugen und die alten unveraendert lassen
     */
    public RebuildReport rebuild(boolean swap) throws RebuildException
    {
        Instant start = Instant.now();
        String stamp = LocalDateTime.now().format(STAMP);
        RebuildReport report = new RebuildReport();

        Path primaryFile = Paths.get(dbPath + ".mv.db");
        Path shadowFile = Paths.get(dbPath + "-shadow.mv.db");
        String targetBase = dbPath + "-rebuild";
        Path targetFile = Paths.get(targetBase + ".mv.db");
        deleteQuietly(targetFile);
        deleteQuietly(Paths.get(targetBase + ".trace.db"));

        List<SourceDb> sources = new ArrayList<>();
        try
            {
            openSource(sources, report, new SourceDb("Primary", dbPath, primaryFile));
            openSource(sources, report, new SourceDb("Shadow", dbPath + "-shadow", shadowFile));
            if (sources.isEmpty())
                {
                throw new RebuildException("Keine Datenbank-Datei lesbar: " + report.sourceErrors());
                }

            Instant min = null, max = null;
            for (SourceDb src : sources)
                {
                inventory(src);
                RebuildReport.SourceSummary s = src.summary;
                if (s.minTimestamp != null && (min == null || s.minTimestamp.isBefore(min))) min = s.minTimestamp;
                if (s.maxTimestamp != null && (max == null || s.maxTimestamp.isAfter(max))) max = s.maxTimestamp;
                progress.accept(String.format("%s: %s, %s Messzeilen laut Index", src.name,
                        RebuildReport.formatBytes(s.sizeBytes),
                        s.inventoryRows >= 0 ? String.format("%,d", s.inventoryRows) : "unbekannt viele"));
                }

            Connection target = DriverManager.getConnection("jdbc:h2:" + targetBase + ";DB_CLOSE_ON_EXIT=FALSE", "sa", "");
            try
                {
                H2MeasurementRepository.createSchema(target);
                report.hosts = copyHosts(sources, target);
                report.ipChanges = copyIpChanges(sources, target);
                report.serviceChecks = copyServiceChecks(sources, target);
                report.unionMeasurements = copyMeasurements(sources, target, min, max);

                long check = countRows(target, "measurements");
                if (check != report.unionMeasurements)
                    {
                    throw new RebuildException("Zielzaehlung weicht ab: " + check + " statt " + report.unionMeasurements);
                    }
                report.targetSizeBeforeCompact = sizeOf(targetFile);
                progress.accept("Kompaktiere die neue Datenbank...");
                shutdownCompact(target);
                } finally
                {
                closeQuietly(target);
                }
            report.targetSizeAfterCompact = sizeOf(targetFile);
            } catch (SQLException e)
            {
            throw new RebuildException("Neuaufbau abgebrochen: " + e.getMessage(), e);
            } finally
            {
            for (SourceDb src : sources) closeQuietly(src.connection);
            }

        if (swap)
            {
            swapFiles(primaryFile, shadowFile, targetFile, stamp, report);
            } else
            {
            report.newFile = targetFile.toAbsolutePath();
            }
        clearRequest(dbPath);

        report.duration = Duration.between(start, Instant.now());
        writeReportFile(primaryFile, stamp, report);
        progress.accept(String.format("Fertig in %d min %d s: %,d Messzeilen, neue Datei %s",
                report.duration.toMinutes(), report.duration.toSecondsPart(), report.unionMeasurements,
                RebuildReport.formatBytes(report.targetSizeAfterCompact)));
        return report;
    }

    private void openSource(List<SourceDb> sources, RebuildReport report, SourceDb src)
    {
        RebuildReport.SourceSummary s = src.summary;
        report.sources.add(s);
        s.sizeBytes = sizeOf(s.file);
        if (!Files.exists(s.file))
            {
            s.openable = false;
            s.openError = "Datei nicht vorhanden";
            return;
            }
        try
            {
            src.connection = DriverManager.getConnection("jdbc:h2:" + src.jdbcBase + READ_ONLY_SUFFIX, "sa", "");
            src.hasExcludedColumn = columnExists(src.connection, "MEASUREMENTS", "EXCLUDED");
            s.openable = true;
            sources.add(src);
            } catch (SQLException e)
            {
            s.openable = false;
            s.openError = e.getMessage();
            progress.accept(src.name + " nicht lesbar: " + shortMessage(e));
            }
    }

    /** Zeilenzahl und Zeitraum ueber den Index (schnell, liest keine Datenseiten). */
    private void inventory(SourceDb src)
    {
        try (Statement st = src.connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*), MIN(timestamp), MAX(timestamp) FROM measurements"))
            {
            if (rs.next())
                {
                src.summary.inventoryRows = rs.getLong(1);
                Timestamp min = rs.getTimestamp(2);
                Timestamp max = rs.getTimestamp(3);
                src.summary.minTimestamp = min != null ? min.toInstant() : null;
                src.summary.maxTimestamp = max != null ? max.toInstant() : null;
                }
            } catch (SQLException e)
            {
            src.summary.tableErrors.add("Inventar (COUNT/MIN/MAX) nicht ermittelbar: " + shortMessage(e));
            }
    }

    // ------------------------------------------------------------------------
    //  Kleine Tabellen: Vereinigung ueber fachliche Schluessel
    // ------------------------------------------------------------------------

    private long copyHosts(List<SourceDb> sources, Connection target) throws SQLException
    {
        // host_hash -> (hostname, os, first_seen, last_seen); fruehestes first_seen, spaetestes last_seen
        Map<String, Object[]> merged = new LinkedHashMap<>();
        for (SourceDb src : sources)
            {
            if (!tableExists(src.connection, "HOSTS")) continue;
            try (Statement st = src.connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT host_hash, hostname, operating_system, first_seen, last_seen FROM hosts"))
                {
                while (rs.next())
                    {
                    String hash = rs.getString(1);
                    Object[] row = {hash, rs.getString(2), rs.getString(3), rs.getTimestamp(4), rs.getTimestamp(5)};
                    merged.merge(hash, row, (a, b) ->
                    {
                    Timestamp firstA = (Timestamp) a[3], firstB = (Timestamp) b[3];
                    Timestamp lastA = (Timestamp) a[4], lastB = (Timestamp) b[4];
                    Object[] r = a.clone();
                    if (firstB != null && (firstA == null || firstB.before(firstA))) r[3] = firstB;
                    if (lastB != null && (lastA == null || lastB.after(lastA)))
                        {
                        r[4] = lastB;
                        r[1] = b[1];
                        r[2] = b[2];
                        }
                    return r;
                    });
                    }
                } catch (SQLException e)
                {
                src.summary.tableErrors.add("hosts nicht lesbar: " + shortMessage(e));
                }
            }
        try (PreparedStatement ps = target.prepareStatement(
                "INSERT INTO hosts (host_hash, hostname, operating_system, first_seen, last_seen) VALUES (?, ?, ?, ?, ?)"))
            {
            for (Object[] r : merged.values())
                {
                ps.setString(1, (String) r[0]);
                ps.setString(2, (String) r[1]);
                ps.setString(3, (String) r[2]);
                ps.setTimestamp(4, (Timestamp) r[3]);
                ps.setTimestamp(5, (Timestamp) r[4]);
                ps.executeUpdate();
                }
            }
        return merged.size();
    }

    private long copyIpChanges(List<SourceDb> sources, Connection target) throws SQLException
    {
        Map<String, Object[]> merged = new LinkedHashMap<>();
        for (SourceDb src : sources)
            {
            if (!tableExists(src.connection, "IP_CHANGES")) continue;
            try (Statement st = src.connection.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT timestamp, old_ip, new_ip, change_type, host_hash FROM ip_changes ORDER BY timestamp"))
                {
                while (rs.next())
                    {
                    Object[] row = {rs.getTimestamp(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)};
                    String key = row[0] + "|" + row[2] + "|" + row[3] + "|" + row[4];
                    merged.putIfAbsent(key, row);
                    }
                } catch (SQLException e)
                {
                src.summary.tableErrors.add("ip_changes nicht lesbar: " + shortMessage(e));
                }
            }
        List<Object[]> rows = new ArrayList<>(merged.values());
        rows.sort(Comparator.comparing(r -> (Timestamp) r[0]));
        try (PreparedStatement ps = target.prepareStatement(
                "INSERT INTO ip_changes (timestamp, old_ip, new_ip, change_type, host_hash) VALUES (?, ?, ?, ?, ?)"))
            {
            for (Object[] r : rows)
                {
                ps.setTimestamp(1, (Timestamp) r[0]);
                ps.setString(2, (String) r[1]);
                ps.setString(3, (String) r[2]);
                ps.setString(4, (String) r[3]);
                ps.setString(5, (String) r[4]);
                ps.executeUpdate();
                }
            }
        return rows.size();
    }

    private long copyServiceChecks(List<SourceDb> sources, Connection target) throws SQLException
    {
        Map<String, Object[]> merged = new LinkedHashMap<>();
        for (SourceDb src : sources)
            {
            if (!tableExists(src.connection, "SERVICE_CHECKS")) continue;
            try (Statement st = src.connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT timestamp, service_id, verdict, method, http_status, resolved_ip, latency_ms "
                         + "FROM service_checks ORDER BY timestamp"))
                {
                while (rs.next())
                    {
                    Object[] row = {rs.getTimestamp(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getInt(5), rs.getString(6), rs.getDouble(7)};
                    merged.putIfAbsent(row[0] + "|" + row[1], row);
                    }
                } catch (SQLException e)
                {
                src.summary.tableErrors.add("service_checks nicht lesbar: " + shortMessage(e));
                }
            }
        List<Object[]> rows = new ArrayList<>(merged.values());
        rows.sort(Comparator.comparing(r -> (Timestamp) r[0]));
        try (PreparedStatement ps = target.prepareStatement(
                "INSERT INTO service_checks (timestamp, service_id, verdict, method, http_status, resolved_ip, latency_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)"))
            {
            for (Object[] r : rows)
                {
                ps.setTimestamp(1, (Timestamp) r[0]);
                ps.setString(2, (String) r[1]);
                ps.setString(3, (String) r[2]);
                ps.setString(4, (String) r[3]);
                ps.setInt(5, (Integer) r[4]);
                ps.setString(6, (String) r[5]);
                ps.setDouble(7, (Double) r[6]);
                ps.executeUpdate();
                }
            }
        return rows.size();
    }

    // ------------------------------------------------------------------------
    //  Messungen: Tag fuer Tag, Vereinigung beider Quellen, eine Transaktion pro Tag
    // ------------------------------------------------------------------------

    private long copyMeasurements(List<SourceDb> sources, Connection target, Instant min, Instant max) throws SQLException
    {
        if (min == null || max == null)
            {
            progress.accept("Keine Messungen in den Quellen gefunden.");
            return 0;
            }
        String insert = """
                INSERT INTO measurements
                (timestamp, target, latency_ms, success, type, local_ipv4, local_ipv6,
                 external_ipv4, external_ipv6, host_hash, excluded)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        Instant day = min.truncatedTo(ChronoUnit.DAYS);
        Instant end = max.plusSeconds(1);
        long total = 0;
        int dayCount = 0;
        target.setAutoCommit(false);
        try (PreparedStatement ps = target.prepareStatement(insert))
            {
            while (day.isBefore(end))
                {
                Instant next = day.plus(1, ChronoUnit.DAYS);
                Map<RowKey, MeasurementRow> merged = new LinkedHashMap<>();
                for (SourceDb src : sources)
                    {
                    for (MeasurementRow row : readRangeWithFallback(src, day, next))
                        {
                        merged.merge(row.key(), row, (a, b) -> (a.excluded() || b.excluded()) ? a.withExcluded(true) : a);
                        }
                    }
                List<MeasurementRow> rows = new ArrayList<>(merged.values());
                rows.sort(Comparator.comparing(MeasurementRow::timestamp).thenComparing(MeasurementRow::type));
                int n = 0;
                for (MeasurementRow r : rows)
                    {
                    ps.setTimestamp(1, Timestamp.from(r.timestamp()));
                    ps.setString(2, r.target());
                    ps.setDouble(3, r.latencyMs());
                    ps.setBoolean(4, r.success());
                    ps.setString(5, r.type());
                    ps.setString(6, r.localIPv4());
                    ps.setString(7, r.localIPv6());
                    ps.setString(8, r.externalIPv4());
                    ps.setString(9, r.externalIPv6());
                    ps.setString(10, r.hostHash());
                    ps.setBoolean(11, r.excluded());
                    ps.addBatch();
                    if (++n % BATCH_SIZE == 0) ps.executeBatch();
                    }
                ps.executeBatch();
                target.commit();
                total += rows.size();
                dayCount++;
                if (dayCount % 10 == 0)
                    {
                    progress.accept(String.format("%s: %,d Zeilen (bisher %,d)",
                            day.atZone(ZoneOffset.UTC).toLocalDate(), rows.size(), total));
                    }
                day = next;
                }
            } finally
            {
            target.setAutoCommit(true);
            }
        return total;
    }

    /**
     * Liest einen Zeitraum aus einer Quelle. Scheitert der Zeitraum am Stueck (typisch:
     * eine unlesbare Seite), wird stundenweise gelesen; unlesbare Stunden werden im
     * Bericht vermerkt und uebersprungen (die andere Quelle liefert sie in der Regel).
     */
    private List<MeasurementRow> readRangeWithFallback(SourceDb src, Instant from, Instant to)
    {
        try
            {
            List<MeasurementRow> rows = readMeasurements(src, from, to);
            src.summary.readRows += rows.size();
            return rows;
            } catch (SQLException e)
            {
            progress.accept(String.format("%s: Zeitraum %s bis %s nicht am Stueck lesbar (%s), lese stundenweise",
                    src.name, RANGE.format(from), RANGE.format(to), shortMessage(e)));
            }
        List<MeasurementRow> result = new ArrayList<>();
        Instant h = from;
        while (h.isBefore(to))
            {
            Instant hn = h.plus(1, ChronoUnit.HOURS);
            if (hn.isAfter(to)) hn = to;
            try
                {
                List<MeasurementRow> rows = readMeasurements(src, h, hn);
                src.summary.readRows += rows.size();
                result.addAll(rows);
                } catch (SQLException ex)
                {
                String range = RANGE.format(h) + " bis " + RANGE.format(hn);
                src.summary.unreadableRanges.add(range + " (" + shortMessage(ex) + ")");
                progress.accept(String.format("%s: Stunde %s nicht lesbar, Daten der anderen Quelle werden verwendet",
                        src.name, range));
                }
            h = hn;
            }
        return result;
    }

    /** Liest die Messzeilen eines Zeitraums [from, to) aus einer Quelle. Protected fuer Tests. */
    protected List<MeasurementRow> readMeasurements(SourceDb src, Instant from, Instant to) throws SQLException
    {
        String excludedExpr = src.hasExcludedColumn ? "excluded" : "FALSE";
        String sql = "SELECT timestamp, target, latency_ms, success, type, local_ipv4, local_ipv6, "
                + "external_ipv4, external_ipv6, host_hash, " + excludedExpr
                + " FROM measurements WHERE timestamp >= ? AND timestamp < ?";
        List<MeasurementRow> rows = new ArrayList<>();
        try (PreparedStatement ps = src.connection.prepareStatement(sql))
            {
            ps.setTimestamp(1, Timestamp.from(from));
            ps.setTimestamp(2, Timestamp.from(to));
            try (ResultSet rs = ps.executeQuery())
                {
                while (rs.next())
                    {
                    rows.add(new MeasurementRow(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getDouble(3),
                            rs.getBoolean(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            rs.getString(9), rs.getString(10), rs.getBoolean(11)));
                    }
                }
            }
        return rows;
    }

    // ------------------------------------------------------------------------
    //  Dateien tauschen, Bericht schreiben
    // ------------------------------------------------------------------------

    private void swapFiles(Path primaryFile, Path shadowFile, Path targetFile, String stamp, RebuildReport report)
            throws RebuildException
    {
        Path dir = primaryFile.toAbsolutePath().getParent();
        Path quarantine = dir.resolve("quarantine").resolve("rebuild_" + stamp);
        try
            {
            Files.createDirectories(quarantine);
            moveIfExists(primaryFile, quarantine);
            moveIfExists(shadowFile, quarantine);
            moveIfExists(Paths.get(dbPath + ".trace.db"), quarantine);
            moveIfExists(Paths.get(dbPath + "-shadow.trace.db"), quarantine);
            Files.move(targetFile, primaryFile);
            Files.copy(primaryFile, shadowFile, StandardCopyOption.REPLACE_EXISTING);
            report.quarantineDir = quarantine;
            report.swapped = true;
            progress.accept("Alte Dateien verschoben nach " + quarantine);
            } catch (IOException e)
            {
            throw new RebuildException("Dateitausch fehlgeschlagen: " + e.getMessage()
                    + " (neue Datei liegt unter " + targetFile.toAbsolutePath() + ")", e);
            }
    }

    private void writeReportFile(Path primaryFile, String stamp, RebuildReport report)
    {
        Path dir = primaryFile.toAbsolutePath().getParent();
        String baseName = primaryFile.getFileName().toString().replace(".mv.db", "");
        Path file = dir.resolve(baseName + "_rebuild-report_" + stamp + ".txt");
        try
            {
            report.reportFile = file;
            Files.writeString(file, report.toText(), StandardCharsets.UTF_8);
            } catch (IOException e)
            {
            report.reportFile = null;
            logger.warn("Bericht konnte nicht geschrieben werden: {}", e.getMessage());
            }
    }

    // ------------------------------------------------------------------------
    //  Hilfsfunktionen
    // ------------------------------------------------------------------------

    private static void shutdownCompact(Connection c)
    {
        try (Statement st = c.createStatement())
            {
            st.execute("SHUTDOWN COMPACT");
            } catch (SQLException e)
            {
            // SHUTDOWN schliesst die Verbindung selbst; Folgefehler sind unerheblich
            logger.debug("SHUTDOWN COMPACT meldete: {}", e.getMessage());
            }
    }

    private static long countRows(Connection c, String table) throws SQLException
    {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table))
            {
            return rs.next() ? rs.getLong(1) : -1;
            }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException
    {
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getTables(null, null, table, null))
            {
            return rs.next();
            }
    }

    private static boolean columnExists(Connection c, String table, String column) throws SQLException
    {
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getColumns(null, null, table, column))
            {
            return rs.next();
            }
    }

    private static void moveIfExists(Path file, Path targetDir) throws IOException
    {
        if (Files.exists(file))
            {
            Files.move(file, targetDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
    }

    private static void deleteQuietly(Path file)
    {
        try
            {
            Files.deleteIfExists(file);
            } catch (IOException ignored)
            {
            }
    }

    private static void closeQuietly(Connection c)
    {
        if (c == null) return;
        try
            {
            c.close();
            } catch (SQLException ignored)
            {
            }
    }

    private static long sizeOf(Path file)
    {
        try
            {
            return Files.exists(file) ? Files.size(file) : -1;
            } catch (IOException e)
            {
            return -1;
            }
    }

    private static String shortMessage(Exception e)
    {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        m = m.replace('\n', ' ');
        return m.length() > 160 ? m.substring(0, 160) + "..." : m;
    }
}
