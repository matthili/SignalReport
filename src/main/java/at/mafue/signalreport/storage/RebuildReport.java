package at.mafue.signalreport.storage;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Ergebnis eines Datenbank-Neuaufbaus durch {@link DatabaseRebuilder}: was aus welcher
 * Quelle gelesen wurde, was nicht lesbar war, wie gross die neue Datei ist und wohin
 * die alten Dateien verschoben wurden. {@link #toText()} liefert den Bericht, der auch
 * als Datei neben der Datenbank abgelegt wird.
 */
public class RebuildReport
{
    /** Befund zu einer Quelle (Primary oder Shadow). */
    public static class SourceSummary
    {
        public final String name;
        public final Path file;
        public long sizeBytes;
        public boolean openable;
        public String openError;
        /** Zeilenzahl laut Index (-1 = nicht ermittelbar). */
        public long inventoryRows = -1;
        public Instant minTimestamp;
        public Instant maxTimestamp;
        /** Tatsaechlich gelesene Messzeilen. */
        public long readRows;
        public final List<String> unreadableRanges = new ArrayList<>();
        public final List<String> tableErrors = new ArrayList<>();

        public SourceSummary(String name, Path file)
        {
            this.name = name;
            this.file = file;
        }
    }

    public final List<SourceSummary> sources = new ArrayList<>();
    public long unionMeasurements;
    public long hosts;
    public long ipChanges;
    public long serviceChecks;
    public long hourlyRollups;
    public long targetSizeBeforeCompact;
    public long targetSizeAfterCompact;
    public boolean swapped;
    public Path quarantineDir;
    public Path newFile;
    public Path reportFile;
    public Duration duration = Duration.ZERO;

    public String sourceErrors()
    {
        StringBuilder sb = new StringBuilder();
        for (SourceSummary s : sources)
            {
            if (!s.openable)
                {
                if (sb.length() > 0) sb.append("; ");
                sb.append(s.name).append(": ").append(s.openError);
                }
            }
        return sb.toString();
    }

    /** Summe aller als unlesbar gemeldeten Stunden ueber alle Quellen. */
    public int unreadableRangeCount()
    {
        int n = 0;
        for (SourceSummary s : sources) n += s.unreadableRanges.size();
        return n;
    }

    public String toText()
    {
        StringBuilder sb = new StringBuilder();
        // Bewusst reines ASCII: der Bericht erscheint auch in Windows-Konsolen ohne UTF-8
        sb.append("SignalReport - Bericht zum Datenbank-Neuaufbau\n");
        sb.append("==============================================\n");
        sb.append(String.format("Dauer: %d min %d s%n", duration.toMinutes(), duration.toSecondsPart()));
        sb.append('\n');
        for (SourceSummary s : sources)
            {
            sb.append("Quelle ").append(s.name).append(": ").append(s.file).append('\n');
            sb.append("  Groesse: ").append(formatBytes(s.sizeBytes)).append('\n');
            if (!s.openable)
                {
                sb.append("  NICHT lesbar: ").append(s.openError).append('\n');
                continue;
                }
            if (s.inventoryRows >= 0)
                {
                sb.append(String.format("  Messzeilen laut Index: %,d (%s bis %s)%n", s.inventoryRows,
                        s.minTimestamp, s.maxTimestamp));
                }
            sb.append(String.format("  Gelesene Messzeilen: %,d%n", s.readRows));
            if (s.unreadableRanges.isEmpty())
                {
                sb.append("  Unlesbare Bereiche: keine\n");
                } else
                {
                sb.append("  Unlesbare Bereiche (Daten der anderen Quelle verwendet):\n");
                for (String r : s.unreadableRanges) sb.append("    - ").append(r).append('\n');
                }
            for (String t : s.tableErrors) sb.append("  Hinweis: ").append(t).append('\n');
            }
        sb.append('\n');
        sb.append(String.format("Neue Datenbank: %,d Messzeilen (Vereinigung aller Quellen), %,d Hosts, %,d IP-Wechsel, "
                        + "%,d Dienst-Pruefungen, %,d Stundenwerte%n",
                unionMeasurements, hosts, ipChanges, serviceChecks, hourlyRollups));
        sb.append(String.format("Groesse vor Kompaktierung: %s, danach: %s%n",
                formatBytes(targetSizeBeforeCompact), formatBytes(targetSizeAfterCompact)));
        if (swapped)
            {
            sb.append("Alte Dateien verschoben nach: ").append(quarantineDir).append('\n');
            sb.append("Die neue Datei ist als Primary eingesetzt, die Shadow ist eine Kopie davon.\n");
            } else if (newFile != null)
            {
            sb.append("Alte Dateien unveraendert; neue Datei: ").append(newFile).append('\n');
            }
        if (reportFile != null)
            {
            sb.append("Dieser Bericht: ").append(reportFile).append('\n');
            }
        return sb.toString();
    }

    static String formatBytes(long bytes)
    {
        if (bytes < 0) return "unbekannt";
        if (bytes >= 1_000_000_000L) return String.format("%.1f GB", bytes / 1e9);
        if (bytes >= 1_000_000L) return String.format("%.1f MB", bytes / 1e6);
        if (bytes >= 1_000L) return String.format("%.1f KB", bytes / 1e3);
        return bytes + " Byte";
    }
}
