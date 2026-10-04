package at.mafue.signalreport.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Konfiguration der Daten-Aufbereitung: Zeitfenster (auch ueber Mitternacht), Umschaltung
 * auf das Maintenance-Fenster, Begrenzung der Aufbewahrungsfrist und JSON-Rundlauf.
 */
class DataPrepConfigTest
{
    @Test
    void testDefaults()
    {
        DataPrepConfig cfg = new DataPrepConfig();
        assertTrue(cfg.isEnabled(), "Aufbereitung ist standardmaessig aktiv");
        assertEquals(3, cfg.getStartHour());
        assertEquals(0, cfg.getStartMinute());
        assertEquals(5, cfg.getEndHour());
        assertEquals(0, cfg.getEndMinute());
        assertFalse(cfg.isUseMaintenanceWindow());
        assertEquals(DataPrepConfig.DEFAULT_RETENTION_DAYS, cfg.getRetentionDays());
        assertEquals(90, DataPrepConfig.DEFAULT_RETENTION_DAYS);
    }

    @Test
    void testInWindowSimpleRange()
    {
        LocalTime start = LocalTime.of(3, 0);
        LocalTime end = LocalTime.of(5, 0);
        assertFalse(DataPrepConfig.inWindow(LocalTime.of(2, 59), start, end));
        assertTrue(DataPrepConfig.inWindow(LocalTime.of(3, 0), start, end), "Beginn ist inklusiv");
        assertTrue(DataPrepConfig.inWindow(LocalTime.of(4, 59), start, end));
        assertFalse(DataPrepConfig.inWindow(LocalTime.of(5, 0), start, end), "Ende ist exklusiv");
        assertFalse(DataPrepConfig.inWindow(LocalTime.of(12, 0), start, end));
    }

    @Test
    void testInWindowAcrossMidnight()
    {
        LocalTime start = LocalTime.of(23, 0);
        LocalTime end = LocalTime.of(1, 0);
        assertTrue(DataPrepConfig.inWindow(LocalTime.of(23, 30), start, end));
        assertTrue(DataPrepConfig.inWindow(LocalTime.of(0, 30), start, end));
        assertTrue(DataPrepConfig.inWindow(LocalTime.of(23, 0), start, end));
        assertFalse(DataPrepConfig.inWindow(LocalTime.of(1, 0), start, end));
        assertFalse(DataPrepConfig.inWindow(LocalTime.of(12, 0), start, end));
    }

    @Test
    void testEqualStartAndEndIsNeverInWindow()
    {
        LocalTime t = LocalTime.of(4, 0);
        assertFalse(DataPrepConfig.inWindow(t, t, t));
        assertFalse(DataPrepConfig.inWindow(LocalTime.of(10, 0), t, t));
    }

    @Test
    void testWindowNowUsesOwnWindowByDefault()
    {
        DataPrepConfig cfg = new DataPrepConfig();          // 03:00-05:00
        MaintenanceWindow maintenance = new MaintenanceWindow();
        maintenance.setEnabled(true);
        maintenance.setStartHour(22);
        maintenance.setStartMinute(0);
        maintenance.setEndHour(23);
        maintenance.setEndMinute(0);

        assertTrue(cfg.isWindowNow(LocalTime.of(4, 0), maintenance));
        assertFalse(cfg.isWindowNow(LocalTime.of(22, 30), maintenance), "Maintenance-Fenster gilt nur mit der Option");
    }

    @Test
    void testWindowNowWithMaintenanceOption()
    {
        DataPrepConfig cfg = new DataPrepConfig();
        cfg.setUseMaintenanceWindow(true);
        MaintenanceWindow maintenance = new MaintenanceWindow();
        maintenance.setEnabled(true);
        maintenance.setStartHour(22);
        maintenance.setStartMinute(0);
        maintenance.setEndHour(23);
        maintenance.setEndMinute(0);

        assertTrue(cfg.isWindowNow(LocalTime.of(22, 30), maintenance));
        assertFalse(cfg.isWindowNow(LocalTime.of(4, 0), maintenance), "eigenes Fenster ist dann nicht massgeblich");

        // Maintenance-Fenster deaktiviert -> Rueckfall auf das eigene Fenster
        maintenance.setEnabled(false);
        assertTrue(cfg.isWindowNow(LocalTime.of(4, 0), maintenance));
        assertFalse(cfg.isWindowNow(LocalTime.of(22, 30), maintenance));

        // ohne Maintenance-Objekt ebenfalls eigenes Fenster
        assertTrue(cfg.isWindowNow(LocalTime.of(4, 0), null));
    }

    @Test
    void testDisabledIsNeverInWindow()
    {
        DataPrepConfig cfg = new DataPrepConfig();
        cfg.setEnabled(false);
        assertFalse(cfg.isWindowNow(LocalTime.of(4, 0), null));
        cfg.setUseMaintenanceWindow(true);
        MaintenanceWindow allDay = new MaintenanceWindow();
        allDay.setEnabled(true);
        allDay.setStartHour(0);
        allDay.setStartMinute(0);
        allDay.setEndHour(23);
        allDay.setEndMinute(59);
        assertFalse(cfg.isWindowNow(LocalTime.of(4, 0), allDay));
    }

    @Test
    void testRetentionClamp()
    {
        DataPrepConfig cfg = new DataPrepConfig();
        cfg.setRetentionDays(3);
        assertEquals(DataPrepConfig.MIN_RETENTION_DAYS, cfg.getRetentionDays(), "unter der Untergrenze -> Untergrenze");
        cfg.setRetentionDays(0);
        assertEquals(0, cfg.getRetentionDays(), "0 = nie loeschen bleibt erlaubt");
        cfg.setRetentionDays(-5);
        assertEquals(0, cfg.getRetentionDays());
        cfg.setRetentionDays(100_000);
        assertEquals(DataPrepConfig.MAX_RETENTION_DAYS, cfg.getRetentionDays());
        cfg.setRetentionDays(365);
        assertEquals(365, cfg.getRetentionDays());
    }

    @Test
    void testHourAndMinuteClamp()
    {
        DataPrepConfig cfg = new DataPrepConfig();
        cfg.setStartHour(27);
        cfg.setStartMinute(-5);
        cfg.setEndHour(-1);
        cfg.setEndMinute(75);
        assertEquals(23, cfg.getStartHour());
        assertEquals(0, cfg.getStartMinute());
        assertEquals(0, cfg.getEndHour());
        assertEquals(59, cfg.getEndMinute());
    }

    @Test
    void testJsonRoundTrip() throws Exception
    {
        DataPrepConfig cfg = new DataPrepConfig();
        cfg.setEnabled(false);
        cfg.setStartHour(1);
        cfg.setStartMinute(15);
        cfg.setEndHour(2);
        cfg.setEndMinute(45);
        cfg.setUseMaintenanceWindow(true);
        cfg.setRetentionDays(30);

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(cfg);
        assertTrue(json.contains("\"useMaintenanceWindow\":true"), json);
        assertTrue(json.contains("\"retentionDays\":30"), json);

        DataPrepConfig back = mapper.readValue(json, DataPrepConfig.class);
        assertFalse(back.isEnabled());
        assertEquals(1, back.getStartHour());
        assertEquals(15, back.getStartMinute());
        assertEquals(2, back.getEndHour());
        assertEquals(45, back.getEndMinute());
        assertTrue(back.isUseMaintenanceWindow());
        assertEquals(30, back.getRetentionDays());
    }
}
