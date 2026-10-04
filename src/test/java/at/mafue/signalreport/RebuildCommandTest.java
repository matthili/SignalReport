package at.mafue.signalreport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Randfall des Kommandozeilen-Neuaufbaus: Pfad-Fallback ohne Konfiguration. Der eigentliche
 * Neuaufbau ist in DatabaseRebuilderTest abgedeckt; hier wird bewusst kein Lauf gegen
 * ./data/signalreport ausgeloest, weil dort eine echte Entwicklungs-Datenbank liegen kann.
 */
class RebuildCommandTest
{
    @Test
    void testResolveDbPathFallsBackToDefault()
    {
        assertEquals(RebuildCommand.DEFAULT_DB_PATH, RebuildCommand.resolveDbPath("definitiv-nicht-vorhanden.json"));
        assertEquals(RebuildCommand.DEFAULT_DB_PATH, RebuildCommand.resolveDbPath(null));
    }
}
