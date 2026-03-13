package at.mafue.signalreport;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HostIdentifierTest {

    @Test
    void testHostHashConsistency() {
        // Arrange & Act
        String hash1 = HostIdentifier.getHostHash();
        String hash2 = HostIdentifier.getHostHash();

        // Assert
        assertNotNull(hash1, "Hash darf nicht null sein");
        assertNotNull(hash2, "Hash darf nicht null sein");
        assertEquals(hash1, hash2, "Hash muss konsistent sein (gleicher Rechner)");
        assertEquals(16, hash1.length(), "Hash sollte 16 Zeichen lang sein");
    }

    @Test
    void testHostnameNotEmpty() {
        // Act
        String hostname = HostIdentifier.getHostname();

        // Assert
        assertNotNull(hostname, "Hostname darf nicht null sein");
        assertFalse(hostname.isEmpty(), "Hostname darf nicht leer sein");
        assertFalse(hostname.equals("unknown"), "Hostname sollte ermittelbar sein");
    }

    @Test
    void testOperatingSystemNotEmpty() {
        // Act
        String os = HostIdentifier.getOperatingSystem();

        // Assert
        assertNotNull(os, "OS darf nicht null sein");
        assertFalse(os.isEmpty(), "OS darf nicht leer sein");
        assertFalse(os.equals("unknown"), "OS sollte ermittelbar sein");
    }

    @Test
    void testFullHostInfoFormat() {
        // Act
        String info = HostIdentifier.getFullHostInfo();

        // Assert
        assertNotNull(info, "Host-Info darf nicht null sein");
        assertTrue(info.contains("Hostname:"), "Muss Hostname enthalten");
        assertTrue(info.contains("OS:"), "Muss OS enthalten");
        assertTrue(info.contains("Hash:"), "Muss Hash enthalten");
    }
}