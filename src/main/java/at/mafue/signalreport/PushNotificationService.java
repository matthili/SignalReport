package at.mafue.signalreport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class PushNotificationService
{
    // SLF4J Logger-Instanz (statisch für die ganze Klasse)
    private static final Logger logger = LoggerFactory.getLogger(PushNotificationService.class);

    private static PushNotificationService instance;
    private final List<String> subscribedClients = new CopyOnWriteArrayList<>();
    private boolean enabled = false;
    private double latencyThreshold = 100.0; // ms
    private int consecutiveBadMeasurements = 2;

    private PushNotificationService()
    {
        // Privater Konstruktor für Singleton
    }

    public static synchronized PushNotificationService getInstance()
    {
        if (instance == null)
            {
            instance = new PushNotificationService();
            }
        return instance;
    }

    public void enable()
    {
        this.enabled = true;
        logger.info("Push-Benachrichtigungen aktiviert");
    }

    public void disable()
    {
        this.enabled = false;
        logger.info("Push-Benachrichtigungen deaktiviert");
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setLatencyThreshold(double threshold)
    {
        this.latencyThreshold = threshold;
    }

    public double getLatencyThreshold()
    {
        return latencyThreshold;
    }

    public void setConsecutiveBadMeasurements(int count)
    {
        this.consecutiveBadMeasurements = count;
    }

    public int getConsecutiveBadMeasurements()
    {
        return consecutiveBadMeasurements;
    }

    public void subscribeClient(String clientId)
    {
        if (!subscribedClients.contains(clientId))
            {
            subscribedClients.add(clientId);
            logger.info("Push-Client registriert: {}", clientId);
            }
    }

    public void unsubscribeClient(String clientId)
    {
        subscribedClients.remove(clientId);
        logger.info("Push-Client entfernt: {}", clientId);
    }

    public List<String> getSubscribedClients()
    {
        return new ArrayList<>(subscribedClients);
    }

    public void sendNotification(String title, String body)
    {
        if (!enabled) return;

        logger.info("Push-Benachrichtigung: {} - {}", title, body);
        // Hier würde die tatsächliche Push-API-Integration Platz finden
        // (z.B. mit webpush-java Bibliothek für VAPID)
        // für dieses Projekt war es leider nicht praxistauglich umsetzbar, da aktuell nur mit SSL-Zertifikat möglich.
    }

    public void checkAndNotify(String type, double latency, boolean success, int consecutiveFailures)
    {
        if (!enabled) return;

        if (!success)
            {
            logger.warn("⚠️ Internet-Ausfall erkannt! {}-Verbindung unterbrochen", type);
            } else if (latency > latencyThreshold && consecutiveFailures >= consecutiveBadMeasurements)
            {
            logger.warn("⚠️ Schlechte Internet-Verbindung: {}-Latenz: {:.1f} ms (Schwellwert: {} ms)",
                    type, latency, latencyThreshold);
            }
    }
}