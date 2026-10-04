package at.mafue.signalreport;

import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.storage.DatabaseRebuilder;
import at.mafue.signalreport.storage.RebuildReport;

import java.io.File;

/**
 * Kommandozeilen-Neuaufbau der Datenbank: {@code java -jar signalreport.jar rebuild-db [--no-swap]}.
 * <p>
 * Im Datenverzeichnis bei gestopptem Dienst ausfuehren (laeuft er noch, scheitert das
 * Oeffnen der Quellen an der H2-Dateisperre). Liest den Datenbankpfad aus {@code config.json}
 * (Fallback {@code ./data/signalreport}) und delegiert an {@link DatabaseRebuilder}.
 * Mit {@code --no-swap} wird nur die neue Datei erzeugt, die alten bleiben unveraendert.
 * Ausgaben gehen auf die Konsole, der Bericht zusaetzlich als Datei ins Datenverzeichnis.
 */
public final class RebuildCommand
{
    static final String DEFAULT_DB_PATH = "./data/signalreport";

    private RebuildCommand()
    {
    }

    public static boolean run(String configFile, boolean swap)
    {
        String dbPath = resolveDbPath(configFile);
        System.out.println("[rebuild-db] Datenbank: " + new File(dbPath + ".mv.db").getAbsolutePath());
        System.out.println("[rebuild-db] " + (swap
                ? "Alte Dateien werden nach dem Neuaufbau in den Quarantaene-Ordner verschoben."
                : "Nur-Aufbau (--no-swap): die alten Dateien bleiben unveraendert."));
        DatabaseRebuilder rebuilder = new DatabaseRebuilder(dbPath, s -> System.out.println("[rebuild-db] " + s));
        try
            {
            RebuildReport report = rebuilder.rebuild(swap);
            System.out.println();
            System.out.println(report.toText());
            return true;
            } catch (DatabaseRebuilder.RebuildException e)
            {
            System.err.println("[rebuild-db] FEHLER: " + e.getMessage());
            System.err.println("[rebuild-db] Laeuft der SignalReport-Dienst noch? Er muss fuer den Neuaufbau gestoppt sein.");
            return false;
            }
    }

    static String resolveDbPath(String configFile)
    {
        try
            {
            if (configFile != null && new File(configFile).exists())
                {
                String path = Config.load(configFile).getDatabase().getPath();
                if (path != null && !path.isBlank()) return path;
                }
            } catch (Exception e)
            {
            System.err.println("[rebuild-db] Konfiguration nicht lesbar (" + e.getMessage()
                    + "), verwende " + DEFAULT_DB_PATH);
            }
        return DEFAULT_DB_PATH;
    }
}
