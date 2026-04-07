package at.mafue.signalreport;

import org.junit.jupiter.api.*;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest
{
    private SessionManager sessionManager;

    @BeforeEach
    void setUp()
    {
        sessionManager = new SessionManager();
    }

    // === Nonce-Tests ===

    @Test
    @DisplayName("Nonce-Generierung: liefert 64-stelligen Hex-String")
    void generateNonce_liefertGueltigesFormat()
    {
        String nonce = sessionManager.generateNonce();

        assertNotNull(nonce);
        assertEquals(64, nonce.length(), "Nonce muss 64 Hex-Zeichen lang sein (32 Bytes)");
        assertTrue(nonce.matches("[0-9a-f]+"), "Nonce darf nur Hex-Zeichen enthalten");
    }

    @Test
    @DisplayName("Nonce-Generierung: jede Nonce ist einzigartig")
    void generateNonce_liefertUniqueWerte()
    {
        String nonce1 = sessionManager.generateNonce();
        String nonce2 = sessionManager.generateNonce();

        assertNotEquals(nonce1, nonce2, "Zwei Nonces duerfen nicht identisch sein");
    }

    @Test
    @DisplayName("Nonce-Validierung: gueltige Nonce wird akzeptiert")
    void validateNonce_akzeptiertGueltigeNonce()
    {
        String nonce = sessionManager.generateNonce();

        assertTrue(sessionManager.validateNonce(nonce), "Frisch generierte Nonce muss gueltig sein");
    }

    @Test
    @DisplayName("Nonce ist Single-Use: zweite Validierung schlaegt fehl")
    void validateNonce_singleUse()
    {
        String nonce = sessionManager.generateNonce();

        assertTrue(sessionManager.validateNonce(nonce), "Erste Validierung muss erfolgreich sein");
        assertFalse(sessionManager.validateNonce(nonce), "Zweite Validierung muss fehlschlagen (Single-Use)");
    }

    @Test
    @DisplayName("Nonce-Validierung: null wird abgelehnt")
    void validateNonce_lehntNullAb()
    {
        assertFalse(sessionManager.validateNonce(null));
    }

    @Test
    @DisplayName("Nonce-Validierung: unbekannte Nonce wird abgelehnt")
    void validateNonce_lehntUnbekannteNonceAb()
    {
        assertFalse(sessionManager.validateNonce("diese-nonce-existiert-nicht"));
    }

    @Test
    @DisplayName("Nonce-Ablauf: abgelaufene Nonce wird abgelehnt (>60s)")
    void validateNonce_lehntAbgelaufeneNonceAb() throws Exception
    {
        String nonce = sessionManager.generateNonce();

        // Nonce-Zeitstempel kuenstlich in die Vergangenheit setzen (>60s)
        Field noncesField = SessionManager.class.getDeclaredField("nonces");
        noncesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Instant> nonces =
                (ConcurrentHashMap<String, Instant>) noncesField.get(sessionManager);
        nonces.put(nonce, Instant.now().minusSeconds(120));

        assertFalse(sessionManager.validateNonce(nonce), "Abgelaufene Nonce (>60s) muss abgelehnt werden");
    }

    // === Session-Tests ===

    @Test
    @DisplayName("Session-Erstellung: liefert gueltigen Token")
    void createSession_liefertToken()
    {
        String token = sessionManager.createSession("admin");

        assertNotNull(token);
        assertEquals(64, token.length(), "Session-Token muss 64 Hex-Zeichen lang sein");
        assertTrue(token.matches("[0-9a-f]+"), "Token darf nur Hex-Zeichen enthalten");
    }

    @Test
    @DisplayName("Session abrufen: gueltige Session wird zurueckgegeben")
    void getSession_liefertGueltigeSession()
    {
        String token = sessionManager.createSession("admin");

        SessionManager.Session session = sessionManager.getSession(token);

        assertNotNull(session, "Session muss abrufbar sein");
        assertEquals("admin", session.getRole());
        assertTrue(session.isAdmin());
    }

    @Test
    @DisplayName("Session-Rollen: Admin vs. User korrekt unterschieden")
    void session_rollenUnterscheidung()
    {
        String adminToken = sessionManager.createSession("admin");
        String userToken = sessionManager.createSession("user");

        SessionManager.Session adminSession = sessionManager.getSession(adminToken);
        SessionManager.Session userSession = sessionManager.getSession(userToken);

        assertTrue(adminSession.isAdmin(), "Admin-Session muss isAdmin() = true liefern");
        assertFalse(userSession.isAdmin(), "User-Session muss isAdmin() = false liefern");
    }

    @Test
    @DisplayName("Session abrufen: null-Token liefert null")
    void getSession_lehntNullAb()
    {
        assertNull(sessionManager.getSession(null));
    }

    @Test
    @DisplayName("Session abrufen: unbekannter Token liefert null")
    void getSession_lehntUnbekanntenTokenAb()
    {
        assertNull(sessionManager.getSession("unbekannter-token"));
    }

    @Test
    @DisplayName("Session invalidieren: nach Logout nicht mehr gueltig")
    void invalidateSession_entferntSession()
    {
        String token = sessionManager.createSession("user");
        assertNotNull(sessionManager.getSession(token), "Session muss vor Invalidierung existieren");

        sessionManager.invalidateSession(token);

        assertNull(sessionManager.getSession(token), "Session darf nach Invalidierung nicht mehr existieren");
    }

    @Test
    @DisplayName("Session invalidieren: null verursacht keinen Fehler")
    void invalidateSession_nullSicher()
    {
        assertDoesNotThrow(() -> sessionManager.invalidateSession(null));
    }

    @Test
    @DisplayName("Session-Timeout: abgelaufene Session wird abgelehnt (>24h)")
    void getSession_lehntAbgelaufeneSessionAb() throws Exception
    {
        String token = sessionManager.createSession("admin");

        // lastAccess kuenstlich auf >24h in der Vergangenheit setzen
        Field sessionsField = SessionManager.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, SessionManager.Session> sessions =
                (ConcurrentHashMap<String, SessionManager.Session>) sessionsField.get(sessionManager);
        SessionManager.Session session = sessions.get(token);

        Field lastAccessField = SessionManager.Session.class.getDeclaredField("lastAccess");
        lastAccessField.setAccessible(true);
        lastAccessField.set(session, Instant.now().minus(java.time.Duration.ofHours(25)));

        assertNull(sessionManager.getSession(token), "Session nach 24h Inaktivitaet muss abgelehnt werden");
    }

    // === Challenge-Response-Tests ===

    @Test
    @DisplayName("Challenge-Response: korrekte Antwort wird akzeptiert")
    void verifyChallengeResponse_akzeptiertKorrekteAntwort()
    {
        String passwort = "meinGeheimesPasswort";
        String storedHash = Config.hashPassword(passwort);     // SHA-256(passwort)
        String nonce = sessionManager.generateNonce();

        // Client berechnet: SHA-256(SHA-256(passwort) + nonce)
        String clientResponse = Config.hashPassword(storedHash + nonce);

        assertTrue(sessionManager.verifyChallengeResponse(storedHash, nonce, clientResponse),
                "Korrekte Challenge-Response muss akzeptiert werden");
    }

    @Test
    @DisplayName("Challenge-Response: falsches Passwort wird abgelehnt")
    void verifyChallengeResponse_lehntFalschesPasswortAb()
    {
        String storedHash = Config.hashPassword("richtiges-passwort");
        String nonce = sessionManager.generateNonce();

        // Client berechnet mit falschem Passwort
        String falscherHash = Config.hashPassword("falsches-passwort");
        String clientResponse = Config.hashPassword(falscherHash + nonce);

        assertFalse(sessionManager.verifyChallengeResponse(storedHash, nonce, clientResponse),
                "Falsches Passwort muss abgelehnt werden");
    }

    @Test
    @DisplayName("Challenge-Response: null-Parameter werden abgelehnt")
    void verifyChallengeResponse_lehntNullAb()
    {
        assertFalse(sessionManager.verifyChallengeResponse(null, "nonce", "response"));
        assertFalse(sessionManager.verifyChallengeResponse("hash", null, "response"));
        assertFalse(sessionManager.verifyChallengeResponse("hash", "nonce", null));
    }

    @Test
    @DisplayName("Challenge-Response: selbes Passwort mit anderer Nonce ergibt andere Antwort")
    void verifyChallengeResponse_nonceVerhindertReplay()
    {
        String storedHash = Config.hashPassword("passwort123");
        String nonce1 = sessionManager.generateNonce();
        String nonce2 = sessionManager.generateNonce();

        String response1 = Config.hashPassword(storedHash + nonce1);
        String response2 = Config.hashPassword(storedHash + nonce2);

        assertNotEquals(response1, response2,
                "Verschiedene Nonces muessen verschiedene Antworten ergeben (Replay-Schutz)");
    }
}
