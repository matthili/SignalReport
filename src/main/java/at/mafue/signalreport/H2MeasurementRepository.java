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
            // H2-Treiber laden (embedded)
            Class.forName("org.h2.Driver");
            // Datenbank-Verzeichnis erstellen
            Files.createDirectories(Paths.get("./data"));
        } catch (ClassNotFoundException | IOException e) {
            throw new SQLException("Fehler bei der Datenbank-Initialisierung", e);
        }

        // Verbindung herstellen
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
}