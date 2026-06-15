package at.mafue.signalreport;

import at.mafue.signalreport.config.GatewayConfig;
import at.mafue.signalreport.config.MaintenanceWindow;

import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.i18n.I18n;
import at.mafue.signalreport.measurement.DnsMeasurer;
import at.mafue.signalreport.measurement.HttpMeasurer;
import at.mafue.signalreport.measurement.Measurement;
import at.mafue.signalreport.measurement.PingMeasurer;
import at.mafue.signalreport.network.GatewayDiscovery;
import at.mafue.signalreport.network.HostIdentifier;
import at.mafue.signalreport.network.NetworkInfo;
import at.mafue.signalreport.report.ReliabilityReport;
import at.mafue.signalreport.storage.H2MeasurementRepository;
import at.mafue.signalreport.web.WebServer;

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
        GatewayConfig gwCfg = config.getGateway();
        boolean discoverNear = !gwCfg.isNearManual();
        boolean discoverFar = !gwCfg.isFarManual();
        if (gwCfg.isAutoDiscover() && (discoverNear || discoverFar))
            {
            logger.info("Ermittle lokale Gateways (Traceroute)...");
            java.util.List<String> chain = GatewayDiscovery.discoverLocalChain();
            if (!chain.isEmpty())
                {
                if (discoverNear) gwCfg.setNear(chain.get(0));
                if (discoverFar) gwCfg.setFar(chain.get(chain.size() - 1));
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

        // Lokale IP merken, um bei einem Netzwechsel die Gateways neu zu ermitteln.
        String lastLocalIp = NetworkInfo.getLocalIPv4();
        int round = 1;
        while (true)
            {
            Config currentConfig = Config.getInstance();

            // Lokaler IP-Wechsel? Dann nicht-persistente Gateways neu ermitteln.
            String curLocalIp = NetworkInfo.getLocalIPv4();
            if (lastLocalIp != null && curLocalIp != null && !curLocalIp.equals(lastLocalIp))
                {
                rediscoverGatewaysAfterIpChange(currentConfig, lastLocalIp, curLocalIp);
                }
            lastLocalIp = curLocalIp;

            MaintenanceWindow maintenance = currentConfig.getMaintenanceWindow();

            if (maintenance == null)
                {
                maintenance = new MaintenanceWindow();
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
                    GatewayConfig gw = currentConfig.getGateway();
                    String nearGw = gw.getNear();
                    String farGw = gw.getFar();
                    int gwCount = 0;
                    if (nearGw != null && !nearGw.isBlank())
                        {
                        repo.save(pingMeasurer.measure(nearGw, GatewayDiscovery.TYPE_NEAR));
                        gwCount++;
                        }
                    if (farGw != null && !farGw.isBlank() && !farGw.equals(nearGw) && gw.isFarPingEnabled())
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

    /**
     * Ermittelt nach einem lokalen IP-Wechsel die Gateways neu. Segmente, die
     * manuell UND als persistent markiert sind, bleiben unveraendert; alle anderen
     * werden per Traceroute aktualisiert und verlieren dabei ihr Manuell-Flag.
     * Vollstaendig gekapselt (eigenes try/catch), damit die Messschleife nie bricht.
     */
    private static void rediscoverGatewaysAfterIpChange(Config cfg, String oldIp, String newIp)
    {
        GatewayConfig gw = cfg.getGateway();
        if (!gw.isAutoDiscover())
            {
            return; // Auto-Erkennung global deaktiviert
            }
        boolean keepNear = gw.isNearManual() && gw.isNearPersistent();
        boolean keepFar = gw.isFarManual() && gw.isFarPersistent();
        if (keepNear && keepFar)
            {
            logger.info("Lokaler IP-Wechsel {} -> {}; Gateways bleiben (manuell+persistent).", oldIp, newIp);
            return;
            }
        try
            {
            logger.info("Lokaler IP-Wechsel {} -> {}; ermittle Gateways neu...", oldIp, newIp);
            java.util.List<String> chain = GatewayDiscovery.discoverLocalChain();
            if (chain.isEmpty())
                {
                logger.info("Keine lokalen Gateways nach IP-Wechsel erkannt.");
                return;
                }
            if (!keepNear)
                {
                gw.setNear(chain.get(0));
                gw.setNearManual(false);
                }
            if (!keepFar)
                {
                gw.setFar(chain.get(chain.size() - 1));
                gw.setFarManual(false);
                }
            Config.save(CONFIG_JSON);
            logger.info("Gateways nach IP-Wechsel aktualisiert: nah={}, fern={}", gw.getNear(), gw.getFar());
            } catch (Exception e)
            {
            logger.warn("Gateway-Neuermittlung nach IP-Wechsel fehlgeschlagen: {}", e.getMessage());
            }
    }
}