package at.mafue.signalreport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class PushNotificationService {
    private static PushNotificationService instance;
    private final List<String> subscribedClients = new CopyOnWriteArrayList<>();
    private boolean enabled = false;
    private double latencyThreshold = 100.0; // ms
    private int consecutiveBadMeasurements = 2;

    private PushNotificationService() {}

    public static synchronized PushNotificationService getInstance() {
        if (instance == null) {
            instance = new PushNotificationService();
        }
        return instance;
    }

    public void enable() {
        this.enabled = true;
        System.out.println("🔔 Push-Benachrichtigungen aktiviert");
    }

    public void disable() {
        this.enabled = false;
        System.out.println("🔕 Push-Benachrichtigungen deaktiviert");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setLatencyThreshold(double threshold) {
        this.latencyThreshold = threshold;
    }

    public double getLatencyThreshold() {
        return latencyThreshold;
    }

    public void setConsecutiveBadMeasurements(int count) {
        this.consecutiveBadMeasurements = count;
    }

    public int getConsecutiveBadMeasurements() {
        return consecutiveBadMeasurements;
    }

    public void subscribeClient(String clientId) {
        if (!subscribedClients.contains(clientId)) {
            subscribedClients.add(clientId);
            System.out.println("✅ Push-Client registriert: " + clientId);
        }
    }

    public void unsubscribeClient(String clientId) {
        subscribedClients.remove(clientId);
        System.out.println("❌ Push-Client entfernt: " + clientId);
    }

    public List<String> getSubscribedClients() {
        return new ArrayList<>(subscribedClients);
    }

    public void sendNotification(String title, String body) {
        if (!enabled) return;

        System.out.println("🔔 Push-Benachrichtigung: " + title + " - " + body);
        // Hier würde die tatsächliche Push-API-Integration kommen
        // (z.B. mit webpush-java Bibliothek für VAPID)
    }

    public void checkAndNotify(String type, double latency, boolean success, int consecutiveFailures) {
        if (!enabled) return;

        if (!success) {
            sendNotification(
                "⚠️ Internet-Ausfall erkannt!",
                type + "-Verbindung unterbrochen"
            );
        } else if (latency > latencyThreshold && consecutiveFailures >= consecutiveBadMeasurements) {
            sendNotification(
                "⚠️ Schlechte Internet-Verbindung",
                type + "-Latenz: " + String.format("%.1f ms", latency) + " (Schwellwert: " + latencyThreshold + " ms)"
            );
        }
    }
}