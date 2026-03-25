package at.mafue.signalreport;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

public class PdfReportGenerator {
    private final H2MeasurementRepository repository;

    public PdfReportGenerator(H2MeasurementRepository repository) {
        this.repository = repository;
    }

    public byte[] generateReport(int hours) throws Exception {
        Document document = new Document(PageSize.A4);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter.getInstance(document, baos);
        document.open();

        Instant now = Instant.now();
        Instant since = now.minusSeconds(hours * 3600L);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                .withZone(ZoneId.systemDefault());

        // Titel
        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA, 20, Font.BOLD);
        document.add(new Paragraph("SignalReport – Internet-Qualitätsbericht", titleFont));
        document.add(Chunk.NEWLINE);

        // Benutzer-Informationen
        Config config = Config.getInstance();
        Config.UserInfo userInfo = config.getUserInfo();
        boolean hasUserInfo = userInfo != null &&
            (!userInfo.getProvider().isEmpty() ||
             !userInfo.getCustomerId().isEmpty() ||
             !userInfo.getUserName().isEmpty());

        if (hasUserInfo) {
            Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA, 12, Font.BOLD);
            document.add(new Paragraph("Kunde:", sectionFont));

            Font regularFont = FontFactory.getFont(FontFactory.HELVETICA, 11);
            if (!userInfo.getUserName().isEmpty()) {
                document.add(new Paragraph("Name:          " + userInfo.getUserName(), regularFont));
            }
            if (!userInfo.getProvider().isEmpty()) {
                document.add(new Paragraph("Provider:      " + userInfo.getProvider(), regularFont));
            }
            if (!userInfo.getCustomerId().isEmpty()) {
                document.add(new Paragraph("Kundennummer:  " + userInfo.getCustomerId(), regularFont));
            }
            document.add(Chunk.NEWLINE);
        }

        // Zeitraum
        Font regularFont = FontFactory.getFont(FontFactory.HELVETICA, 12);
        String periodDesc = hours == 8760 ? "12 Monate" : hours + " Stunden";
        document.add(new Paragraph("Zeitraum: " + formatter.format(since) + " – " + formatter.format(now) + " (" + periodDesc + ")", regularFont));
        document.add(Chunk.NEWLINE);

        // Host-Informationen
        Font smallFont = FontFactory.getFont(FontFactory.HELVETICA, 10);
        document.add(new Paragraph("Host: " + HostIdentifier.getHostname() + " | Hash: " + HostIdentifier.getHostHash().substring(0, 8), smallFont));
        document.add(new Paragraph("LAN IPv4: " + NetworkInfo.getLocalIPv4() + " | LAN IPv6: " + NetworkInfo.getLocalIPv6(), smallFont));
        document.add(new Paragraph("Externe IPv4: " + NetworkInfo.getExternalIPv4() + " | Externe IPv6: " + NetworkInfo.getExternalIPv6(), smallFont));
        document.add(Chunk.NEWLINE);

        // Alle Messungen holen
        List<Measurement> allMeasurements = repository.findLastN(100000);
        List<Measurement> filteredMeasurements = new ArrayList<>();
        for (Measurement m : allMeasurements) {
            if (!m.getTimestamp().isBefore(since)) {
                filteredMeasurements.add(m);
            }
        }

        // === PING STATISTIK ===
        document.add(new Paragraph("PING-Messungen", FontFactory.getFont(FontFactory.HELVETICA, 14, Font.BOLD)));
        document.add(Chunk.NEWLINE);

        H2MeasurementRepository.Statistics pingStats = repository.calculateStatistics("PING", hours);
        Font statFont = FontFactory.getFont(FontFactory.HELVETICA, 11);
        document.add(new Paragraph("Durchschnittliche Latenz:       " + String.format("%.1f ms", pingStats.getAvgLatency()), statFont));
        document.add(new Paragraph("95th Percentile:                " + String.format("%.1f ms", pingStats.getP95Latency()), statFont));
        document.add(new Paragraph("Maximale Latenz (erfolgreich):  " + String.format("%.1f ms", pingStats.getMaxLatency()), statFont));
        document.add(new Paragraph("Paketverlust:                   " + String.format("%.1f %%", pingStats.getPacketLossPercent()), statFont));
        document.add(new Paragraph("Jitter:                         " + String.format("%.1f ms", pingStats.getJitter()), statFont));
        document.add(Chunk.NEWLINE);

        // PING Chart
        List<Measurement> pingMeasurements = filterByType(filteredMeasurements, "PING");
        if (!pingMeasurements.isEmpty()) {
            BufferedImage pingChart = createLatencyChart(pingMeasurements, "PING", detectTargetChanges(pingMeasurements));
            addChartToPdf(document, pingChart, "PING Latenz über Zeit");
            document.add(Chunk.NEWLINE);
            String pingTargetsNote = getTargetsChronological(pingMeasurements);
            document.add(new Paragraph(pingTargetsNote, FontFactory.getFont(FontFactory.HELVETICA, 9, Font.ITALIC)));
            //document.add(Chunk.NEXTPAGE); //provoziert leere Seite
        }


        // === DNS STATISTIK ===
        document.add(new Paragraph("DNS-Messungen", FontFactory.getFont(FontFactory.HELVETICA, 14, Font.BOLD)));
        document.add(Chunk.NEWLINE);

        H2MeasurementRepository.Statistics dnsStats = repository.calculateStatistics("DNS", hours);
        document.add(new Paragraph("Durchschnittliche Latenz:       " + String.format("%.1f ms", dnsStats.getAvgLatency()), statFont));
        document.add(new Paragraph("95th Percentile:                " + String.format("%.1f ms", dnsStats.getP95Latency()), statFont));
        document.add(new Paragraph("Maximale Latenz (erfolgreich):  " + String.format("%.1f ms", dnsStats.getMaxLatency()), statFont));
        document.add(new Paragraph("Paketverlust:                   " + String.format("%.1f %%", dnsStats.getPacketLossPercent()), statFont));
        document.add(new Paragraph("Jitter:                         " + String.format("%.1f ms", dnsStats.getJitter()), statFont));
        document.add(Chunk.NEWLINE);

        // DNS Chart
        List<Measurement> dnsMeasurements = filterByType(filteredMeasurements, "DNS");
        if (!dnsMeasurements.isEmpty()) {
            BufferedImage dnsChart = createLatencyChart(dnsMeasurements, "DNS", detectTargetChanges(dnsMeasurements));
            addChartToPdf(document, dnsChart, "DNS Latenz über Zeit");
            document.add(Chunk.NEWLINE);
            String dnsTargetsNote = getTargetsChronological(dnsMeasurements);
            document.add(new Paragraph(dnsTargetsNote, FontFactory.getFont(FontFactory.HELVETICA, 9, Font.ITALIC)));
            //document.add(Chunk.NEWLINE);
            document.add(Chunk.NEXTPAGE);
        }

        // === HTTP STATISTIK ===
        document.add(new Paragraph("HTTP-Messungen", FontFactory.getFont(FontFactory.HELVETICA, 14, Font.BOLD)));
        document.add(Chunk.NEWLINE);

        H2MeasurementRepository.Statistics httpStats = repository.calculateStatistics("HTTP", hours);
        document.add(new Paragraph("Durchschnittliche Latenz:       " + String.format("%.1f ms", httpStats.getAvgLatency()), statFont));
        document.add(new Paragraph("95th Percentile:                " + String.format("%.1f ms", httpStats.getP95Latency()), statFont));
        document.add(new Paragraph("Maximale Latenz (erfolgreich):  " + String.format("%.1f ms", httpStats.getMaxLatency()), statFont));
        document.add(new Paragraph("Paketverlust:                   " + String.format("%.1f %%", httpStats.getPacketLossPercent()), statFont));
        document.add(new Paragraph("Jitter:                         " + String.format("%.1f ms", httpStats.getJitter()), statFont));
        document.add(Chunk.NEWLINE);

        // HTTP Chart
        List<Measurement> httpMeasurements = filterByType(filteredMeasurements, "HTTP");
        if (!httpMeasurements.isEmpty()) {
            BufferedImage httpChart = createLatencyChart(httpMeasurements, "HTTP", detectTargetChanges(httpMeasurements));
            addChartToPdf(document, httpChart, "HTTP Latenz über Zeit");
            document.add(Chunk.NEWLINE);
            String httpTargetsNote = getTargetsChronological(httpMeasurements);
            document.add(new Paragraph(httpTargetsNote, FontFactory.getFont(FontFactory.HELVETICA, 9, Font.ITALIC)));
            //document.add(Chunk.NEWLINE);
            document.add(Chunk.NEXTPAGE);
        }

        // === TOP 10 SCHLECHTESTE MESSUNGEN ===
        document.add(new Paragraph("Top 10 schlechteste Messungen", FontFactory.getFont(FontFactory.HELVETICA, 14, Font.BOLD)));
        document.add(Chunk.NEWLINE);

        List<Measurement> worstMeasurements = getWorstMeasurements(filteredMeasurements, 10);
        if (!worstMeasurements.isEmpty()) {
            PdfPTable worstTable = createWorstMeasurementsTable(worstMeasurements);
            document.add(worstTable);
        } else {
            document.add(new Paragraph("Keine Messungen gefunden.", FontFactory.getFont(FontFactory.HELVETICA, 10)));
        }
        document.add(Chunk.NEWLINE);

        // === VERBINDUNGSAUSFÄLLE ===
        document.add(new Paragraph("Verbindungsausfälle", FontFactory.getFont(FontFactory.HELVETICA, 14, Font.BOLD)));
        document.add(Chunk.NEWLINE);

        List<ConnectionOutage> outages = detectConnectionOutages(filteredMeasurements);
        int totalOutages = outages.size();
        document.add(new Paragraph("Gesamtanzahl Verbindungsausfälle: " + totalOutages, statFont));
        document.add(Chunk.NEWLINE);

        if (!outages.isEmpty()) {
            document.add(new Paragraph("Top 10 längste Verbindungsausfälle", FontFactory.getFont(FontFactory.HELVETICA, 12, Font.BOLD)));
            document.add(Chunk.NEWLINE);

            List<ConnectionOutage> longestOutages = getLongestOutages(outages, 10);
            PdfPTable outagesTable = createOutagesTable(longestOutages);
            document.add(outagesTable);
        }

        document.close();
        return baos.toByteArray();
    }

    // Hilfsmethoden
    private List<Measurement> filterByType(List<Measurement> measurements, String type) {
        List<Measurement> result = new ArrayList<>();
        for (Measurement m : measurements) {
            if (m.getType().equals(type)) {
                result.add(m);
            }
        }
        return result;
    }

    private List<Measurement> getWorstMeasurements(List<Measurement> measurements, int count) {
        List<Measurement> successful = new ArrayList<>();
        for (Measurement m : measurements) {
            if (m.isSuccess()) {
                successful.add(m);
            }
        }

        successful.sort((a, b) -> Double.compare(b.getLatencyMs(), a.getLatencyMs()));

        if (successful.size() > count) {
            return successful.subList(0, count);
        }
        return successful;
    }

    private List<ConnectionOutage> detectConnectionOutages(List<Measurement> measurements) {
        List<ConnectionOutage> outages = new ArrayList<>();

        if (measurements.isEmpty()) return outages;

        // Nach Zeitstempel sortieren
        measurements.sort(Comparator.comparing(Measurement::getTimestamp));

        List<Measurement> currentOutage = null;

        for (Measurement m : measurements) {
            if (!m.isSuccess()) {
                if (currentOutage == null) {
                    currentOutage = new ArrayList<>();
                }
                currentOutage.add(m);
            } else {
                if (currentOutage != null && currentOutage.size() >= 2) {
                    outages.add(new ConnectionOutage(
                        currentOutage.get(0).getTimestamp(),
                        currentOutage.get(currentOutage.size() - 1).getTimestamp(),
                        currentOutage.size(),
                        currentOutage.get(0).getType()
                    ));
                }
                currentOutage = null;
            }
        }

        // Letzten Ausfall speichern, falls am Ende
        if (currentOutage != null && currentOutage.size() >= 2) {
            outages.add(new ConnectionOutage(
                currentOutage.get(0).getTimestamp(),
                currentOutage.get(currentOutage.size() - 1).getTimestamp(),
                currentOutage.size(),
                currentOutage.get(0).getType()
            ));
        }

        return outages;
    }

    private List<ConnectionOutage> getLongestOutages(List<ConnectionOutage> outages, int count) {
        outages.sort((a, b) -> Long.compare(b.getDurationSeconds(), a.getDurationSeconds()));

        if (outages.size() > count) {
            return outages.subList(0, count);
        }
        return outages;
    }

    private List<TargetChange> detectTargetChanges(List<Measurement> measurements) {
        List<TargetChange> changes = new ArrayList<>();

        if (measurements.isEmpty()) return changes;

        String currentTarget = measurements.get(0).getTarget();

        for (int i = 1; i < measurements.size(); i++) {
            Measurement m = measurements.get(i);
            if (!m.getTarget().equals(currentTarget)) {
                changes.add(new TargetChange(m.getTimestamp(), currentTarget, m.getTarget()));
                currentTarget = m.getTarget();
            }
        }

        return changes;
    }

    private String getTargetsChronological(List<Measurement> measurements) {
    if (measurements == null || measurements.isEmpty()) {
        return "Keine Messungen im Zeitraum";
    }

    // LinkedHashSet behält Reihenfolge des ersten Auftretens
    java.util.LinkedHashSet<String> uniqueTargets = new java.util.LinkedHashSet<>();

    // Liste ist absteigend sortiert (neueste zuerst) → rückwärts traversieren für chronologische Reihenfolge
    for (int i = measurements.size() - 1; i >= 0; i--) {
        uniqueTargets.add(measurements.get(i).getTarget());
    }

    // Formatierung mit farblichem Hinweis bei mehreren Zielen
    String targetsList = String.join(", ", uniqueTargets);
    String prefix = uniqueTargets.size() > 1
        ? "⚠️ Gemessene Ziele (chronologisch): "
        : "Gemessenes Ziel: ";

    return prefix + targetsList;
    }

    private BufferedImage createLatencyChart(List<Measurement> measurements, String type, List<TargetChange> targetChanges) {
        XYSeries series = new XYSeries(type + " Latenz");
        Map<Integer, TargetChange> changePoints = new HashMap<>();

        for (int i = 0; i < measurements.size(); i++) {
            Measurement m = measurements.get(i);
            if (m.isSuccess()) {
                series.add(i, m.getLatencyMs());
            }
        }

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                type + " Latenz über Zeit",
                "Messung #",
                "Latenz (ms)",
                dataset,
                PlotOrientation.VERTICAL,
                false,
                true,
                false
        );

        XYPlot plot = chart.getXYPlot();
        XYLineAndShapeRenderer renderer = (XYLineAndShapeRenderer) plot.getRenderer();
        renderer.setSeriesPaint(0, Color.decode("#0d6efd"));
        renderer.setSeriesStroke(0, new BasicStroke(2.0f));
        renderer.setSeriesShapesVisible(0, true);

        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setLowerBound(0);

        // Chart als BufferedImage rendern
        int width = 800;
        int height = 400;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        g2.setPaint(Color.WHITE);
        g2.fillRect(0, 0, width, height);
        chart.draw(g2, new java.awt.geom.Rectangle2D.Double(0, 0, width, height));

        // Ziel-Änderungen markieren
        if (!targetChanges.isEmpty() && !measurements.isEmpty()) {
            g2.setColor(Color.RED);
            g2.setStroke(new BasicStroke(2.0f));

            for (TargetChange change : targetChanges) {
                // Finde Position der Änderung
                for (int i = 0; i < measurements.size(); i++) {
                    if (measurements.get(i).getTimestamp().equals(change.getTimestamp())) {
                        int x = (int) ((double) i / measurements.size() * width);
                        g2.drawLine(x, 0, x, height);
                        break;
                    }
                }
            }
        }

        g2.dispose();

        return image;
    }

    private void addChartToPdf(Document document, BufferedImage chart, String title) throws DocumentException, IOException {
        // Sicherheitsabstand vor Chart
        document.add(Chunk.NEWLINE);

        // Chart skalieren (max. 500x250 für bessere Platzierung)
        ByteArrayOutputStream imageBaos = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(chart, "png", imageBaos);
        com.lowagie.text.Image chartImage = com.lowagie.text.Image.getInstance(imageBaos.toByteArray());
        chartImage.scaleToFit(500, 250); // 🔑 KLEINER für bessere Platzierung
        chartImage.setAlignment(com.lowagie.text.Image.ALIGN_CENTER);

        // Titel + Chart
        document.add(new Paragraph(title, FontFactory.getFont(FontFactory.HELVETICA, 12, Font.BOLD)));
        document.add(Chunk.NEWLINE);
        document.add(chartImage);

        // Sicherheitsabstand nach Chart
        document.add(Chunk.NEWLINE);
        document.add(Chunk.NEWLINE);
    }

    private PdfPTable createWorstMeasurementsTable(List<Measurement> measurements) {
        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{2, 1, 2, 1, 1});

        Font headerFont = FontFactory.getFont(FontFactory.HELVETICA, 10, Font.BOLD);
        addTableCell(table, "Zeitstempel", headerFont, true);
        addTableCell(table, "Typ", headerFont, true);
        addTableCell(table, "Ziel", headerFont, true);
        addTableCell(table, "Latenz", headerFont, true);
        addTableCell(table, "Host", headerFont, true);

        Font contentFont = FontFactory.getFont(FontFactory.HELVETICA, 9);
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());

        for (Measurement m : measurements) {
            addTableCell(table, dtf.format(m.getTimestamp()), contentFont, false);
            addTableCell(table, m.getType(), contentFont, false);
            addTableCell(table, m.getTarget(), contentFont, false);
            addTableCell(table, String.format("%.1f ms", m.getLatencyMs()), contentFont, false);
            addTableCell(table, m.getHostHash().substring(0, 8), contentFont, false);
        }

        return table;
    }

    private PdfPTable createOutagesTable(List<ConnectionOutage> outages) {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{2, 2, 1, 1});

        Font headerFont = FontFactory.getFont(FontFactory.HELVETICA, 10, Font.BOLD);
        addTableCell(table, "Start", headerFont, true);
        addTableCell(table, "Ende", headerFont, true);
        addTableCell(table, "Dauer", headerFont, true);
        addTableCell(table, "Typ", headerFont, true);

        Font contentFont = FontFactory.getFont(FontFactory.HELVETICA, 9);
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());

        for (ConnectionOutage outage : outages) {
            addTableCell(table, dtf.format(outage.getStart()), contentFont, false);
            addTableCell(table, dtf.format(outage.getEnd()), contentFont, false);
            addTableCell(table, outage.getDurationFormatted(), contentFont, false);
            addTableCell(table, outage.getType(), contentFont, false);
        }

        return table;
    }

    private void addTableCell(PdfPTable table, String text, Font font, boolean isHeader) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        if (isHeader) {
            cell.setBorder(Rectangle.BOTTOM);
            cell.setBorderWidthBottom(1.5f);
        } else {
            cell.setBorder(Rectangle.NO_BORDER);
        }
        table.addCell(cell);
    }

    // Hilfsklassen
    private static class ConnectionOutage {
        private final Instant start;
        private final Instant end;
        private final int measurementCount;
        private final String type;

        public ConnectionOutage(Instant start, Instant end, int measurementCount, String type) {
            this.start = start;
            this.end = end;
            this.measurementCount = measurementCount;
            this.type = type;
        }

        public Instant getStart() { return start; }
        public Instant getEnd() { return end; }
        public int getMeasurementCount() { return measurementCount; }
        public String getType() { return type; }
        public long getDurationSeconds() { return java.time.Duration.between(start, end).getSeconds(); }
        public String getDurationFormatted() {
            long seconds = getDurationSeconds();
            if (seconds < 60) return seconds + "s";
            if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
            return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
        }
    }

    private static class TargetChange {
        private final Instant timestamp;
        private final String oldTarget;
        private final String newTarget;

        public TargetChange(Instant timestamp, String oldTarget, String newTarget) {
            this.timestamp = timestamp;
            this.oldTarget = oldTarget;
            this.newTarget = newTarget;
        }

        public Instant getTimestamp() { return timestamp; }
        public String getOldTarget() { return oldTarget; }
        public String getNewTarget() { return newTarget; }
    }
}