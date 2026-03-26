package at.mafue.signalreport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

public class SignalReportApp
{
    // Logger-Instanz (statisch für die ganze Klasse)
    private static final Logger logger = LoggerFactory.getLogger(SignalReportApp.class);

    static
        {
        System.setProperty("java.awt.headless", "true");
        }

    public static void main(String[] args) throws Exception
    {
        // Logger/Info statt println
        logger.info("📡 SignalReport – Starte Mess-Engine und Web-Interface");
        logger.info("   {}", HostIdentifier.getFullHostInfo());
        logger.info("   (Beenden mit STRG+C)\n");

        // Konfiguration laden oder erstellen
        Config config;
        File configFile = new File("config.json");
        if (configFile.exists())
            {
            try
                {
                config = Config.load("config.json");
                logger.info("Konfiguration geladen: {}", configFile.getAbsolutePath());
                } catch (IOException e)
                {
                logger.error("Fehler beim Laden der Konfiguration: {}", e.getMessage());
                logger.warn("Verwende Standard-Konfiguration...");
                config = Config.createDefault();
                }
            } else
            {
            logger.warn("⚠️  Keine Konfigurationsdatei gefunden – erstelle Standard-Konfiguration...");
            config = Config.createDefault();
            try
                {
                Config.save("config.json");
                logger.info("Standard-Konfiguration erstellt: {}", configFile.getAbsolutePath());
                } catch (IOException e)
                {
                logger.error("Fehler beim Speichern der Konfiguration: {}", e.getMessage());
                }
            }

        // Measurer initialisieren
        PingMeasurer pingMeasurer = new PingMeasurer();
        DnsMeasurer dnsMeasurer = new DnsMeasurer();
        HttpMeasurer httpMeasurer = new HttpMeasurer();

        // Datenbank initialisieren
        H2MeasurementRepository repo = new H2MeasurementRepository(config.getDatabase().getPath());

        // Webserver starten
        WebServer webServer = new WebServer(repo);
        webServer.start(config.getWebserver().getPort());

        // Host registrieren
        repo.registerHost(
                HostIdentifier.getHostHash(),
                HostIdentifier.getHostname(),
                HostIdentifier.getOperatingSystem()
        );

        // Kontinuierliche Messung
        logger.info("Starte kontinuierliche Messung...\n");

        int round = 1;
        while (true)
            {
            Config currentConfig = Config.getInstance();
            Config.MaintenanceWindow maintenance = currentConfig.getMaintenanceWindow();

            if (maintenance == null)
                {
                maintenance = new Config.MaintenanceWindow();
                }

            // Wartungsfenster-Prüfung
            if (maintenance.isMaintenanceTime())
                {
                logger.info("⏸️ Maintenance-Fenster aktiv ({}:{}–{}:{}) – überspringe Messung",
                        String.format("%02d", maintenance.getStartHour()),
                        String.format("%02d", maintenance.getStartMinute()),
                        String.format("%02d", maintenance.getEndHour()),
                        String.format("%02d", maintenance.getEndMinute()));
                } else
                {
                // Normale Messung durchführen
                String pingTarget = currentConfig.getMeasurement().getTargets().getPing();
                String dnsTarget = currentConfig.getMeasurement().getTargets().getDns();
                String httpTarget = currentConfig.getMeasurement().getTargets().getHttp();
                int intervalSeconds = currentConfig.getMeasurement().getIntervalSeconds();

                logger.info("Messrunde #{} | Ziele: {}, {}, {}",
                        round, pingTarget, dnsTarget, httpTarget);

                try
                    {
                    // IP-Tracking
                    String currentExternalIp = NetworkInfo.getExternalIPv4();
                    String hostHash = HostIdentifier.getHostHash();
                    repo.trackIpChange(currentExternalIp, hostHash);

                    // Messungen durchführen
                    repo.save(pingMeasurer.measure(pingTarget));
                    repo.save(dnsMeasurer.measure(dnsTarget));
                    repo.save(httpMeasurer.measure(httpTarget));

                    logger.info("3 Messungen gespeichert | IP: {}", currentExternalIp);
                    } catch (Exception e)
                    {
                    logger.error("Fehler bei Messung: {}", e.getMessage());
                    }
                }

            logger.debug("   ---");

            // auf nächstes Intervall warten
            Config updatedConfig = Config.getInstance();
            int nextInterval = updatedConfig.getMeasurement().getIntervalSeconds();
            Thread.sleep(nextInterval * 1000L);
            round++;
            }
    }
}