package at.mafue.signalreport.report;

import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.i18n.I18n;
import at.mafue.signalreport.storage.H2MeasurementRepository;
import at.mafue.signalreport.storage.ServiceCheck;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Erzeugt ein echtes PDF inklusive des neuen Dienst-Erreichbarkeits-Abschnitts.
 * Aus der CI ausgeklammert (setzt den Config-Singleton): laeuft nur, wenn
 * {@code PDF_SMOKE} gesetzt ist, und dann sinnvollerweise isoliert
 * ({@code -Dtest=PdfReportSmokeTest}).
 */
class PdfReportSmokeTest
{
    @Test
    void smokeGeneratePdfWithReachabilitySection() throws Exception
    {
        assumeTrue(System.getenv("PDF_SMOKE") != null, "PDF_SMOKE nicht gesetzt -- PDF-Smoke uebersprungen");

        String dbPath = "./data/test-pdf-smoke";
        H2MeasurementRepository repo = new H2MeasurementRepository(dbPath);
        try
            {
            Config.createDefault();
            Config.getInstance().getServiceReachability().setEnabled(true);
            I18n.load("de");

            Instant now = Instant.now();
            // Episoden-Story fuer facebook (Default-aktiv): erreichbar -> gesperrt -> erreichbar
            repo.saveServiceCheck(new ServiceCheck(now.minusSeconds(5 * 3600), "facebook", "REACHABLE", "HTTP 200", 200, "31.13.84.36", 30.0));
            repo.saveServiceCheck(new ServiceCheck(now.minusSeconds(4 * 3600), "facebook", "DNS_BLOCKED", "isp NXDOMAIN", -1, null, 0.0));
            repo.saveServiceCheck(new ServiceCheck(now.minusSeconds(3 * 3600), "facebook", "DNS_BLOCKED", "isp NXDOMAIN", -1, null, 0.0));
            repo.saveServiceCheck(new ServiceCheck(now.minusSeconds(2 * 3600), "facebook", "REACHABLE", "HTTP 200", 200, "31.13.84.36", 28.0));

            byte[] pdf = new PdfReportGenerator(repo).generateReport(24);

            assertNotNull(pdf);
            assertTrue(pdf.length > 1000, "PDF sollte nicht leer sein");
            // PDF-Signatur "%PDF"
            assertEquals('%', pdf[0]);
            assertEquals('P', pdf[1]);
            assertEquals('D', pdf[2]);
            assertEquals('F', pdf[3]);
            System.out.println("[PDF-SMOKE] PDF erzeugt: " + pdf.length + " Bytes");
            }
        finally
            {
            repo.close();
            new java.io.File(dbPath + ".mv.db").delete();
            new java.io.File(dbPath + ".trace.db").delete();
            new java.io.File(dbPath + "-shadow.mv.db").delete();
            new java.io.File(dbPath + "-shadow.trace.db").delete();
            }
    }

    /**
     * Langer Bericht (12 Monate) aus Stundenwerten: 120 Tage Historie werden verdichtet, ein Teil der
     * Rohdaten wird nach der Aufbewahrungsregel geloescht, danach muss das PDF aus der Verdichtung
     * entstehen (Tagesmittel-Diagramme, gewichtete Statistik, Ausfaelle aus den aufbewahrten Funden).
     */
    @Test
    void smokeGenerateLongRangePdfFromRollups() throws Exception
    {
        assumeTrue(System.getenv("PDF_SMOKE") != null, "PDF_SMOKE nicht gesetzt -- PDF-Smoke uebersprungen");

        String dbPath = "./data/test-pdf-smoke-long";
        H2MeasurementRepository repo = new H2MeasurementRepository(dbPath);
        try
            {
            Config.createDefault();
            I18n.load("de");

            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            Instant start = now.minus(120, java.time.temporal.ChronoUnit.DAYS);
            java.util.List<at.mafue.signalreport.measurement.Measurement> batch = new java.util.ArrayList<>();
            int rows = 0;
            // alle 10 Minuten eine Runde (PING/DNS/HTTP); am Tag 30 ein Ausfall von 40 Minuten
            for (Instant t = start; t.isBefore(now); t = t.plusSeconds(600))
                {
                boolean outage = t.isAfter(start.plus(30, java.time.temporal.ChronoUnit.DAYS))
                        && t.isBefore(start.plus(30, java.time.temporal.ChronoUnit.DAYS).plusSeconds(2400));
                double base = 15 + 10 * Math.sin(t.getEpochSecond() / 3600.0);
                batch.add(new at.mafue.signalreport.measurement.Measurement("8.8.8.8", outage ? 5000 : base, !outage, "PING", t, "192.168.1.100", "fe80::1", "85.182.1.1", "::1", "smokehost"));
                batch.add(new at.mafue.signalreport.measurement.Measurement("google.com", outage ? 5000 : base + 5, !outage, "DNS", t, "192.168.1.100", "fe80::1", "85.182.1.1", "::1", "smokehost"));
                batch.add(new at.mafue.signalreport.measurement.Measurement("https://example.com", outage ? 5000 : base + 60, !outage, "HTTP", t, "192.168.1.100", "fe80::1", "85.182.1.1", "::1", "smokehost"));
                rows += 3;
                if (batch.size() >= 3000)
                    {
                    repo.saveAll(batch);
                    batch.clear();
                    }
                }
            repo.saveAll(batch);

            at.mafue.signalreport.storage.RollupService rollups = new at.mafue.signalreport.storage.RollupService(repo);
            long t0 = System.nanoTime();
            int hours = rollups.rollupCompletedHours(now, Integer.MAX_VALUE, () -> true);
            long deleted = rollups.applyRetention(now, 90, 400, () -> true);
            long prepMs = (System.nanoTime() - t0) / 1_000_000;

            t0 = System.nanoTime();
            byte[] pdf = new PdfReportGenerator(repo).generateReport(8760);
            long pdfMs = (System.nanoTime() - t0) / 1_000_000;

            assertNotNull(pdf);
            assertTrue(pdf.length > 10_000, "PDF mit 3 Diagrammen sollte nicht winzig sein");
            assertEquals('%', pdf[0]);
            assertEquals('P', pdf[1]);
            assertEquals('D', pdf[2]);
            assertEquals('F', pdf[3]);
            assertTrue(hours >= 120 * 24 - 1, "alle abgeschlossenen Stunden verdichtet: " + hours);
            assertTrue(deleted > 0, "Rohzeilen aelter als 90 Tage muessen verdichtet und geloescht sein");
            assertTrue(pdfMs < 60_000, "12-Monats-PDF muss unter einer Minute fertig sein, war " + pdfMs + " ms");
            System.out.printf("[PDF-SMOKE] %,d Rohzeilen, %d Stunden verdichtet, %,d geloescht in %d ms; 12-Monats-PDF %,d Bytes in %d ms%n",
                    rows, hours, deleted, prepMs, pdf.length, pdfMs);
            java.nio.file.Files.write(java.nio.file.Path.of("./data/test-pdf-smoke-long.pdf"), pdf);
            }
        finally
            {
            repo.close();
            new java.io.File(dbPath + ".mv.db").delete();
            new java.io.File(dbPath + ".trace.db").delete();
            new java.io.File(dbPath + "-shadow.mv.db").delete();
            new java.io.File(dbPath + "-shadow.trace.db").delete();
            }
    }
}
