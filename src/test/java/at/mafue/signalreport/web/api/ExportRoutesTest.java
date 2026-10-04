package at.mafue.signalreport.web.api;

import at.mafue.signalreport.i18n.I18n;
import at.mafue.signalreport.measurement.Measurement;
import at.mafue.signalreport.storage.H2MeasurementRepository;
import at.mafue.signalreport.storage.RollupService;
import io.javalin.Javalin;
import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CSV-Exporte ueber echten Javalin auf Zufallsport: gestreamtes CSV, "alle Daten" als ZIP
 * mit genau einer CSV-Datei, Stundenwerte-Export. Arbeitet mit einer echten H2-Datei
 * unter ./data/test-export.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExportRoutesTest
{
    private static final String DB = "./data/test-export";
    private static final String HOST = "exporthost";

    private H2MeasurementRepository repo;
    private Javalin app;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    void start() throws SQLException
    {
        I18n.load("de");
        deleteFiles();
        repo = new H2MeasurementRepository(DB);
        app = Javalin.create(c -> c.showJavalinBanner = false);
        ExportRoutes.register(app, repo);
        app.start(0);
    }

    @AfterAll
    void stop() throws SQLException
    {
        if (app != null) app.stop();
        if (repo != null) repo.close();
        deleteFiles();
    }

    @BeforeEach
    void seed() throws SQLException
    {
        for (String base : new String[]{DB, DB + "-shadow"})
            {
            try (Connection c = DriverManager.getConnection("jdbc:h2:" + base + ";DB_CLOSE_ON_EXIT=FALSE", "sa", "");
                 Statement st = c.createStatement())
                {
                st.execute("DELETE FROM measurements");
                st.execute("DELETE FROM measurement_hourly");
                st.execute("DELETE FROM rollup_state");
                st.execute("DELETE FROM hosts");
                }
            }
        Instant now = Instant.now();
        repo.saveAll(List.of(
                m(now.minusSeconds(120), "PING", "8.8.8.8", 12.5, true),
                m(now.minusSeconds(110), "DNS", "google.com", 20.0, true),
                m(now.minusSeconds(100), "PING", "8.8.8.8", 5000.0, false)));
    }

    private static void deleteFiles()
    {
        for (String suffix : new String[]{".mv.db", ".trace.db", "-shadow.mv.db", "-shadow.trace.db"})
            {
            new File(DB + suffix).delete();
            }
    }

    private static Measurement m(Instant ts, String type, String target, double latency, boolean ok)
    {
        return new Measurement(target, latency, ok, type, ts, "192.168.1.100", "fe80::1", "85.182.1.1", "::1", HOST);
    }

    private HttpResponse<byte[]> get(String pathAndQuery) throws Exception
    {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + app.port() + pathAndQuery))
                .GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String[] lines(byte[] body)
    {
        return new String(body, StandardCharsets.UTF_8).strip().split("\n");
    }

    @Test
    void testCsvForTimeRangeIsStreamedAsText() throws Exception
    {
        HttpResponse<byte[]> resp = get("/api/export/csv?hours=24");

        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"),
                resp.headers().firstValue("Content-Type").orElse("(leer)"));
        String disposition = resp.headers().firstValue("Content-Disposition").orElse("");
        assertTrue(disposition.contains("signalreport-") && disposition.endsWith(".csv"), disposition);

        String[] rows = lines(resp.body());
        assertEquals(4, rows.length, "Kopfzeile + 3 Messungen");
        assertTrue(rows[0].startsWith("Zeitstempel;Typ;Ziel;"), rows[0]);
        assertEquals(10, rows[0].split(";").length, "10 Spalten");
        assertTrue(rows[1].contains(";PING;8.8.8.8;"), rows[1]);
        assertTrue(rows[3].endsWith(";0;192.168.1.100;fe80::1;85.182.1.1;::1;" + HOST), "Fehlschlag mit 0: " + rows[3]);
    }

    @Test
    void testCsvTypeFilter() throws Exception
    {
        HttpResponse<byte[]> resp = get("/api/export/csv?hours=24&type=PING");
        String[] rows = lines(resp.body());
        assertEquals(3, rows.length, "Kopfzeile + 2 PING-Messungen");
        for (int i = 1; i < rows.length; i++)
            {
            assertTrue(rows[i].contains(";PING;"), rows[i]);
            }
    }

    @Test
    void testInvalidHoursParameterIsRejected() throws Exception
    {
        HttpResponse<byte[]> resp = get("/api/export/csv?hours=abc");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void testAllDataIsDeliveredAsZipWithOneCsvEntry() throws Exception
    {
        HttpResponse<byte[]> resp = get("/api/export/csv?all=true");

        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").startsWith("application/zip"),
                resp.headers().firstValue("Content-Type").orElse("(leer)"));
        String disposition = resp.headers().firstValue("Content-Disposition").orElse("");
        assertTrue(disposition.contains("signalreport-complete-") && disposition.endsWith(".zip"), disposition);

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(resp.body()), StandardCharsets.UTF_8))
            {
            ZipEntry entry = zip.getNextEntry();
            assertNotNull(entry, "ZIP muss einen Eintrag enthalten");
            assertTrue(entry.getName().startsWith("signalreport-complete-") && entry.getName().endsWith(".csv"), entry.getName());
            String[] rows = lines(zip.readAllBytes());
            assertEquals(4, rows.length, "Kopfzeile + 3 Messungen");
            assertTrue(rows[0].startsWith("Zeitstempel;Typ;Ziel;"), rows[0]);
            assertNull(zip.getNextEntry(), "genau ein Eintrag");
            }
    }

    @Test
    void testHourlyCsvListsRollups() throws Exception
    {
        Instant h = Instant.parse("2026-09-01T10:00:00Z");
        repo.saveAll(List.of(
                m(h.plusSeconds(10), "PING", "8.8.8.8", 10.0, true),
                m(h.plusSeconds(20), "PING", "8.8.8.8", 20.0, true),
                m(h.plusSeconds(15), "DNS", "google.com", 7.0, true)));
        new RollupService(repo).rollupHour(h);

        HttpResponse<byte[]> resp = get("/api/export/hourly-csv");

        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"));
        assertTrue(resp.headers().firstValue("Content-Disposition").orElse("").contains("signalreport-hourly-"));
        String[] rows = lines(resp.body());
        assertEquals(3, rows.length, "Kopfzeile + PING + DNS");
        assertTrue(rows[0].startsWith("Stunde;Typ;Ziel;Messungen;Erfolgreich;Ausgenommen;"), rows[0]);
        assertEquals(14, rows[0].split(";").length);
        String pingRow = rows[1].contains(";PING;") ? rows[1] : rows[2];
        assertTrue(pingRow.contains(";PING;8.8.8.8;2;2;0;"), pingRow);
        assertTrue(pingRow.endsWith(";" + HOST), pingRow);
    }

    @Test
    void testEscapeCsv()
    {
        assertEquals("", ExportRoutes.escapeCsv(null));
        assertEquals("", ExportRoutes.escapeCsv(""));
        assertEquals("", ExportRoutes.escapeCsv("unknown"));
        assertEquals("8.8.8.8", ExportRoutes.escapeCsv("8.8.8.8"));
        assertEquals("\"a;b\"", ExportRoutes.escapeCsv("a;b"));
        assertEquals("\"x\"\"y\"", ExportRoutes.escapeCsv("x\"y"));
    }

    @Test
    void testFormatMeasurementRow()
    {
        Measurement m = m(Instant.parse("2026-01-02T03:04:05Z"), "PING", "8.8.8.8", 12.5, true);
        String row = ExportRoutes.formatMeasurementRow(m, ZoneOffset.UTC);
        assertEquals("2026-01-02 03:04:05;PING;8.8.8.8;" + String.format("%.3f", 12.5)
                + ";1;192.168.1.100;fe80::1;85.182.1.1;::1;" + HOST + "\n", row);
    }
}
