package at.mafue.signalreport;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.*;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartRenderingInfo;
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
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

public class PdfReportGenerator
{
    private final H2MeasurementRepository repository;

    public PdfReportGenerator(H2MeasurementRepository repository)
    {
        this.repository = repository;
    }

    // ========================================================================
    //  Unicode-Schriften (DejaVu Sans, eingebettet)
    //
    //  Die eingebauten PDF-Schriften (Helvetica) beherrschen nur Westeuropa-
    //  Zeichen. Fuer Tuerkisch, Polnisch und Ukrainisch (Kyrillisch) wird
    //  DejaVu Sans mit IDENTITY_H-Encoding eingebettet. Dieselben TTF-Dateien
    //  dienen auch JFreeChart als AWT-Schrift fuer die Chart-Beschriftungen.
    // ========================================================================

    private static volatile BaseFont baseRegular;
    private static volatile BaseFont baseBold;
    private static volatile BaseFont baseOblique;
    private static volatile java.awt.Font awtChartFont;

    private static synchronized void initFonts()
    {
        if (baseRegular != null) return;
        try
            {
            byte[] regular = readResource("/fonts/DejaVuSans.ttf");
            byte[] bold = readResource("/fonts/DejaVuSans-Bold.ttf");
            byte[] oblique = readResource("/fonts/DejaVuSans-Oblique.ttf");

            baseRegular = BaseFont.createFont("DejaVuSans.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, false, regular, null);
            baseBold = BaseFont.createFont("DejaVuSans-Bold.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, false, bold, null);
            baseOblique = BaseFont.createFont("DejaVuSans-Oblique.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, false, oblique, null);

            awtChartFont = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,
                    new java.io.ByteArrayInputStream(regular));
            } catch (Exception e)
            {
            throw new RuntimeException("Konnte eingebettete Schriften nicht laden", e);
            }
    }

    private static byte[] readResource(String path) throws IOException
    {
        try (InputStream in = PdfReportGenerator.class.getResourceAsStream(path))
            {
            if (in == null)
                {
                throw new IOException("Ressource nicht gefunden: " + path);
                }
            return in.readAllBytes();
            }
    }

    private static Font font(float size)
    {
        initFonts();
        return new Font(baseRegular, size);
    }

    private static Font fontBold(float size)
    {
        initFonts();
        return new Font(baseBold, size);
    }

    private static Font fontItalic(float size)
    {
        initFonts();
        return new Font(baseOblique, size);
    }

    public byte[] generateReport(int hours) throws Exception
    {
        initFonts();
        Locale locale = I18n.locale();

        Document document = new Document(PageSize.A4);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = PdfWriter.getInstance(document, baos);

        // Seitenzahlen im Format "1 / 4" in der Fußzeile
        PageNumberFooter pageNumberFooter = new PageNumberFooter();
        writer.setPageEvent(pageNumberFooter);

        document.open();

        Instant now = Instant.now();
        Instant since = now.minusSeconds(hours * 3600L);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", locale)
                .withZone(ZoneId.systemDefault());

        // Logo
        try
            {
            java.io.InputStream logoStream = getClass().getResourceAsStream("/web/logo_mit_schriftzug_light.png");
            if (logoStream != null)
                {
                byte[] logoBytes = logoStream.readAllBytes();
                com.lowagie.text.Image logo = com.lowagie.text.Image.getInstance(logoBytes);
                logo.scaleToFit(250, 80);
                logo.setAlignment(com.lowagie.text.Image.ALIGN_CENTER);
                document.add(logo);
                document.add(Chunk.NEWLINE);
                }
            } catch (Exception e)
            {
            // Fallback: Texttitel falls Logo nicht geladen werden kann
            document.add(new Paragraph(I18n.get("pdf.title"), fontBold(20)));
            document.add(Chunk.NEWLINE);
            }

        // Benutzer-Informationen
        Config config = Config.getInstance();
        Config.UserInfo userInfo = config.getUserInfo();
        boolean hasUserInfo = userInfo != null &&
                (!userInfo.getProvider().isEmpty() ||
                        !userInfo.getCustomerId().isEmpty() ||
                        !userInfo.getUserName().isEmpty());

        if (hasUserInfo)
            {
            document.add(new Paragraph(I18n.get("pdf.customerData"), fontBold(12)));

            Font regularInfoFont = font(11);
            if (!userInfo.getUserName().isEmpty())
                {
                document.add(new Paragraph(I18n.get("customer.name") + ": " + userInfo.getUserName(), regularInfoFont));
                }
            if (!userInfo.getProvider().isEmpty())
                {
                document.add(new Paragraph(I18n.get("customer.provider") + ": " + userInfo.getProvider(), regularInfoFont));
                }
            if (!userInfo.getCustomerId().isEmpty())
                {
                document.add(new Paragraph(I18n.get("customer.customerId") + ": " + userInfo.getCustomerId(), regularInfoFont));
                }
            document.add(Chunk.NEWLINE);
            }

        // Zeitraum
        Font regularFont = font(12);
        String periodDesc = hours == 8760 ? I18n.get("pdf.months12") : hours + " " + I18n.get("pdf.hours");
        document.add(new Paragraph(I18n.get("pdf.period") + ": " + formatter.format(since) + " – " + formatter.format(now) + " (" + periodDesc + ")", regularFont));
        document.add(Chunk.NEWLINE);

        // Host-Informationen
        Font smallFont = font(10);
        document.add(new Paragraph(I18n.get("table.host") + ": " + HostIdentifier.getHostname() + " | " + I18n.get("hosts.hash") + ": " + HostIdentifier.getHostHash().substring(0, 8), smallFont));
        document.add(new Paragraph(I18n.get("network.localIPv4") + ": " + NetworkInfo.getLocalIPv4() + " | " + I18n.get("network.localIPv6") + ": " + NetworkInfo.getLocalIPv6(), smallFont));
        document.add(new Paragraph(I18n.get("network.externalIPv4") + ": " + NetworkInfo.getExternalIPv4() + " | " + I18n.get("network.externalIPv6") + ": " + NetworkInfo.getExternalIPv6(), smallFont));
        document.add(Chunk.NEWLINE);

        // Alle Messungen im Zeitraum holen (direkt per SQL gefiltert)
        List<Measurement> filteredMeasurements = repository.findSince(since);

        // === PING STATISTIK ===
        document.add(new Paragraph(I18n.get("pdf.pingMeasurements"), fontBold(14)));

        H2MeasurementRepository.Statistics pingStats = repository.calculateStatistics("PING", hours);
        Font statFont = font(11);
        addStatisticsBlock(document, pingStats, statFont, locale);
        document.add(Chunk.NEWLINE);

        // PING Chart
        List<Measurement> pingMeasurements = filterByType(filteredMeasurements, "PING");
        if (!pingMeasurements.isEmpty())
            {
            BufferedImage pingChart = createLatencyChart(pingMeasurements, "PING", detectTargetChanges(pingMeasurements));
            addChartToPdf(document, pingChart, I18n.get("pdf.chartTitle"));
            String pingTargetsNote = getTargetsChronological(pingMeasurements);
            document.add(new Paragraph(pingTargetsNote, fontItalic(9)));
            if (!hasUserInfo)
                {
                document.add(Chunk.NEXTPAGE); //provoziert leere Seite, wenn keine Kundendaten hinterlegt sind
                }
            }


        // === DNS STATISTIK ===
        document.add(new Paragraph(I18n.get("pdf.dnsMeasurements"), fontBold(14)));

        H2MeasurementRepository.Statistics dnsStats = repository.calculateStatistics("DNS", hours);
        addStatisticsBlock(document, dnsStats, statFont, locale);
        document.add(Chunk.NEWLINE);

        // DNS Chart
        List<Measurement> dnsMeasurements = filterByType(filteredMeasurements, "DNS");
        if (!dnsMeasurements.isEmpty())
            {
            BufferedImage dnsChart = createLatencyChart(dnsMeasurements, "DNS", detectTargetChanges(dnsMeasurements));
            addChartToPdf(document, dnsChart, I18n.get("pdf.chartTitle"));
            String dnsTargetsNote = getTargetsChronological(dnsMeasurements);
            document.add(new Paragraph(dnsTargetsNote, fontItalic(9)));
            document.add(Chunk.NEXTPAGE);
            }

        // === HTTP STATISTIK ===
        document.add(new Paragraph(I18n.get("pdf.httpMeasurements"), fontBold(14)));

        H2MeasurementRepository.Statistics httpStats = repository.calculateStatistics("HTTP", hours);
        addStatisticsBlock(document, httpStats, statFont, locale);
        document.add(Chunk.NEWLINE);

        // HTTP Chart
        List<Measurement> httpMeasurements = filterByType(filteredMeasurements, "HTTP");
        if (!httpMeasurements.isEmpty())
            {
            BufferedImage httpChart = createLatencyChart(httpMeasurements, "HTTP", detectTargetChanges(httpMeasurements));
            addChartToPdf(document, httpChart, I18n.get("pdf.chartTitle"));
            String httpTargetsNote = getTargetsChronological(httpMeasurements);
            document.add(new Paragraph(httpTargetsNote, fontItalic(9)));
            document.add(Chunk.NEXTPAGE);
            }

        // === TOP 10 SCHLECHTESTE MESSUNGEN ===
        document.add(new Paragraph(I18n.get("pdf.worstTitle"), fontBold(14)));
        document.add(Chunk.NEWLINE);

        List<Measurement> worstMeasurements = getWorstMeasurements(filteredMeasurements, 10);
        if (!worstMeasurements.isEmpty())
            {
            PdfPTable worstTable = createWorstMeasurementsTable(worstMeasurements, locale);
            document.add(worstTable);
            } else
            {
            document.add(new Paragraph(I18n.get("pdf.noMeasurements"), font(10)));
            }
        document.add(Chunk.NEWLINE);

        // === VERBINDUNGSAUSFÄLLE ===
        document.add(new Paragraph(I18n.get("pdf.outagesTitle"), fontBold(14)));
        document.add(Chunk.NEWLINE);

        List<ConnectionOutage> outages = detectConnectionOutages(filteredMeasurements);
        int totalOutages = outages.size();
        document.add(new Paragraph(I18n.get("pdf.totalOutages") + ": " + totalOutages, statFont));
        document.add(Chunk.NEWLINE);

        if (!outages.isEmpty())
            {
            document.add(new Paragraph(I18n.get("pdf.longestOutages"), fontBold(12)));
            document.add(Chunk.NEWLINE);

            List<ConnectionOutage> longestOutages = getLongestOutages(outages, 10);
            PdfPTable outagesTable = createOutagesTable(longestOutages, locale);
            document.add(outagesTable);
            }

        document.close();
        return baos.toByteArray();
    }

    /** Schreibt den Statistik-Block (5 Kennzahlen) lokalisiert ins Dokument. */
    private void addStatisticsBlock(Document document, H2MeasurementRepository.Statistics stats,
                                    Font statFont, Locale locale) throws DocumentException
    {
        document.add(new Paragraph(I18n.get("pdf.avgLatency") + ": " + String.format(locale, "%.1f ms", stats.getAvgLatency()), statFont));
        document.add(new Paragraph(I18n.get("stats.p95") + ": " + String.format(locale, "%.1f ms", stats.getP95Latency()), statFont));
        document.add(new Paragraph(I18n.get("pdf.maxLatency") + ": " + String.format(locale, "%.1f ms", stats.getMaxLatency()), statFont));
        document.add(new Paragraph(I18n.get("stats.packetLoss") + ": " + String.format(locale, "%.1f %%", stats.getPacketLossPercent()), statFont));
        document.add(new Paragraph(I18n.get("stats.jitter") + ": " + String.format(locale, "%.1f ms", stats.getJitter()), statFont));
    }

    // Hilfsmethoden
    private List<Measurement> filterByType(List<Measurement> measurements, String type)
    {
        List<Measurement> result = new ArrayList<>();
        for (Measurement m : measurements)
            {
            if (m.getType().equals(type))
                {
                result.add(m);
                }
            }
        return result;
    }

    private List<Measurement> getWorstMeasurements(List<Measurement> measurements, int count)
    {
        List<Measurement> successful = new ArrayList<>();
        for (Measurement m : measurements)
            {
            if (m.isSuccess())
                {
                successful.add(m);
                }
            }

        successful.sort((a, b) -> Double.compare(b.getLatencyMs(), a.getLatencyMs()));

        if (successful.size() > count)
            {
            return successful.subList(0, count);
            }
        return successful;
    }

    private List<ConnectionOutage> detectConnectionOutages(List<Measurement> measurements)
    {
        List<ConnectionOutage> outages = new ArrayList<>();

        if (measurements.isEmpty()) return outages;

        // Nach Zeitstempel sortieren
        measurements.sort(Comparator.comparing(Measurement::getTimestamp));

        List<Measurement> currentOutage = null;

        for (Measurement m : measurements)
            {
            if (!m.isSuccess())
                {
                if (currentOutage == null)
                    {
                    currentOutage = new ArrayList<>();
                    }
                currentOutage.add(m);
                } else
                {
                if (currentOutage != null && currentOutage.size() >= 2)
                    {
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
        if (currentOutage != null && currentOutage.size() >= 2)
            {
            outages.add(new ConnectionOutage(
                    currentOutage.get(0).getTimestamp(),
                    currentOutage.get(currentOutage.size() - 1).getTimestamp(),
                    currentOutage.size(),
                    currentOutage.get(0).getType()
            ));
            }

        return outages;
    }

    private List<ConnectionOutage> getLongestOutages(List<ConnectionOutage> outages, int count)
    {
        outages.sort((a, b) -> Long.compare(b.getDurationSeconds(), a.getDurationSeconds()));

        if (outages.size() > count)
            {
            return outages.subList(0, count);
            }
        return outages;
    }

    private List<TargetChange> detectTargetChanges(List<Measurement> measurements)
    {
        List<TargetChange> changes = new ArrayList<>();

        if (measurements.isEmpty()) return changes;

        String currentTarget = measurements.get(0).getTarget();

        for (int i = 1; i < measurements.size(); i++)
            {
            Measurement m = measurements.get(i);
            if (!m.getTarget().equals(currentTarget))
                {
                changes.add(new TargetChange(m.getTimestamp(), currentTarget, m.getTarget()));
                currentTarget = m.getTarget();
                }
            }

        return changes;
    }

    private String getTargetsChronological(List<Measurement> measurements)
    {
        if (measurements == null || measurements.isEmpty())
            {
            return I18n.get("pdf.noMeasurementsInPeriod");
            }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", I18n.locale())
                .withZone(ZoneId.systemDefault());

        // Erstes Ziel mit Zeitstempel der ersten Messung
        String firstTarget = measurements.get(0).getTarget();
        String firstTime = fmt.format(measurements.get(0).getTimestamp());
        List<String> entries = new ArrayList<>();
        entries.add(firstTarget + " (" + I18n.get("pdf.since") + " " + firstTime + ")");

        // Ziel-Änderungen mit jeweiligem Zeitstempel
        List<TargetChange> changes = detectTargetChanges(measurements);
        for (TargetChange change : changes)
            {
            entries.add(change.getNewTarget() + " (" + I18n.get("pdf.since") + " " + fmt.format(change.getTimestamp()) + ")");
            }

        String prefix = entries.size() > 1
                ? "⚠️ " + I18n.get("pdf.measuredTargets")
                : I18n.get("pdf.measuredTarget");

        return prefix + String.join(", ", entries);
    }

    private BufferedImage createLatencyChart(List<Measurement> measurements, String type, List<TargetChange> targetChanges)
    {
        initFonts();
        XYSeries series = new XYSeries(type + " " + I18n.get("table.latency"));

        for (int i = 0; i < measurements.size(); i++)
            {
            Measurement m = measurements.get(i);
            if (m.isSuccess())
                {
                series.add(i, m.getLatencyMs());
                }
            }

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                type + " " + I18n.get("chart.latencyOverTime"),
                I18n.get("pdf.measurementNo"),
                I18n.get("chart.latencyMs"),
                dataset,
                PlotOrientation.VERTICAL,
                false,
                true,
                false
        );

        // DejaVu auch fuer die Chart-Texte verwenden, damit alle Sprachen
        // (inkl. Kyrillisch) korrekt gerendert werden
        chart.getTitle().setFont(awtChartFont.deriveFont(java.awt.Font.BOLD, 16f));

        XYPlot plot = chart.getXYPlot();
        plot.getDomainAxis().setLabelFont(awtChartFont.deriveFont(12f));
        plot.getDomainAxis().setTickLabelFont(awtChartFont.deriveFont(10f));
        plot.getRangeAxis().setLabelFont(awtChartFont.deriveFont(12f));
        plot.getRangeAxis().setTickLabelFont(awtChartFont.deriveFont(10f));

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
        ChartRenderingInfo chartInfo = new ChartRenderingInfo();
        java.awt.geom.Rectangle2D chartArea = new java.awt.geom.Rectangle2D.Double(0, 0, width, height);
        chart.draw(g2, chartArea, chartInfo);

        // Ziel-Änderungen markieren
        if (!targetChanges.isEmpty() && !measurements.isEmpty())
            {
            g2.setColor(Color.RED);
            g2.setStroke(new BasicStroke(2.0f));
            java.awt.geom.Rectangle2D dataArea = chartInfo.getPlotInfo().getDataArea();

            for (TargetChange change : targetChanges)
                {
                // Finde Position der Änderung
                for (int i = 0; i < measurements.size(); i++)
                    {
                    if (measurements.get(i).getTimestamp().equals(change.getTimestamp()))
                        {
                        double xValue = plot.getDomainAxis().valueToJava2D(i, dataArea, plot.getDomainAxisEdge());
                        int x = (int) xValue;
                        int yTop = (int) dataArea.getMinY();
                        int yBottom = (int) dataArea.getMaxY();
                        g2.drawLine(x, yTop, x, yBottom);
                        break;
                        }
                    }
                }
            }

        g2.dispose();

        return image;
    }

    private void addChartToPdf(Document document, BufferedImage chart, String title) throws DocumentException, IOException
    {
        // Sicherheitsabstand vor Chart
        document.add(Chunk.NEWLINE);

        // Chart skalieren (max. 500x250 für bessere Platzierung)
        ByteArrayOutputStream imageBaos = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(chart, "png", imageBaos);
        com.lowagie.text.Image chartImage = com.lowagie.text.Image.getInstance(imageBaos.toByteArray());
        chartImage.scaleToFit(500, 250); // 🔑 KLEINER für bessere Platzierung
        chartImage.setAlignment(com.lowagie.text.Image.ALIGN_CENTER);

        // Titel + Chart
        document.add(new Paragraph(title, fontBold(12)));
        document.add(Chunk.NEWLINE);
        document.add(chartImage);

        // Sicherheitsabstand nach Chart
        document.add(Chunk.NEWLINE);
        document.add(Chunk.NEWLINE);
    }

    private PdfPTable createWorstMeasurementsTable(List<Measurement> measurements, Locale locale) throws DocumentException
    {
        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{2, 1, 2, 1, 1});

        Font headerFont = fontBold(10);
        addTableCell(table, I18n.get("pdf.timestamp"), headerFont, true);
        addTableCell(table, I18n.get("table.type"), headerFont, true);
        addTableCell(table, I18n.get("table.target"), headerFont, true);
        addTableCell(table, I18n.get("table.latency"), headerFont, true);
        addTableCell(table, I18n.get("table.host"), headerFont, true);

        Font contentFont = font(9);
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", locale)
                .withZone(ZoneId.systemDefault());

        for (Measurement m : measurements)
            {
            addTableCell(table, dtf.format(m.getTimestamp()), contentFont, false);
            addTableCell(table, m.getType(), contentFont, false);
            addTableCell(table, m.getTarget(), contentFont, false);
            addTableCell(table, String.format(locale, "%.1f ms", m.getLatencyMs()), contentFont, false);
            addTableCell(table, m.getHostHash().substring(0, 8), contentFont, false);
            }

        return table;
    }

    private PdfPTable createOutagesTable(List<ConnectionOutage> outages, Locale locale) throws DocumentException
    {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{2, 2, 1, 1});

        Font headerFont = fontBold(10);
        addTableCell(table, I18n.get("pdf.start"), headerFont, true);
        addTableCell(table, I18n.get("pdf.end"), headerFont, true);
        addTableCell(table, I18n.get("pdf.duration"), headerFont, true);
        addTableCell(table, I18n.get("table.type"), headerFont, true);

        Font contentFont = font(9);
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", locale)
                .withZone(ZoneId.systemDefault());

        for (ConnectionOutage outage : outages)
            {
            addTableCell(table, dtf.format(outage.getStart()), contentFont, false);
            addTableCell(table, dtf.format(outage.getEnd()), contentFont, false);
            addTableCell(table, outage.getDurationFormatted(), contentFont, false);
            addTableCell(table, outage.getType(), contentFont, false);
            }

        return table;
    }

    private void addTableCell(PdfPTable table, String text, Font font, boolean isHeader)
    {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        if (isHeader)
            {
            cell.setBorder(Rectangle.BOTTOM);
            cell.setBorderWidthBottom(1.5f);
            } else
            {
            cell.setBorder(Rectangle.NO_BORDER);
            }
        table.addCell(cell);
    }

    // Hilfsklassen
    private static class ConnectionOutage
    {
        private final Instant start;
        private final Instant end;
        private final int measurementCount;
        private final String type;

        public ConnectionOutage(Instant start, Instant end, int measurementCount, String type)
        {
            this.start = start;
            this.end = end;
            this.measurementCount = measurementCount;
            this.type = type;
        }

        public Instant getStart()
        {
            return start;
        }

        public Instant getEnd()
        {
            return end;
        }

        public int getMeasurementCount()
        {
            return measurementCount;
        }

        public String getType()
        {
            return type;
        }

        public long getDurationSeconds()
        {
            return java.time.Duration.between(start, end).getSeconds();
        }

        public String getDurationFormatted()
        {
            long seconds = getDurationSeconds();
            if (seconds < 60) return seconds + "s";
            if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
            return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
        }
    }

    private static class PageNumberFooter extends PdfPageEventHelper
    {
        private PdfTemplate totalPages;
        private BaseFont footerFont;

        @Override
        public void onOpenDocument(PdfWriter writer, Document document)
        {
            totalPages = writer.getDirectContent().createTemplate(30, 16);
            initFonts();
            footerFont = baseRegular;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document)
        {
            int currentPage = writer.getPageNumber();
            String text = currentPage + " / ";
            float textWidth = footerFont.getWidthPoint(text, 9);
            float xCenter = (document.right() + document.left()) / 2;

            PdfContentByte cb = writer.getDirectContent();
            cb.beginText();
            cb.setFontAndSize(footerFont, 9);
            cb.setTextMatrix(xCenter - textWidth / 2, document.bottom() - 20);
            cb.showText(text);
            cb.endText();
            cb.addTemplate(totalPages, xCenter + textWidth / 2, document.bottom() - 20);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document)
        {
            totalPages.beginText();
            totalPages.setFontAndSize(footerFont, 9);
            totalPages.showText(String.valueOf(writer.getPageNumber() - 1));
            totalPages.endText();
        }
    }

    private static class TargetChange
    {
        private final Instant timestamp;
        private final String oldTarget;
        private final String newTarget;

        public TargetChange(Instant timestamp, String oldTarget, String newTarget)
        {
            this.timestamp = timestamp;
            this.oldTarget = oldTarget;
            this.newTarget = newTarget;
        }

        public Instant getTimestamp()
        {
            return timestamp;
        }

        public String getOldTarget()
        {
            return oldTarget;
        }

        public String getNewTarget()
        {
            return newTarget;
        }
    }
}
