package at.mafue.signalreport;

import org.junit.jupiter.api.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigTest
{

    private static final String TEST_CONFIG_PATH = "test-config.json";
    private Config config;

    @BeforeAll
    void setUp() throws IOException
    {
        // Test-Konfiguration erstellen
        String testConfig = """
                {
                  "measurement": {
                    "intervalSeconds": 15,
                    "targets": {
                      "ping": "1.1.1.1",
                      "dns": "example.com",
                      "http": "https://heise.de"
                    }
                  },
                  "database": {
                    "path": "./data/test-signalreport"
                  },
                  "webserver": {
                    "port": 8080
                  },
                  "dnsServers": [
                    {
                      "name": "Cloudflare",
                      "address": "1.1.1.1",
                      "region": "Nordamerika",
                      "provider": "Cloudflare"
                    }
                  ],
                  "maintenanceWindow": {
                    "enabled": true,
                    "startHour": 4,
                    "startMinute": 0,
                    "endHour": 4,
                    "endMinute": 10
                  },
                  "userInfo": {
                    "provider": "Vodafone",
                    "customerId": "987654321",
                    "userName": "Erika Musterfrau"
                  }
                }
                """;

        Files.writeString(Path.of(TEST_CONFIG_PATH), testConfig);
    }

    @BeforeEach
    void loadConfig() throws IOException
    {
        config = Config.load(TEST_CONFIG_PATH);
    }

    @AfterAll
    void tearDown()
    {
        // Test-Datei bereinigen
        File testFile = new File(TEST_CONFIG_PATH);
        if (testFile.exists())
            {
            testFile.delete();
            }
    }

    @Test
    void testConfigLoading()
    {
        assertNotNull(config, "Konfiguration darf nicht null sein");
    }

    @Test
    void testMeasurementTargets()
    {
        assertEquals("1.1.1.1", config.getMeasurement().getTargets().getPing());
        assertEquals("example.com", config.getMeasurement().getTargets().getDns());
        assertEquals("https://heise.de", config.getMeasurement().getTargets().getHttp());
    }

    @Test
    void testIntervalSeconds()
    {
        assertEquals(15, config.getMeasurement().getIntervalSeconds());
    }

    @Test
    void testDatabasePath()
    {
        assertEquals("./data/test-signalreport", config.getDatabase().getPath());
    }

    @Test
    void testWebserverPort()
    {
        assertEquals(8080, config.getWebserver().getPort());
    }

    @Test
    void testDnsServers()
    {
        assertNotNull(config.getDnsServers());
        assertFalse(config.getDnsServers().isEmpty());
        assertEquals("Cloudflare", config.getDnsServers().get(0).getName());
        assertEquals("1.1.1.1", config.getDnsServers().get(0).getAddress());
        assertEquals("Nordamerika", config.getDnsServers().get(0).getRegion());
        assertEquals("Cloudflare", config.getDnsServers().get(0).getProvider());
    }

    @Test
    void testMaintenanceWindow()
    {
        assertNotNull(config.getMaintenanceWindow());
        assertTrue(config.getMaintenanceWindow().isEnabled());
        assertEquals(4, config.getMaintenanceWindow().getStartHour());
        assertEquals(0, config.getMaintenanceWindow().getStartMinute());
        assertEquals(4, config.getMaintenanceWindow().getEndHour());
        assertEquals(10, config.getMaintenanceWindow().getEndMinute());
    }

    @Test
    void testUserInfo()
    {
        assertNotNull(config.getUserInfo());
        assertEquals("Vodafone", config.getUserInfo().getProvider());
        assertEquals("987654321", config.getUserInfo().getCustomerId());
        assertEquals("Erika Musterfrau", config.getUserInfo().getUserName());
    }

    @Test
    void testConfigUpdateTargets()
    {
        // Act
        config.updateTargets("8.8.8.8", "google.com", "https://example.com", 30);

        // Assert
        assertEquals("8.8.8.8", config.getMeasurement().getTargets().getPing());
        assertEquals("google.com", config.getMeasurement().getTargets().getDns());
        assertEquals("https://example.com", config.getMeasurement().getTargets().getHttp());
        assertEquals(30, config.getMeasurement().getIntervalSeconds());
    }

    @Test
    void testConfigUpdateMaintenanceWindow()
    {
        // Act
        config.updateMaintenanceWindow(false, 2, 30, 3, 30);

        // Assert
        assertFalse(config.getMaintenanceWindow().isEnabled());
        assertEquals(2, config.getMaintenanceWindow().getStartHour());
        assertEquals(30, config.getMaintenanceWindow().getStartMinute());
        assertEquals(3, config.getMaintenanceWindow().getEndHour());
        assertEquals(30, config.getMaintenanceWindow().getEndMinute());
    }

    @Test
    void testConfigUpdateUserInfo()
    {
        // Act
        config.updateUserInfo("Vodafone", "987654321", "Erika Musterfrau");

        // Assert
        assertEquals("Vodafone", config.getUserInfo().getProvider());
        assertEquals("987654321", config.getUserInfo().getCustomerId());
        assertEquals("Erika Musterfrau", config.getUserInfo().getUserName());
    }

    // --- Neue Tests: hashPassword ---

    @Test
    void testHashPasswordProducesConsistentResult()
    {
        // Gleicher Input muss immer gleichen Hash ergeben
        String hash1 = Config.hashPassword("testPasswort123");
        String hash2 = Config.hashPassword("testPasswort123");

        assertNotNull(hash1);
        assertFalse(hash1.isEmpty(), "Hash darf nicht leer sein");
        assertEquals(hash1, hash2, "Gleiche Passwoerter muessen gleichen Hash erzeugen");
    }

    @Test
    void testHashPasswordDifferentInputs()
    {
        // Verschiedene Passwoerter muessen verschiedene Hashes erzeugen
        String hash1 = Config.hashPassword("passwort1");
        String hash2 = Config.hashPassword("passwort2");

        assertNotEquals(hash1, hash2, "Verschiedene Passwoerter muessen verschiedene Hashes erzeugen");
    }

    @Test
    void testHashPasswordIsSHA256Length()
    {
        // SHA-256 erzeugt immer 64 Hex-Zeichen
        String hash = Config.hashPassword("meinPasswort");
        assertEquals(64, hash.length(), "SHA-256 Hash muss 64 Zeichen lang sein (32 Bytes hex)");
    }

    // --- Neue Tests: Theme-Config ---

    @Test
    void testThemeConfigDefault()
    {
        Config.ThemeConfig theme = new Config.ThemeConfig();
        assertFalse(theme.isDarkMode(), "Dark Mode muss standardmaessig deaktiviert sein");
    }

    @Test
    void testThemeConfigToggle()
    {
        Config.ThemeConfig theme = new Config.ThemeConfig();

        theme.setDarkMode(true);
        assertTrue(theme.isDarkMode(), "Dark Mode muss aktivierbar sein");

        theme.setDarkMode(false);
        assertFalse(theme.isDarkMode(), "Dark Mode muss deaktivierbar sein");
    }

    // --- Neuer Test: Save und Reload ---

    @Test
    void testConfigSaveAndReload() throws IOException
    {
        String savePath = "test-config-save.json";
        try
            {
            // Aendern
            config.updateTargets("9.9.9.9", "heise.de", "https://orf.at", 60);
            config.getTheme().setDarkMode(true);

            // Speichern (ueber statische Methode mit temporaerem Singleton)
            // Da Config.save den Singleton verwendet, setzen wir ihn
            Config.save(savePath);

            // Neu laden
            Config reloaded = Config.load(savePath);

            // Pruefen, dass Aenderungen persistiert wurden
            assertEquals("9.9.9.9", reloaded.getMeasurement().getTargets().getPing());
            assertEquals("heise.de", reloaded.getMeasurement().getTargets().getDns());
            assertEquals("https://orf.at", reloaded.getMeasurement().getTargets().getHttp());
            assertEquals(60, reloaded.getMeasurement().getIntervalSeconds());
            } finally
            {
            new java.io.File(savePath).delete();
            }
    }
}