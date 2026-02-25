package at.mafue.signalreport;

// OpenPDF Imports (explizit)
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

// JFreeChart Imports
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

// AWT Imports (explizit)
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class PdfReportGenerator {
    private final H2MeasurementRepository repository;

    public PdfReportGenerator(H2MeasurementRepository repository) {
        this.repository = repository;
    }

    public byte[] generateReport(int hours) throws Exception {
        // Dokument erstellen
        Document document = new Document(PageSize.A4);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter.getInstance(document, baos);
        document.open();

        // Zeitraum für Bericht
        Instant now = Instant.now();
        Instant since = now.minusSeconds(hours * 3600L);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                .withZone(ZoneId.systemDefault());

        // Titel
        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA, 20, Font.BOLD);
        document.add(new Paragraph("SignalReport – Internet-Qualitätsbericht", titleFont));
        document.add(Chunk.NEWLINE);

        // Zeitraum
        Font regularFont = FontFactory.getFont(FontFactory.HELVETICA, 12);
        document.add(new Paragraph("Zeitraum: " + formatter.format(since) + " – " + formatter.format(now), regularFont));
        document.add(Chunk.NEWLINE);

        // Statistiken
        H2MeasurementRepository.Statistics stats = repository.calculateStatistics("PING", hours);
        Font boldFont = FontFactory.getFont(FontFactory.HELVETICA, 14, Font.BOLD);
        document.add(new Paragraph("Zusammenfassung (PING)", boldFont));
        document.add(Chunk.NEWLINE);

        Font statFont = FontFactory.getFont(FontFactory.HELVETICA, 11);
        document.add(new Paragraph("Durchschnittliche Latenz:       " + String.format("%.1f ms", stats.getAvgLatency()), statFont));
        document.add(new Paragraph("95th Percentile:                " + String.format("%.1f ms", stats.getP95Latency()), statFont));
        document.add(new Paragraph("Maximale Latenz (erfolgreich):  " + String.format("%.1f ms", stats.getMaxLatency()), statFont));
        document.add(new Paragraph("Paketverlust:                   " + String.format("%.1f %%", stats.getPacketLossPercent()), statFont));
        document.add(new Paragraph("Jitter:                         " + String.format("%.1f ms", stats.getJitter()), statFont));
        document.add(Chunk.NEWLINE);
        document.add(Chunk.NEWLINE);

        // Chart erstellen
        List<Measurement> measurements = repository.findLastN(hours * 6);
        BufferedImage chartImage = createLatencyChart(measurements);

        // Chart als Bild speichern und ins PDF einfügen
        ByteArrayOutputStream imageBaos = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(chartImage, "png", imageBaos);
        Image chartImagePdf = Image.getInstance(imageBaos.toByteArray());
        chartImagePdf.scaleToFit(500, 300);
        document.add(chartImagePdf);

        document.close();
        return baos.toByteArray();
    }

    private BufferedImage createLatencyChart(List<Measurement> measurements) {
        XYSeries series = new XYSeries("PING Latenz");
        int index = 0;
        for (Measurement m : measurements) {
            if (m.getType().equals("PING") && m.isSuccess()) {
                series.add(index++, m.getLatencyMs());
            }
        }

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                "PING Latenz über Zeit (letzte Messungen)",
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

        int width = 800;
        int height = 400;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        g2.setPaint(Color.WHITE);
        g2.fillRect(0, 0, width, height);
        chart.draw(g2, new java.awt.geom.Rectangle2D.Double(0, 0, width, height));
        g2.dispose();

        return image;
    }
}