package at.mafue.signalreport;

import java.time.Instant;

public class Measurement {
    private final Instant timestamp;
    private final String target;
    private final double latencyMs;
    private final boolean success;
    private final String type;

    // Neue Felder für IP-Adressen und Host-Identifikation
    private final String localIPv4;
    private final String localIPv6;
    private final String externalIPv4;
    private final String externalIPv6;
    private final String hostHash;

    // Hauptkonstruktor (ohne IP-Adressen für alte Messungen)
    public Measurement(String target, double latencyMs, boolean success, String type) {
        this(target, latencyMs, success, type,
             NetworkInfo.getLocalIPv4(),
             NetworkInfo.getLocalIPv6(),
             NetworkInfo.getExternalIPv4(),
             NetworkInfo.getExternalIPv6(),
             HostIdentifier.getHostHash());
    }

    // Vollständiger Konstruktor mit allen Feldern
    public Measurement(String target, double latencyMs, boolean success, String type,
                      String localIPv4, String localIPv6, String externalIPv4, String externalIPv6, String hostHash) {
        this.timestamp = Instant.now();
        this.target = target.trim();
        this.latencyMs = latencyMs;
        this.success = success;
        this.type = type;
        this.localIPv4 = localIPv4 != null ? localIPv4 : "unknown";
        this.localIPv6 = localIPv6 != null ? localIPv6 : "unknown";
        this.externalIPv4 = externalIPv4 != null ? externalIPv4 : "unknown";
        this.externalIPv6 = externalIPv6 != null ? externalIPv6 : "unknown";
        this.hostHash = hostHash != null ? hostHash : "unknown";
    }

    // Konstruktor für DB-Laden
    public Measurement(String target, double latencyMs, boolean success, String type,
                      Instant timestamp, String localIPv4, String localIPv6,
                      String externalIPv4, String externalIPv6, String hostHash) {
        this.timestamp = timestamp;
        this.target = target.trim();
        this.latencyMs = latencyMs;
        this.success = success;
        this.type = type;
        this.localIPv4 = localIPv4 != null ? localIPv4 : "unknown";
        this.localIPv6 = localIPv6 != null ? localIPv6 : "unknown";
        this.externalIPv4 = externalIPv4 != null ? externalIPv4 : "unknown";
        this.externalIPv6 = externalIPv6 != null ? externalIPv6 : "unknown";
        this.hostHash = hostHash != null ? hostHash : "unknown";
    }

    // Getter
    public Instant getTimestamp() { return timestamp; }
    public String getTarget() { return target; }
    public double getLatencyMs() { return latencyMs; }
    public boolean isSuccess() { return success; }
    public String getType() { return type; }
    public String getLocalIPv4() { return localIPv4; }
    public String getLocalIPv6() { return localIPv6; }
    public String getExternalIPv4() { return externalIPv4; }
    public String getExternalIPv6() { return externalIPv6; }
    public String getHostHash() { return hostHash; }

    // Für Debug-Ausgabe
    @Override
    public String toString() {
        return String.format("%s,%s,%.1f,%b,%s,%s,%s,%s,%s,%s",
            timestamp, target, latencyMs, success, type,
            localIPv4, localIPv6, externalIPv4, externalIPv6, hostHash);
    }
}