package at.mafue.signalreport;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class MeasurementTest {

    // Dummy-Werte für IP-Adressen und Host-Hash (für Tests)
    private static final String DUMMY_LOCAL_IPV4 = "192.168.1.100";
    private static final String DUMMY_LOCAL_IPV6 = "fe80::1";
    private static final String DUMMY_EXTERNAL_IPV4 = "85.182.1.1";
    private static final String DUMMY_EXTERNAL_IPV6 = "2001:db8::1";
    private static final String DUMMY_HOST_HASH = "testhash12345678";

    @Test
    void testMeasurementCreation() {
        // Arrange & Act (mit vollem Konstruktor inkl. IPs und Hash)
        Measurement m = new Measurement(
            "8.8.8.8",
            23.5,
            true,
            "PING",
            DUMMY_LOCAL_IPV4,
            DUMMY_LOCAL_IPV6,
            DUMMY_EXTERNAL_IPV4,
            DUMMY_EXTERNAL_IPV6,
            DUMMY_HOST_HASH
        );

        // Assert
        assertNotNull(m.getTimestamp());
        assertEquals("8.8.8.8", m.getTarget());
        assertEquals(23.5, m.getLatencyMs(), 0.01);
        assertTrue(m.isSuccess());
        assertEquals("PING", m.getType());
        assertEquals(DUMMY_LOCAL_IPV4, m.getLocalIPv4());
        assertEquals(DUMMY_HOST_HASH, m.getHostHash());
    }

    @Test
    void testTargetTrimming() {
        // Arrange & Act
        Measurement m1 = new Measurement(
            "  8.8.8.8  ",
            10.0,
            true,
            "PING",
            DUMMY_LOCAL_IPV4,
            DUMMY_LOCAL_IPV6,
            DUMMY_EXTERNAL_IPV4,
            DUMMY_EXTERNAL_IPV6,
            DUMMY_HOST_HASH
        );
        Measurement m2 = new Measurement(
            "google.com",
            15.0,
            true,
            "DNS",
            DUMMY_LOCAL_IPV4,
            DUMMY_LOCAL_IPV6,
            DUMMY_EXTERNAL_IPV4,
            DUMMY_EXTERNAL_IPV6,
            DUMMY_HOST_HASH
        );

        // Assert
        assertEquals("8.8.8.8", m1.getTarget(), "Leerzeichen sollten entfernt werden");
        assertEquals("google.com", m2.getTarget(), "Keine Änderung bei sauberem Target");
    }

    @Test
    void testMeasurementWithTimestamp() {
        // Arrange
        Instant fixedTime = Instant.parse("2026-03-16T14:30:00Z");

        // Act (mit Timestamp + allen IPs/Hash)
        Measurement m = new Measurement(
            "example.com",
            45.6,
            false,
            "HTTP",
            fixedTime,
            DUMMY_LOCAL_IPV4,
            DUMMY_LOCAL_IPV6,
            DUMMY_EXTERNAL_IPV4,
            DUMMY_EXTERNAL_IPV6,
            DUMMY_HOST_HASH
        );

        // Assert
        assertEquals(fixedTime, m.getTimestamp());
        assertFalse(m.isSuccess());
    }

    @Test
    void testToStringFormat() {
        // Arrange
        Instant fixedTime = Instant.parse("2026-03-16T14:30:00Z");
        Measurement m = new Measurement(
            "8.8.8.8",
            23.5,
            true,
            "PING",
            fixedTime,
            DUMMY_LOCAL_IPV4,
            DUMMY_LOCAL_IPV6,
            DUMMY_EXTERNAL_IPV4,
            DUMMY_EXTERNAL_IPV6,
            DUMMY_HOST_HASH
        );

        // Act
        String result = m.toString();

        // Assert
        assertTrue(result.contains("8.8.8.8"), "Target muss enthalten sein");
        assertTrue(result.contains("23.5"), "Latenz muss enthalten sein");
        assertTrue(result.contains("true"), "Erfolg muss enthalten sein");
        assertTrue(result.contains("PING"), "Typ muss enthalten sein");
        // Hinweis: toString() enthält aktuell KEINE IPs/Hash – das ist OK für Tests
    }

    @Test
    void testIpAddressesAndHostHash() {
        // Arrange & Act
        Measurement m = new Measurement(
            "test.target",
            10.0,
            true,
            "TEST",
            DUMMY_LOCAL_IPV4,
            DUMMY_LOCAL_IPV6,
            DUMMY_EXTERNAL_IPV4,
            DUMMY_EXTERNAL_IPV6,
            DUMMY_HOST_HASH
        );

        // Assert
        assertEquals(DUMMY_LOCAL_IPV4, m.getLocalIPv4());
        assertEquals(DUMMY_LOCAL_IPV6, m.getLocalIPv6());
        assertEquals(DUMMY_EXTERNAL_IPV4, m.getExternalIPv4());
        assertEquals(DUMMY_EXTERNAL_IPV6, m.getExternalIPv6());
        assertEquals(DUMMY_HOST_HASH, m.getHostHash());
    }
}