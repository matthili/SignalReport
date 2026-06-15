package at.mafue.signalreport.web.api;

import at.mafue.signalreport.i18n.I18n;
import at.mafue.signalreport.measurement.Measurement;
import at.mafue.signalreport.network.HostIdentifier;
import at.mafue.signalreport.report.PdfReportGenerator;
import at.mafue.signalreport.web.ErrorResponse;
import io.javalin.Javalin;
import java.time.Instant;
import java.util.List;
import at.mafue.signalreport.storage.H2MeasurementRepository;

public class ExportRoutes
{
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
                    + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH_mm_ss"))
                    + ".pdf";
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

        // CSV-Export
        app.get("/api/export/csv", ctx ->
        {
        try
            {
            String allParam = ctx.queryParam("all");
            int hours = 24;
            boolean exportAll = "true".equalsIgnoreCase(allParam);

            if (!exportAll && ctx.queryParam("hours") != null)
                {
                hours = Integer.parseInt(ctx.queryParam("hours"));
                }

            String typeFilter = ctx.queryParam("type");

            List<Measurement> measurements;
            if (exportAll)
                {
                measurements = repository.findAll();
                } else
                {
                Instant cutoff = Instant.now().minusSeconds(hours * 3600L);
                measurements = repository.findSince(cutoff);
                }

            // Spaltenkoepfe folgen der eingestellten Sprache (UI-Sprache)
            StringBuilder csv = new StringBuilder();
            csv.append(String.join(";",
                            I18n.get("csv.timestamp"), I18n.get("csv.type"), I18n.get("csv.target"),
                            I18n.get("csv.latencyMs"), I18n.get("csv.success"),
                            I18n.get("csv.localIpv4"), I18n.get("csv.localIpv6"),
                            I18n.get("csv.externalIpv4"), I18n.get("csv.externalIpv6"),
                            I18n.get("csv.hostHash")))
                    .append("\n");

            for (Measurement m : measurements)
                {
                if (typeFilter != null && !typeFilter.equals(m.getType())) continue;

                csv.append(m.getTimestamp().atZone(java.time.ZoneId.systemDefault())
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                        .append(";")
                        .append(escapeCsv(m.getType()))
                        .append(";")
                        .append(escapeCsv(m.getTarget()))
                        .append(";")
                        .append(String.format("%.3f", m.getLatencyMs()))
                        .append(";")
                        .append(m.isSuccess() ? "1" : "0")
                        .append(";")
                        .append(escapeCsv(m.getLocalIPv4()))
                        .append(";")
                        .append(escapeCsv(m.getLocalIPv6()))
                        .append(";")
                        .append(escapeCsv(m.getExternalIPv4()))
                        .append(";")
                        .append(escapeCsv(m.getExternalIPv6()))
                        .append(";")
                        .append(escapeCsv(m.getHostHash()))
                        .append("\n");
                }

            String timestamp = java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH_mm_ss"));
            String csvFilename = (exportAll ? "signalreport-complete" : "signalreport") + "-"
                    + HostIdentifier.getHostname() + "-" + timestamp + ".csv";
            ctx.contentType("text/csv");
            ctx.header("Content-Disposition", "attachment; filename=" + csvFilename);

            ctx.result(csv.toString());
            } catch (NumberFormatException e)
            {
            ctx.status(400);
            ctx.json(new ErrorResponse("Ungültiger Parameter"));
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("CSV-Export-Fehler: " + e.getMessage()));
            }
        });
    }

    // CSV-Escaping-Hilfsfunktion
    private static String escapeCsv(String value)
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
