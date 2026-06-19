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
}
