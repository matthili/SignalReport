package at.mafue.signalreport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class H2MeasurementRepository
{
    private final String jdbcUrl;
    private Connection connection;

    public H2MeasurementRepository(String dbPath) throws SQLException
    {
        this.jdbcUrl = "jdbc:h2:" + dbPath + ";DB_CLOSE_ON_EXIT=FALSE";

        try
            {
            Class.forName("org.h2.Driver");
            Files.createDirectories(Paths.get("./data"));
            } catch (ClassNotFoundException | IOException e)
            {
            throw new SQLException("Fehler bei der Datenbank-Initialisierung", e);
            }

        this.connection = DriverManager.getConnection(jdbcUrl, "sa", "");
        createTables();
    }

    private void createTables() throws SQLException
    {
        // Haupttabelle für Messungen
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

        // Tabelle für Host-Informationen
        String hostsTable = """
                CREATE TABLE IF NOT EXISTS hosts (
                    host_hash VARCHAR(32) PRIMARY KEY,
                    hostname VARCHAR(255) NOT NULL,
                    operating_system VARCHAR(255) NOT NULL,
                    first_seen TIMESTAMP NOT NULL,
                    last_seen TIMESTAMP NOT NULL
                )
                """;

        // 🔑 NEU: Tabelle für IP-Änderungen (Tracking)
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

        // Indizes für häufige Abfragen (timestamp, type, host_hash)
        String indexTimestamp = "CREATE INDEX IF NOT EXISTS idx_measurements_timestamp ON measurements(timestamp)";
        String indexType = "CREATE INDEX IF NOT EXISTS idx_measurements_type ON measurements(type)";
        String indexTypeTimestamp = "CREATE INDEX IF NOT EXISTS idx_measurements_type_timestamp ON measurements(type, timestamp)";
        String indexHostHash = "CREATE INDEX IF NOT EXISTS idx_measurements_host_hash ON measurements(host_hash)";
        String indexIpChangesTimestamp = "CREATE INDEX IF NOT EXISTS idx_ip_changes_timestamp ON ip_changes(timestamp)";

        try (Statement stmt = connection.createStatement())
            {
            stmt.execute(measurementsTable);
            stmt.execute(hostsTable);
            stmt.execute(ipChangesTable);
            stmt.execute(indexTimestamp);
            stmt.execute(indexType);
            stmt.execute(indexTypeTimestamp);
            stmt.execute(indexHostHash);
            stmt.execute(indexIpChangesTimestamp);
            }
    }

    // Host-Information speichern/aktualisieren
    public void registerHost(String hostHash, String hostname, String os) throws SQLException
    {
        String sql = """
                MERGE INTO hosts (host_hash, hostname, operating_system, first_seen, last_seen)
                KEY (host_hash)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql))
            {
            pstmt.setString(1, hostHash);
            pstmt.setString(2, hostname);
            pstmt.setString(3, os);
            pstmt.executeUpdate();
            }
    }

    //  IP-Änderung erkennen und speichern
    public void trackIpChange(String currentIp, String hostHash) throws SQLException
    {
        if (currentIp == null || currentIp.equals("unknown"))
            {
            return; // Kein Tracking bei unbekannter IP
            }

        // Letzte bekannte IP holen
        String lastIp = getLastKnownIp(hostHash);

        // IP hat sich geändert?
        if (lastIp == null)
            {
            // Erste IP für diesen Host – Initialisierung
            recordIpChange(null, currentIp, "INITIAL", hostHash);
            } else if (!lastIp.equals(currentIp))
            {
            // IP-Wechsel erkannt!
            recordIpChange(lastIp, currentIp, "CHANGE", hostHash);
            }
    }

    // Letzte bekannte IP für einen Host holen
    private String getLastKnownIp(String hostHash) throws SQLException
    {
        String sql = """
                SELECT new_ip FROM ip_changes 
                WHERE host_hash = ? 
                ORDER BY timestamp DESC 
                LIMIT 1
                """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql))
            {
            pstmt.setString(1, hostHash);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next())
                {
                return rs.getString("new_ip");
                }
            return null;
            }
    }

    // IP-Änderung in Datenbank speichern
    private void recordIpChange(String oldIp, String newIp, String changeType, String hostHash) throws SQLException
    {
        String sql = """
                INSERT INTO ip_changes (timestamp, old_ip, new_ip, change_type, host_hash)
                VALUES (CURRENT_TIMESTAMP, ?, ?, ?, ?)
                """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql))
            {
            pstmt.setString(1, oldIp);
            pstmt.setString(2, newIp);
            pstmt.setString(3, changeType);
            pstmt.setString(4, hostHash);
            pstmt.executeUpdate();
            }
    }

    // Alle IP-Änderungen abfragen
    public List<IpChange> getIpChanges(int limit) throws SQLException
    {
        List<IpChange> results = new ArrayList<>();
        String sql = """
                SELECT timestamp, old_ip, new_ip, change_type, host_hash
                FROM ip_changes
                ORDER BY timestamp DESC
                LIMIT ?
                """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql))
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
    }

    // Statistik: IP-Wechsel pro Host
    public List<IpChangeStats> getIpChangeStatistics() throws SQLException
    {
        List<IpChangeStats> results = new ArrayList<>();
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

        try (Statement stmt = connection.createStatement();
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
    }

    // Hilfsklasse für IP-Änderungen
    public static class IpChange
    {
        private final Instant timestamp;
        private final String oldIp;
        private final String newIp;
        private final String changeType;
        private final String hostHash;

        public IpChange(Instant timestamp, String oldIp, String newIp, String changeType, String hostHash)
        {
            this.timestamp = timestamp;
            this.oldIp = oldIp;
            this.newIp = newIp;
            this.changeType = changeType;
            this.hostHash = hostHash;
        }

        public Instant getTimestamp()
        {
            return timestamp;
        }

        public String getOldIp()
        {
            return oldIp;
        }

        public String getNewIp()
        {
            return newIp;
        }

        public String getChangeType()
        {
            return changeType;
        }

        public String getHostHash()
        {
            return hostHash;
        }
    }

    // Hilfsklasse für IP-Statistik
    public static class IpChangeStats
    {
        private final String hostHash;
        private final int changeCount;
        private final Instant firstChange;
        private final Instant lastChange;

        public IpChangeStats(String hostHash, int changeCount, Instant firstChange, Instant lastChange)
        {
            this.hostHash = hostHash;
            this.changeCount = changeCount;
            this.firstChange = firstChange;
            this.lastChange = lastChange;
        }

        public String getHostHash()
        {
            return hostHash;
        }

        public int getChangeCount()
        {
            return changeCount;
        }

        public Instant getFirstChange()
        {
            return firstChange;
        }

        public Instant getLastChange()
        {
            return lastChange;
        }
    }

    public void save(Measurement m) throws SQLException
    {
        String sql = """
                INSERT INTO measurements 
                (timestamp, target, latency_ms, success, type, local_ipv4, local_ipv6, 
                 external_ipv4, external_ipv6, host_hash) 
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql))
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
            pstmt.executeUpdate();

            // Host registrieren
            registerHost(m.getHostHash(), HostIdentifier.getHostname(), HostIdentifier.getOperatingSystem());
            }
    }

    public List<Measurement> findLastN(int n) throws SQLException
    {
        List<Measurement> results = new ArrayList<>();
        String sql = """
                SELECT timestamp, target, latency_ms, success, type, 
                       local_ipv4, local_ipv6, external_ipv4, external_ipv6, host_hash 
                FROM measurements 
                ORDER BY timestamp DESC 
                LIMIT ?
                """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql))
            {
            pstmt.setInt(1, n);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next())
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

                results.add(new Measurement(target, latency, success, type,
                        timestamp, localIPv4, localIPv6, externalIPv4, externalIPv6, hostHash));
                }
            }
        return results;
    }

    public List<Measurement> findSince(Instant since) throws SQLException
    {
        List<Measurement> results = new ArrayList<>();
        String sql = """
                SELECT timestamp, target, latency_ms, success, type,
                       local_ipv4, local_ipv6, external_ipv4, external_ipv6, host_hash
                FROM measurements
                WHERE timestamp >= ?
                ORDER BY timestamp ASC
                """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql))
            {
            pstmt.setTimestamp(1, java.sql.Timestamp.from(since));
            ResultSet rs = pstmt.executeQuery();
            while (rs.next())
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

                results.add(new Measurement(target, latency, success, type,
                        timestamp, localIPv4, localIPv6, externalIPv4, externalIPv6, hostHash));
                }
            }
        return results;
    }

    public List<Measurement> findAll() throws SQLException
    {
        List<Measurement> results = new ArrayList<>();
        String sql = """
                SELECT timestamp, target, latency_ms, success, type,
                       local_ipv4, local_ipv6, external_ipv4, external_ipv6, host_hash
                FROM measurements
                ORDER BY timestamp ASC
                """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql))
            {
            ResultSet rs = pstmt.executeQuery();
            while (rs.next())
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

                results.add(new Measurement(target, latency, success, type,
                        timestamp, localIPv4, localIPv6, externalIPv4, externalIPv6, hostHash));
                }
            }
        return results;
    }

    // Host-Informationen abfragen
    public List<HostInfo> getAllHosts() throws SQLException
    {
        List<HostInfo> results = new ArrayList<>();
        String sql = "SELECT host_hash, hostname, operating_system, first_seen, last_seen FROM hosts ORDER BY last_seen DESC";

        try (Statement stmt = connection.createStatement();
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
    }

    public void close() throws SQLException
    {
        if (connection != null && !connection.isClosed())
            {
            connection.close();
            }
    }

    // ========== STATISTIK ==========
    public static class Statistics
    {
        private final double avgLatency;
        private final double p95Latency;
        private final double maxLatency;
        private final double packetLossPercent;
        private final double jitter;

        public Statistics(double avgLatency, double p95Latency, double maxLatency,
                          double packetLossPercent, double jitter)
        {
            this.avgLatency = avgLatency;
            this.p95Latency = p95Latency;
            this.maxLatency = maxLatency;
            this.packetLossPercent = packetLossPercent;
            this.jitter = jitter;
        }

        public double getAvgLatency()
        {
            return avgLatency;
        }

        public double getP95Latency()
        {
            return p95Latency;
        }

        public double getMaxLatency()
        {
            return maxLatency;
        }

        public double getPacketLossPercent()
        {
            return packetLossPercent;
        }

        public double getJitter()
        {
            return jitter;
        }
    }

    public Statistics calculateStatistics(String type, int hours) throws SQLException
    {
        String sql = """
                SELECT latency_ms, success
                FROM measurements
                WHERE type = ?
                  AND timestamp >= DATEADD('HOUR', ?, CURRENT_TIMESTAMP)
                ORDER BY timestamp ASC
                """;

        List<Double> latencies = new ArrayList<>();
        int total = 0;
        int failed = 0;

        try (PreparedStatement pstmt = connection.prepareStatement(sql))
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
                    latencies.add(latency);
                    } else
                    {
                    failed++;
                    }
                }
            }

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

        // P95 und Max: benötigen sortierte Werte
        List<Double> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        int p95Index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        p95Index = Math.max(0, Math.min(p95Index, sorted.size() - 1));
        double p95Latency = sorted.get(p95Index);

        double maxLatency = sorted.get(sorted.size() - 1);

        double packetLossPercent = total > 0 ? (failed * 100.0) / total : 0;

        return new Statistics(avgLatency, p95Latency, maxLatency, packetLossPercent, jitter);
    }

    // ========== HOURLY AVERAGES ==========
    public static class HourlyAverage
    {
        private final int hourOfDay;
        private final double avgLatency;
        private final int count;

        public HourlyAverage(int hourOfDay, double avgLatency, int count)
        {
            this.hourOfDay = hourOfDay;
            this.avgLatency = avgLatency;
            this.count = count;
        }

        public int getHourOfDay()
        {
            return hourOfDay;
        }

        public double getAvgLatency()
        {
            return avgLatency;
        }

        public int getCount()
        {
            return count;
        }
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

        List<HourlyAverage> results = new ArrayList<>();
        try (PreparedStatement pstmt = connection.prepareStatement(sql))
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
    }

    // Hilfsklasse für Host-Informationen
    public static class HostInfo
    {
        private final String hostHash;
        private final String hostname;
        private final String operatingSystem;
        private final Instant firstSeen;
        private final Instant lastSeen;

        public HostInfo(String hostHash, String hostname, String operatingSystem,
                        Instant firstSeen, Instant lastSeen)
        {
            this.hostHash = hostHash;
            this.hostname = hostname;
            this.operatingSystem = operatingSystem;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
        }

        public String getHostHash()
        {
            return hostHash;
        }

        public String getHostname()
        {
            return hostname;
        }

        public String getOperatingSystem()
        {
            return operatingSystem;
        }

        public Instant getFirstSeen()
        {
            return firstSeen;
        }

        public Instant getLastSeen()
        {
            return lastSeen;
        }
    }
}