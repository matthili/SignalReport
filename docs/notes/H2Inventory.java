import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Reines Lese-Inventar einer SignalReport-H2-Datenbank. Oeffnet die Datei strikt
 * read-only (ACCESS_MODE_DATA=r, IFEXISTS=TRUE, keine Trace-Datei) und prueft
 * tageweise, ob alle Zeilen der Tabelle measurements lesbar sind. Tage, bei denen
 * H2 einen Fehler wirft, werden mit Fehlertext ausgegeben.
 *
 * Aufruf: java -cp h2-2.4.240.jar H2Inventory.java <pfad-ohne-.mv.db>
 */
public class H2Inventory
{
    public static void main(String[] args) throws Exception
    {
        String base = args[0];
        String url = "jdbc:h2:" + base + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE;TRACE_LEVEL_FILE=0;DB_CLOSE_ON_EXIT=FALSE";
        Class.forName("org.h2.Driver");

        long t0 = System.nanoTime();
        try (Connection c = DriverManager.getConnection(url, "sa", ""))
            {
            System.out.printf("OPEN ok in %.1fs  readOnly=%s%n", (System.nanoTime() - t0) / 1e9, c.isReadOnly());

            try (Statement st = c.createStatement())
                {
                printOne(st, "measurements  ", "SELECT COUNT(*), MIN(timestamp), MAX(timestamp) FROM measurements");
                printOne(st, "service_checks", "SELECT COUNT(*), MIN(timestamp), MAX(timestamp) FROM service_checks");
                printOne(st, "ip_changes    ", "SELECT COUNT(*), MIN(timestamp), MAX(timestamp) FROM ip_changes");
                printOne(st, "hosts         ", "SELECT COUNT(*), MIN(first_seen), MAX(last_seen) FROM hosts");

                long t1 = System.nanoTime();
                try (ResultSet rs = st.executeQuery("SELECT type, COUNT(*) FROM measurements GROUP BY type ORDER BY type"))
                    {
                    while (rs.next()) System.out.printf("  type %-14s %,12d%n", rs.getString(1), rs.getLong(2));
                    }
                System.out.printf("  (GROUP BY type in %.1fs)%n", (System.nanoTime() - t1) / 1e9);
                }

            // Tageweiser Voll-Lesetest: latency_ms/success stehen nur in der Tabelle,
            // nicht im Index -> jede Zeile muss tatsaechlich gelesen werden.
            Instant min, max;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT MIN(timestamp), MAX(timestamp) FROM measurements"))
                {
                rs.next();
                min = rs.getTimestamp(1).toInstant();
                max = rs.getTimestamp(2).toInstant();
                }
            LocalDate day = min.atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate last = max.atZone(ZoneOffset.UTC).toLocalDate();
            long okRows = 0, badDays = 0, days = 0;
            long tScan = System.nanoTime();
            String sql = "SELECT COUNT(*), SUM(CASE WHEN success THEN 1 ELSE 0 END), SUM(latency_ms), "
                    + "SUM(CASE WHEN excluded THEN 1 ELSE 0 END) FROM measurements WHERE timestamp >= ? AND timestamp < ?";
            try (PreparedStatement ps = c.prepareStatement(sql))
                {
                while (!day.isAfter(last))
                    {
                    Instant from = day.atStartOfDay(ZoneOffset.UTC).toInstant();
                    Instant to = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                    ps.setTimestamp(1, Timestamp.from(from));
                    ps.setTimestamp(2, Timestamp.from(to));
                    days++;
                    try (ResultSet rs = ps.executeQuery())
                        {
                        rs.next();
                        long n = rs.getLong(1);
                        okRows += n;
                        if (days % 10 == 0 || n == 0)
                            {
                            System.out.printf("  %s  rows=%,7d ok=%,7d excl=%,6d  (elapsed %.0fs)%n",
                                    day, n, rs.getLong(2), rs.getLong(4), (System.nanoTime() - tScan) / 1e9);
                            }
                        } catch (SQLException e)
                        {
                        badDays++;
                        String msg = e.getMessage();
                        if (msg != null && msg.length() > 220) msg = msg.substring(0, 220) + "...";
                        System.out.printf("  %s  FEHLER: %s%n", day, msg);
                        }
                    day = day.plusDays(1);
                    }
                }
            System.out.printf("SCAN done: days=%d badDays=%d readableRows=%,d in %.0fs%n",
                    days, badDays, okRows, (System.nanoTime() - tScan) / 1e9);
            }
    }

    private static void printOne(Statement st, String label, String sql)
    {
        long t = System.nanoTime();
        try (ResultSet rs = st.executeQuery(sql))
            {
            rs.next();
            System.out.printf("  %s count=%,12d  min=%s  max=%s  (%.1fs)%n", label,
                    rs.getLong(1), rs.getTimestamp(2), rs.getTimestamp(3), (System.nanoTime() - t) / 1e9);
            } catch (SQLException e)
            {
            System.out.printf("  %s FEHLER: %s%n", label, e.getMessage());
            }
    }
}
