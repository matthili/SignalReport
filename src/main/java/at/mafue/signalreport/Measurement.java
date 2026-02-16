package at.mafue.signalreport;

import java.time.Instant;

public class Measurement {
    private final Instant timestamp;
    private final String target;
    private final double latencyMs;
    private final boolean success;
    private final String type; // "PING" oder später "DNS", "HTTP"

    // Hauptkonstruktor
    public Measurement(String target, double latencyMs, boolean success, String type) {
        this.timestamp = Instant.now();
        this.target = target.trim(); // 🔑 Entfernt führende/abschließende Leerzeichen
        this.latencyMs = latencyMs;
        this.success = success;
        this.type = type;
    }

    // Konstruktor für DB-Laden
    public Measurement(String target, double latencyMs, boolean success, String type, Instant timestamp) {
        this.timestamp = timestamp;
        this.target = target.trim(); // 🔑 Auch hier trimmen!
        this.latencyMs = latencyMs;
        this.success = success;
        this.type = type;
    }

    // Getter (für späteren Zugriff)
    public Instant getTimestamp() { return timestamp; }
    public String getTarget() { return target; }
    public double getLatencyMs() { return latencyMs; }
    public boolean isSuccess() { return success; }
    public String getType() { return type; }

    // Für Debug-Ausgabe
    @Override
    public String toString() {
        return String.format("%s,%s,%.1f,%b,%s",
            timestamp, target, latencyMs, success, type);
    }
}