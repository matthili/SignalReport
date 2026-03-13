package at.mafue.signalreport;

import org.junit.jupiter.api.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigTest {

    private static final String TEST_CONFIG_PATH = "test-config.json";
    private Config config;

    @BeforeAll
    void setUp() throws IOException {
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
                "provider": "Telekom",
                "customerId": "123456789",
                "userName": "Max Mustermann"
              }
            }
            """;

        Files.writeString(Path.of(TEST_CONFIG_PATH), testConfig);
    }

    @BeforeEach
    void loadConfig() throws IOException {
        config = Config.load(TEST_CONFIG_PATH);
    }

    @AfterAll
    void tearDown() {
        // Test-Datei bereinigen
        File testFile = new File(TEST_CONFIG_PATH);
        if (testFile.exists()) {
            testFile.delete();
        }
    }

    @Test
    void testConfigLoading() {
        assertNotNull(config, "Konfiguration darf nicht null sein");
    }

    @Test
    void testMeasurementTargets() {
        assertEquals("1.1.1.1", config.getMeasurement().getTargets().getPing());
        assertEquals("example.com", config.getMeasurement().getTargets().getDns());
        assertEquals("https://heise.de", config.getMeasurement().getTargets().getHttp());
    }

    @Test
    void testIntervalSeconds() {
        assertEquals(15, config.getMeasurement().getIntervalSeconds());
    }

    @Test
    void testDatabasePath() {
        assertEquals("./data/test-signalreport", config.getDatabase().getPath());
    }

    @Test
    void testWebserverPort() {
        assertEquals(8080, config.getWebserver().getPort());
    }

    @Test
    void testDnsServers() {
        assertNotNull(config.getDnsServers());
        assertFalse(config.getDnsServers().isEmpty());
        assertEquals("Cloudflare", config.getDnsServers().get(0).getName());
        assertEquals("1.1.1.1", config.getDnsServers().get(0).getAddress());
        assertEquals("Nordamerika", config.getDnsServers().get(0).getRegion());
        assertEquals("Cloudflare", config.getDnsServers().get(0).getProvider());
    }

    @Test
    void testMaintenanceWindow() {
        assertNotNull(config.getMaintenanceWindow());
        assertTrue(config.getMaintenanceWindow().isEnabled());
        assertEquals(4, config.getMaintenanceWindow().getStartHour());
        assertEquals(0, config.getMaintenanceWindow().getStartMinute());
        assertEquals(4, config.getMaintenanceWindow().getEndHour());
        assertEquals(10, config.getMaintenanceWindow().getEndMinute());
    }

    @Test
    void testUserInfo() {
        assertNotNull(config.getUserInfo());
        assertEquals("Telekom", config.getUserInfo().getProvider());
        assertEquals("123456789", config.getUserInfo().getCustomerId());
        assertEquals("Max Mustermann", config.getUserInfo().getUserName());
    }

    @Test
    void testConfigUpdateTargets() {
        // Act
        config.updateTargets("8.8.8.8", "google.com", "https://example.com", 30);

        // Assert
        assertEquals("8.8.8.8", config.getMeasurement().getTargets().getPing());
        assertEquals("google.com", config.getMeasurement().getTargets().getDns());
        assertEquals("https://example.com", config.getMeasurement().getTargets().getHttp());
        assertEquals(30, config.getMeasurement().getIntervalSeconds());
    }

    @Test
    void testConfigUpdateMaintenanceWindow() {
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
    void testConfigUpdateUserInfo() {
        // Act
        config.updateUserInfo("Vodafone", "987654321", "Erika Musterfrau");

        // Assert
        assertEquals("Vodafone", config.getUserInfo().getProvider());
        assertEquals("987654321", config.getUserInfo().getCustomerId());
        assertEquals("Erika Musterfrau", config.getUserInfo().getUserName());
    }
}