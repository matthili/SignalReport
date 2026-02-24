package at.mafue.signalreport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class H2MeasurementRepository {
    private final String jdbcUrl = "jdbc:h2:./data/signalreport;DB_CLOSE_ON_EXIT=FALSE";
    private Connection connection;

    public H2MeasurementRepository() throws SQLException {
        try {
            Class.forName("org.h2.Driver");
            Files.createDirectories(Paths.get("./data"));
        } catch (ClassNotFoundException | IOException e) {
            throw new SQLException("Fehler bei der Datenbank-Initialisierung", e);
        }
        this.connection = DriverManager.getConnection(jdbcUrl, "sa", "");
        createTable();
    }

    private void createTable() throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS measurements (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                timestamp TIMESTAMP NOT NULL,
                target VARCHAR(255) NOT NULL,
                latency_ms DOUBLE NOT NULL,
                success BOOLEAN NOT NULL,
                type VARCHAR(20) NOT NULL
            )
            """;
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    public void save(Measurement m) throws SQLException {
        String sql = "INSERT INTO measurements (timestamp, target, latency_ms, success, type) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setTimestamp(1, Timestamp.from(m.getTimestamp()));
            pstmt.setString(2, m.getTarget());
            pstmt.setDouble(3, m.getLatencyMs());
            pstmt.setBoolean(4, m.isSuccess());
            pstmt.setString(5, m.getType());
            pstmt.executeUpdate();
        }
    }

    public List<Measurement> findLastN(int n) throws SQLException {
        List<Measurement> results = new ArrayList<>();
        String sql = "SELECT timestamp, target, latency_ms, success, type FROM measurements ORDER BY timestamp DESC LIMIT ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, n);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                Instant timestamp = rs.getTimestamp(1).toInstant();
                String target = rs.getString(2);
                double latency = rs.getDouble(3);
                boolean success = rs.getBoolean(4);
                String type = rs.getString(5);
                results.add(new Measurement(target, latency, success, type, timestamp));
            }
        }
        return results;
    }

    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    // ========== STATISTIK ==========
    public static class Statistics {
        private final double avgLatency;
        private final double p95Latency;
        private final double maxLatency;
        private final double packetLossPercent;
        private final double jitter;

        public Statistics(double avgLatency, double p95Latency, double maxLatency,
                         double packetLossPercent, double jitter) {
            this.avgLatency = avgLatency;
            this.p95Latency = p95Latency;
            this.maxLatency = maxLatency;
            this.packetLossPercent = packetLossPercent;
            this.jitter = jitter;
        }

        public double getAvgLatency() { return avgLatency; }
        public double getP95Latency() { return p95Latency; }
        public double getMaxLatency() { return maxLatency; }
        public double getPacketLossPercent() { return packetLossPercent; }
        public double getJitter() { return jitter; }
    }

    public Statistics calculateStatistics(String type, int hours) throws SQLException {
        String sql = """
            SELECT latency_ms, success
            FROM measurements
            WHERE type = ?
              AND timestamp >= DATEADD('HOUR', ?, CURRENT_TIMESTAMP)
            ORDER BY latency_ms
            """;

        List<Double> latencies = new ArrayList<>();
        int total = 0;
        int failed = 0;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, type);
            pstmt.setInt(2, -hours);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                double latency = rs.getDouble("latency_ms");
                boolean success = rs.getBoolean("success");

                total++;
                if (success) {
                    latencies.add(latency);
                } else {
                    failed++;
                }
            }
        }

        if (latencies.isEmpty()) {
            return new Statistics(0, 0, 0, failed > 0 ? 100.0 : 0, 0);
        }

        double avgLatency = latencies.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        int p95Index = (int) Math.ceil(latencies.size() * 0.95) - 1;
        p95Index = Math.max(0, Math.min(p95Index, latencies.size() - 1));
        double p95Latency = latencies.get(p95Index);

        double maxLatency = latencies.stream().mapToDouble(Double::doubleValue).max().orElse(0);

        double packetLossPercent = total > 0 ? (failed * 100.0) / total : 0;

        double jitter = 0;
        if (latencies.size() > 1) {
            double sumDiff = 0;
            for (int i = 1; i < latencies.size(); i++) {
                sumDiff += Math.abs(latencies.get(i) - latencies.get(i - 1));
            }
            jitter = sumDiff / (latencies.size() - 1);
        }

        return new Statistics(avgLatency, p95Latency, maxLatency, packetLossPercent, jitter);
    }

    // ========== HOURLY AVERAGES ==========
    public static class HourlyAverage {
        private final int hourOfDay;
        private final double avgLatency;
        private final int count;

        public HourlyAverage(int hourOfDay, double avgLatency, int count) {
            this.hourOfDay = hourOfDay;
            this.avgLatency = avgLatency;
            this.count = count;
        }

        // Getter (wichtig für Jackson JSON-Serialisierung!)
        public int getHourOfDay() { return hourOfDay; }
        public double getAvgLatency() { return avgLatency; }
        public int getCount() { return count; }
    }

    public List<HourlyAverage> calculateHourlyAverages(String type, int days) throws SQLException {
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
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, type);
            pstmt.setInt(2, -days);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                results.add(new HourlyAverage(
                    rs.getInt("hour_of_day"),
                    rs.getDouble("avg_latency"),
                    rs.getInt("measurement_count")
                ));
            }
        }
        return results;
    }
}