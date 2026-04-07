package at.mafue.signalreport;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager
{
    private static final long SESSION_TIMEOUT_HOURS = 24;
    private static final long NONCE_TIMEOUT_SECONDS = 60;

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> nonces = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    // Nonce generieren (Einmal-Verwendung, 60 Sekunden gueltig)
    public String generateNonce()
    {
        cleanupExpiredNonces();
        String nonce = randomHex(32);
        nonces.put(nonce, Instant.now());
        return nonce;
    }

    // Nonce validieren und gleichzeitig entfernen (Single-Use)
    public boolean validateNonce(String nonce)
    {
        if (nonce == null) return false;
        Instant created = nonces.remove(nonce);
        if (created == null) return false;
        return Duration.between(created, Instant.now()).getSeconds() < NONCE_TIMEOUT_SECONDS;
    }

    // Session erstellen nach erfolgreichem Login
    public String createSession(String role)
    {
        cleanupExpiredSessions();
        String token = randomHex(32);
        sessions.put(token, new Session(role, Instant.now()));
        return token;
    }

    // Session abrufen (null wenn ungueltig oder abgelaufen)
    public Session getSession(String token)
    {
        if (token == null) return null;
        Session session = sessions.get(token);
        if (session == null) return null;

        if (Duration.between(session.getLastAccess(), Instant.now()).toHours() >= SESSION_TIMEOUT_HOURS)
            {
            sessions.remove(token);
            return null;
            }

        session.touch();
        return session;
    }

    // Session beenden
    public void invalidateSession(String token)
    {
        if (token != null)
            {
            sessions.remove(token);
            }
    }

    // Challenge-Response verifizieren: SHA-256(storedHash + nonce) == challengeResponse
    public boolean verifyChallengeResponse(String storedHash, String nonce, String challengeResponse)
    {
        if (storedHash == null || nonce == null || challengeResponse == null) return false;
        String expected = Config.hashPassword(storedHash + nonce);
        return expected.equals(challengeResponse);
    }

    private String randomHex(int byteCount)
    {
        byte[] bytes = new byte[byteCount];
        random.nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes)
            {
            hex.append(String.format("%02x", b));
            }
        return hex.toString();
    }

    private void cleanupExpiredNonces()
    {
        Instant cutoff = Instant.now().minusSeconds(NONCE_TIMEOUT_SECONDS);
        nonces.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }

    private void cleanupExpiredSessions()
    {
        Instant cutoff = Instant.now().minus(Duration.ofHours(SESSION_TIMEOUT_HOURS));
        sessions.entrySet().removeIf(e -> e.getValue().getLastAccess().isBefore(cutoff));
    }

    // Session-Datenklasse
    public static class Session
    {
        private final String role;
        private final Instant createdAt;
        private Instant lastAccess;

        public Session(String role, Instant createdAt)
        {
            this.role = role;
            this.createdAt = createdAt;
            this.lastAccess = createdAt;
        }

        public String getRole()
        {
            return role;
        }

        public Instant getCreatedAt()
        {
            return createdAt;
        }

        public Instant getLastAccess()
        {
            return lastAccess;
        }

        public void touch()
        {
            this.lastAccess = Instant.now();
        }

        public boolean isAdmin()
        {
            return "admin".equals(role);
        }
    }
}
