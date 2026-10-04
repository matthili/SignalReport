package at.mafue.signalreport.web.api;

import at.mafue.signalreport.i18n.I18n;
import at.mafue.signalreport.measurement.Measurement;
import at.mafue.signalreport.network.HostIdentifier;
import at.mafue.signalreport.report.PdfReportGenerator;
import at.mafue.signalreport.storage.H2MeasurementRepository;
import at.mafue.signalreport.storage.HourlyRollup;
import at.mafue.signalreport.web.ErrorResponse;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * PDF-Bericht und CSV-Exporte.
 * <p>
 * Die CSV-Exporte werden <b>gestreamt</b>: jede Zeile geht direkt vom ResultSet in die
 * Antwort, ohne alle Messungen im Speicher zu halten (bei 10-Sekunden-Takt sind das
 * Millionen Zeilen pro Jahr). "Alle Daten" wird als ZIP ausgeliefert, der Stundenwerte-
 * Export liefert die Verdichtung (eine Zeile je Stunde, Typ und Ziel).
 */
public class ExportRoutes
{
    private static final Logger logger = LoggerFactory.getLogger(ExportRoutes.class);
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH_mm_ss");

    private ExportRoutes()
    {
    }

    public static void register(Javalin app, H2MeasurementRepository repository)
    {
        // PDF-Bericht generieren
        app.get("/api/report", ctx ->
        {
        try
            {
            int hours = ctx.queryParam("hours") != null
                    ? Integer.parseInt(ctx.queryParam("hours"))
                    : 24;

            PdfReportGenerator generator = new PdfReportGenerator(repository);
            byte[] pdfBytes = generator.generateReport(hours);

            ctx.contentType("application/pdf");
            String pdfFilename = "signalreport-" + HostIdentifier.getHostname() + "-"
                    + java.time.LocalDateTime.now().format(FILE_TIME) + ".pdf";
            ctx.header("Content-Disposition", "attachment; filename=" + pdfFilename);
            ctx.result(pdfBytes);
            } catch (NumberFormatException e)
            {
            ctx.status(400);
            ctx.json(new ErrorResponse("Ungültiger Stunden-Parameter"));
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("PDF-Generierungsfehler: " + e.getMessage()));
            }
        });

        // CSV-Export der Rohmessungen (gestreamt; "alle Daten" als ZIP)
        app.get("/api/export/csv", ctx ->
        {
        boolean exportAll = "true".equalsIgnoreCase(ctx.queryParam("all"));
        int hours = 24;
        try
            {
            if (!exportAll && ctx.queryParam("hours") != null)
                {
                hours = Integer.parseInt(ctx.queryParam("hours"));
                }
            } catch (NumberFormatException e)
            {
            ctx.status(400);
            ctx.json(new ErrorResponse("Ungültiger Parameter"));
            return;
            }
        String typeFilter = ctx.queryParam("type");
        Instant now = Instant.now();
        Instant from = exportAll ? Instant.EPOCH : now.minusSeconds(hours * 3600L);
        Instant to = now.plusSeconds(3600);

        String stamp = java.time.LocalDateTime.now().format(FILE_TIME);
        String baseName = (exportAll ? "signalreport-complete" : "signalreport") + "-"
                + HostIdentifier.getHostname() + "-" + stamp;
        String csvName = baseName + ".csv";

        try
            {
            if (exportAll)
                {
                ctx.contentType("application/zip");
                ctx.header("Content-Disposition", "attachment; filename=" + baseName + ".zip");
                OutputStream raw = ctx.res().getOutputStream();
                ZipOutputStream zip = new ZipOutputStream(raw, StandardCharsets.UTF_8);
                zip.putNextEntry(new ZipEntry(csvName));
                Writer writer = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8), 1 << 16);
                writeMeasurementsCsv(writer, repository, from, to, typeFilter);
                writer.flush();
                zip.closeEntry();
                zip.finish();
                raw.flush();
                } else
                {
                ctx.contentType("text/csv; charset=utf-8");
                ctx.header("Content-Disposition", "attachment; filename=" + csvName);
                Writer writer = new BufferedWriter(new OutputStreamWriter(ctx.res().getOutputStream(), StandardCharsets.UTF_8), 1 << 16);
                writeMeasurementsCsv(writer, repository, from, to, typeFilter);
                writer.flush();
                }
            } catch (Exception e)
            {
            // Die Antwort ist bereits angelaufen; ein JSON-Fehler ist hier nicht mehr moeglich.
            logger.error("CSV-Export abgebrochen: {}", e.getMessage());
            }
        });

        // CSV-Export der Stundenwerte (Verdichtung)
        app.get("/api/export/hourly-csv", ctx ->
        {
        try
            {
            String stamp = java.time.LocalDateTime.now().format(FILE_TIME);
            String csvName = "signalreport-hourly-" + HostIdentifier.getHostname() + "-" + stamp + ".csv";
            ctx.contentType("text/csv; charset=utf-8");
            ctx.header("Content-Disposition", "attachment; filename=" + csvName);
            Writer writer = new BufferedWriter(new OutputStreamWriter(ctx.res().getOutputStream(), StandardCharsets.UTF_8), 1 << 16);
            writeHourlyCsv(writer, repository.findHourlyRollups(null, Instant.EPOCH, Instant.now().plusSeconds(3600)));
            writer.flush();
            } catch (Exception e)
            {
            logger.error("Stundenwerte-Export abgebrochen: {}", e.getMessage());
            }
        });
    }

    /** Schreibt Kopfzeile und Messungen eines Zeitraums als CSV (Spaltenkoepfe in der UI-Sprache). */
    static void writeMeasurementsCsv(Writer writer, H2MeasurementRepository repository, Instant from, Instant to,
                                     String typeFilter) throws Exception
    {
        writer.write(String.join(";",
                I18n.get("csv.timestamp"), I18n.get("csv.type"), I18n.get("csv.target"),
                I18n.get("csv.latencyMs"), I18n.get("csv.success"),
                I18n.get("csv.localIpv4"), I18n.get("csv.localIpv6"),
                I18n.get("csv.externalIpv4"), I18n.get("csv.externalIpv6"),
                I18n.get("csv.hostHash")));
        writer.write("\n");
        ZoneId zone = ZoneId.systemDefault();
        try
            {
            repository.forEachMeasurement(from, to, typeFilter, m ->
            {
            try
                {
                writer.write(formatMeasurementRow(m, zone));
                } catch (IOException e)
                {
                throw new UncheckedIOException(e);
                }
            });
            } catch (UncheckedIOException e)
            {
            throw e.getCause();
            }
    }

    static String formatMeasurementRow(Measurement m, ZoneId zone)
    {
        return m.getTimestamp().atZone(zone).format(CSV_TIME)
                + ";" + escapeCsv(m.getType())
                + ";" + escapeCsv(m.getTarget())
                + ";" + String.format("%.3f", m.getLatencyMs())
                + ";" + (m.isSuccess() ? "1" : "0")
                + ";" + escapeCsv(m.getLocalIPv4())
                + ";" + escapeCsv(m.getLocalIPv6())
                + ";" + escapeCsv(m.getExternalIPv4())
                + ";" + escapeCsv(m.getExternalIPv6())
                + ";" + escapeCsv(m.getHostHash())
                + "\n";
    }

    /** Stundenwerte als CSV: eine Zeile je Stunde, Typ und Ziel. */
    static void writeHourlyCsv(Writer writer, List<HourlyRollup> rollups) throws IOException
    {
        writer.write(String.join(";",
                I18n.get("csv.hourStart"), I18n.get("csv.type"), I18n.get("csv.target"),
                I18n.get("csv.samples"), I18n.get("csv.okSamples"), I18n.get("csv.excludedSamples"),
                I18n.get("csv.minMs"), I18n.get("csv.avgMs"), I18n.get("csv.medianMs"),
                I18n.get("csv.p95Ms"), I18n.get("csv.maxMs"), I18n.get("csv.maxAt"),
                I18n.get("csv.jitterMs"), I18n.get("csv.hostHash")));
        writer.write("\n");
        ZoneId zone = ZoneId.systemDefault();
        for (HourlyRollup r : rollups)
            {
            writer.write(r.getHourStart().atZone(zone).format(CSV_TIME)
                    + ";" + escapeCsv(r.getType())
                    + ";" + escapeCsv(r.getTarget())
                    + ";" + r.getSampleCount()
                    + ";" + r.getOkCount()
                    + ";" + r.getExcludedCount()
                    + ";" + String.format("%.3f", r.getMinMs())
                    + ";" + String.format("%.3f", r.getAvgMs())
                    + ";" + String.format("%.3f", r.getMedianMs())
                    + ";" + String.format("%.3f", r.getP95Ms())
                    + ";" + String.format("%.3f", r.getMaxMs())
                    + ";" + (r.getMaxAt() != null ? r.getMaxAt().atZone(zone).format(CSV_TIME) : "")
                    + ";" + String.format("%.3f", r.getJitterMs())
                    + ";" + escapeCsv(r.getHostHash())
                    + "\n");
            }
    }

    // CSV-Escaping-Hilfsfunktion
    static String escapeCsv(String value)
    {
        if (value == null || value.isEmpty() || value.equals("unknown"))
            {
            return "";
            }
        if (value.contains(";") || value.contains("\n") || value.contains("\""))
            {
            return "\"" + value.replace("\"", "\"\"") + "\"";
            }
        return value;
    }
}
