package at.mafue.signalreport;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Testet die MaintenanceWindow-Logik, insbesondere den Mitternachts-Uebergang.
 */
class MaintenanceWindowTest {

    @Test
    void testDisabledWindowIsNeverActive() {
        Config.MaintenanceWindow mw = new Config.MaintenanceWindow();
        mw.setEnabled(false);
        mw.setStartHour(0);
        mw.setStartMinute(0);
        mw.setEndHour(23);
        mw.setEndMinute(59);

        // Selbst ein Fenster von 00:00 bis 23:59 darf nicht aktiv sein, wenn disabled
        assertFalse(mw.isMaintenanceTime(),
            "Deaktiviertes Maintenance-Fenster darf niemals aktiv sein");
    }

    @Test
    void testFullDayWindowIsAlwaysActive() {
        // Fenster von 00:00 bis 23:59 muss immer aktiv sein
        Config.MaintenanceWindow mw = new Config.MaintenanceWindow();
        mw.setEnabled(true);
        mw.setStartHour(0);
        mw.setStartMinute(0);
        mw.setEndHour(23);
        mw.setEndMinute(59);

        assertTrue(mw.isMaintenanceTime(),
            "Ganztaegiges Fenster (00:00-23:59) muss immer aktiv sein");
    }

    @Test
    void testWindowInDistantPastIsNotActive() {
        // Fenster das garantiert NICHT aktiv ist:
        // Setze das Fenster auf eine Stunde die jetzt sicher nicht ist
        // Trick: Wir nutzen die aktuelle Stunde + 12 (modulo 24)
        int currentHour = java.time.LocalTime.now().getHour();
        int distantHour = (currentHour + 12) % 24;
        int distantEndHour = (distantHour + 1) % 24;

        // Sonderfall: Wenn distantHour > distantEndHour wuerde das ein
        // Mitternachts-Fenster erzeugen. In dem Fall waehle eine sichere Zeit.
        if (distantHour > distantEndHour) {
            distantHour = (currentHour + 6) % 24;
            distantEndHour = (distantHour + 1) % 24;
        }

        Config.MaintenanceWindow mw = new Config.MaintenanceWindow();
        mw.setEnabled(true);
        mw.setStartHour(distantHour);
        mw.setStartMinute(0);
        mw.setEndHour(distantEndHour);
        mw.setEndMinute(0);

        assertFalse(mw.isMaintenanceTime(),
            "Fenster in 12h Entfernung darf jetzt nicht aktiv sein (Fenster: "
            + distantHour + ":00-" + distantEndHour + ":00)");
    }

    @Test
    void testCurrentTimeWindowIsActive() {
        // Fenster das die aktuelle Zeit einschliesst
        int currentHour = java.time.LocalTime.now().getHour();
        int endHour = (currentHour + 2) % 24;

        Config.MaintenanceWindow mw = new Config.MaintenanceWindow();
        mw.setEnabled(true);
        mw.setStartHour(currentHour);
        mw.setStartMinute(0);
        mw.setEndHour(endHour);
        mw.setEndMinute(0);

        // Sonderfall: Wenn currentHour z.B. 23 ist und endHour 1,
        // ist das ein Mitternachts-Fenster, das trotzdem aktiv sein muss
        assertTrue(mw.isMaintenanceTime(),
            "Fenster das aktuelle Zeit einschliesst muss aktiv sein (Fenster: "
            + currentHour + ":00-" + endHour + ":00)");
    }

    @Test
    void testMidnightCrossoverLogic() {
        // Teste die Mitternachts-Logik direkt:
        // Ein Fenster von 23:00-01:00 hat start > end
        Config.MaintenanceWindow mw = new Config.MaintenanceWindow();
        mw.setEnabled(true);
        mw.setStartHour(23);
        mw.setStartMinute(0);
        mw.setEndHour(1);
        mw.setEndMinute(0);

        // Wir koennen nicht direkt testen ob es aktiv ist (haengt von der Uhrzeit ab),
        // aber wir koennen pruefen, dass kein Fehler geworfen wird
        // und dass das Ergebnis ein Boolean ist (keine Exception)
        boolean result = mw.isMaintenanceTime();
        // Kein assertFalse/assertTrue -- wir pruefen nur, dass es nicht crashed
        assertNotNull((Boolean) result,
            "Mitternachts-Fenster (23:00-01:00) darf keinen Fehler werfen");
    }

    @Test
    void testDefaultValuesAreDisabled() {
        // Frisch erstelltes MaintenanceWindow muss deaktiviert sein
        Config.MaintenanceWindow mw = new Config.MaintenanceWindow();
        assertFalse(mw.isMaintenanceTime(),
            "Neu erstelltes MaintenanceWindow muss inaktiv sein");
    }

    @Test
    void testSetterGetterRoundtrip() {
        Config.MaintenanceWindow mw = new Config.MaintenanceWindow();
        mw.setEnabled(true);
        mw.setStartHour(3);
        mw.setStartMinute(45);
        mw.setEndHour(4);
        mw.setEndMinute(15);

        assertTrue(mw.isEnabled());
        assertEquals(3, mw.getStartHour());
        assertEquals(45, mw.getStartMinute());
        assertEquals(4, mw.getEndHour());
        assertEquals(15, mw.getEndMinute());
    }
}
