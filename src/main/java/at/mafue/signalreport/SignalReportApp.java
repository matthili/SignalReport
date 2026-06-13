package at.mafue.signalreport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

public class SignalReportApp
{
    // Logger-Instanz (statisch für die ganze Klasse)
    private static final Logger logger = LoggerFactory.getLogger(SignalReportApp.class);
    protected static final String CONFIG_JSON = "config.json";

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
        File configFile = new File(CONFIG_JSON);
        if (configFile.exists())
            {
            try
                {
                config = Config.load(CONFIG_JSON);
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
                Config.save(CONFIG_JSON);
                logger.info("Standard-Konfiguration erstellt: {}", configFile.getAbsolutePath());
                } catch (IOException e)
                {
                logger.error("Fehler beim Speichern der Konfiguration: {}", e.getMessage());
                }
            }

        // Sprache laden (Web-UI, PDF- und CSV-Texte)
        I18n.load(config.getLanguage());

        // Measurer initialisieren
        PingMeasurer pingMeasurer = new PingMeasurer();
        DnsMeasurer dnsMeasurer = new DnsMeasurer();
        HttpMeasurer httpMeasurer = new HttpMeasurer();

        // Datenbank initialisieren (Twin-DB: Primary + Shadow mit Auto-Recovery)
        H2MeasurementRepository repo = new H2MeasurementRepository(config.getDatabase().getPath());

        // Shutdown-Hook: schliesst beide DBs sauber bei normaler Terminierung
        // (Service-Stop, STRG+C, SIGTERM). Bei abrupten Terminierungen
        // (kill -9, Stromausfall, Windows-Update-Reboot) sorgt die Twin-DB-
        // Architektur dafuer, dass die intakte DB beim Neustart die korrupte
        // automatisch rekonstruiert.
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
        logger.info("Shutdown-Hook: schliesse Datenbanken...");
        try
            {
            repo.close();
            logger.info("Datenbanken sauber geschlossen.");
            } catch (Exception e)
            {
            logger.error("Fehler beim Schliessen der Datenbanken: {}", e.getMessage());
            }
        }, "signalreport-shutdown"));

        // Webserver starten
        WebServer webServer = new WebServer(repo);
        webServer.start(config.getWebserver().getPort());

        // Host registrieren
        repo.registerHost(
                HostIdentifier.getHostHash(),
                HostIdentifier.getHostname(),
                HostIdentifier.getOperatingSystem()
        );

        // Lokale Gateways ermitteln (naechster Router + Pforte ins Internet).
        // Per Traceroute beim Start; das Ergebnis wird in config.json hinterlegt.
        Config.GatewayConfig gwCfg = config.getGateway();
        if (gwCfg.isAutoDiscover())
            {
            logger.info("Ermittle lokale Gateways (Traceroute)...");
            java.util.List<String> chain = GatewayDiscovery.discoverLocalChain();
            if (!chain.isEmpty())
                {
                gwCfg.setNear(chain.get(0));
                gwCfg.setFar(chain.get(chain.size() - 1));
                try
                    {
                    Config.save(CONFIG_JSON);
                    } catch (IOException e)
                    {
                    logger.warn("Gateway-Konfiguration konnte nicht gespeichert werden: {}", e.getMessage());
                    }
                if (gwCfg.getFar().equals(gwCfg.getNear()))
                    {
                    logger.info("Lokaler Gateway erkannt: {}", gwCfg.getNear());
                    } else
                    {
                    logger.info("Lokale Gateways erkannt: nah={}, fern={}", gwCfg.getNear(), gwCfg.getFar());
                    }
                } else
                {
                logger.info("Keine lokalen Gateways erkannt (evtl. direkte oeffentliche IP).");
                }
            }

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
                logger.info("Maintenance-Fenster aktiv ({}:{}–{}:{}) – überspringe Messung",
                        String.format("%02d", maintenance.getStartHour()),
                        String.format("%02d", maintenance.getStartMinute()),
                        String.format("%02d", maintenance.getEndHour()),
                        String.format("%02d", maintenance.getEndMinute()));
                // Marker schreiben: kennzeichnet diese Luecke als GEPLANT (Wartung),
                // damit die Abdeckungs-Berechnung sie nicht als fehlende Daten wertet.
                try
                    {
                    repo.save(new Measurement("maintenance", 0.0, true, ReliabilityReport.TYPE_MAINTENANCE));
                    } catch (Exception e)
                    {
                    logger.error("Maintenance-Marker konnte nicht gespeichert werden: {}", e.getMessage());
                    }
                } else
                {
                // Normale Messung durchführen
                String pingTarget = currentConfig.getMeasurement().getTargets().getPing();
                String dnsTarget = currentConfig.getMeasurement().getTargets().getDns();
                String httpTarget = currentConfig.getMeasurement().getTargets().getHttp();

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

                    // Lokale Gateways messen (eigene Typen, fliessen NICHT in die
                    // Internet-PING-Statistik ein). Lokalisiert eine Stoerung:
                    // ist der eigene Router/das Modem schuld oder der Provider?
                    Config.GatewayConfig gw = currentConfig.getGateway();
                    String nearGw = gw.getNear();
                    String farGw = gw.getFar();
                    int gwCount = 0;
                    if (nearGw != null && !nearGw.isBlank())
                        {
                        repo.save(pingMeasurer.measure(nearGw, GatewayDiscovery.TYPE_NEAR));
                        gwCount++;
                        }
                    if (farGw != null && !farGw.isBlank() && !farGw.equals(nearGw))
                        {
                        repo.save(pingMeasurer.measure(farGw, GatewayDiscovery.TYPE_FAR));
                        gwCount++;
                        }

                    logger.info("{} Messungen gespeichert | IP: {}", 3 + gwCount, currentExternalIp);
                    } catch (Exception e)
                    {
                    logger.error("Fehler bei Messung", e);
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