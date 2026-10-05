package at.mafue.signalreport.config;

import at.mafue.signalreport.i18n.I18n;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class Config
{
    private static final Logger logger = LoggerFactory.getLogger(Config.class);
    private static volatile Config instance;

    private MeasurementConfig measurement;
    private DatabaseConfig database;
    private WebserverConfig webserver;
    // DNS-Benchmark: nur der Nutzer-Teil (eigene Server, ausgeblendete); die Liste kommt mit dem Programm
    private DnsBenchmarkConfig dnsBenchmark = new DnsBenchmarkConfig();
    // Altbestand bis 2.2.1: komplette DNS-Liste unter "dnsServers"; nur Lesen, siehe migrateLegacyLists()
    private List<DnsServer> legacyDnsServers;
    private MaintenanceWindow maintenanceWindow;
    private UserInfo userInfo;
    private AuthConfig auth = new AuthConfig();

    public AuthConfig getAuth()
    {
        return auth;
    }

    public void setAuth(AuthConfig auth)
    {
        this.auth = auth;
    }

    private SetupConfig setup = new SetupConfig();

    public SetupConfig getSetup()
    {
        return setup;
    }

    public void setSetup(SetupConfig setup)
    {
        this.setup = setup;
    }

    private PushConfig push = new PushConfig();

    public PushConfig getPush()
    {
        return push;
    }

    public void setPush(PushConfig push)
    {
        this.push = push;
    }

    private ThemeConfig theme = new ThemeConfig();

    public ThemeConfig getTheme()
    {
        return theme;
    }

    public void setTheme(ThemeConfig theme)
    {
        this.theme = theme;
    }

    // Sprache der Benutzeroberflaeche (ISO-Code, z. B. "de", "en", "uk").
    // null bei Konfigurationen aus Vorgaenger-Versionen -> getLanguage() liefert dann "de",
    // damit sich bestehende Installationen beim Update nicht ploetzlich umstellen.
    private String language;

    public String getLanguage()
    {
        return (language == null || language.isBlank()) ? "de" : language;
    }

    public void setLanguage(String language)
    {
        this.language = language;
    }

    public static Config load(String path) throws IOException
    {

        if (instance == null)
            {
            // Sicherstellen, dass data-Verzeichnis existiert
            Files.createDirectories(Paths.get("./data"));

            File configFile = new File(path);
            if (configFile.exists())
                {
                // Jackson konfigurieren: Unbekannte FELDER ignorieren
                ObjectMapper mapper = new ObjectMapper();
                mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                instance = mapper.readValue(configFile, Config.class);
                logger.info("Konfiguration geladen: {}", configFile.getAbsolutePath());
                if (instance.migrateLegacyLists())
                    {
                    try
                        {
                        save(path);
                        logger.info("config.json auf das Format \"mitgelieferte Liste + eigener Teil\" umgestellt.");
                        } catch (IOException e)
                        {
                        // Im Speicher ist die Umstellung erfolgt; der naechste Speichervorgang schreibt sie.
                        logger.warn("config.json konnte nach der Umstellung nicht gespeichert werden: {}", e.getMessage());
                        }
                    }
                } else
                {
                instance = createDefault();
                save(path);
                logger.info("Standard-Konfiguration erstellt: {}", configFile.getAbsolutePath());
                }
            }
        return instance;
    }

    public static void save(String path) throws IOException
    {
        if (instance != null)
            {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(new File(path), instance);
            }
    }

    public static Config getInstance()
    {
        return instance;
    }

    // Passwort-Hashing (wird von AuthConfig und SetupConfig gemeinsam verwendet)
    public static String hashPassword(String password)
    {
        try
            {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(password.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes)
                {
                hex.append(String.format("%02x", b));
                }
            return hex.toString();
            } catch (Exception e)
            {
            return "";
            }
    }

    // Getter
    public MeasurementConfig getMeasurement()
    {
        return measurement;
    }

    public DatabaseConfig getDatabase()
    {
        return database;
    }

    public WebserverConfig getWebserver()
    {
        return webserver;
    }

    /** Nutzer-Teil des DNS-Benchmarks (eigene Server, ausgeblendete mitgelieferte); nie null. */
    public DnsBenchmarkConfig getDnsBenchmark()
    {
        if (this.dnsBenchmark == null)
            {
            this.dnsBenchmark = new DnsBenchmarkConfig();
            }
        return this.dnsBenchmark;
    }

    public void setDnsBenchmark(DnsBenchmarkConfig dnsBenchmark)
    {
        this.dnsBenchmark = dnsBenchmark;
    }

    /** Liest die alte, komplette DNS-Liste aus config.json-Dateien bis 2.2.1 (wird nicht mehr geschrieben). */
    @JsonProperty("dnsServers")
    public void setLegacyDnsServers(List<DnsServer> dnsServers)
    {
        this.legacyDnsServers = dnsServers;
    }

    /**
     * Ueberfuehrt Listen aus config.json-Dateien bis 2.2.1, in denen die komplette DNS- und
     * Dienstliste stand, in das Modell "mitgelieferte Liste + eigener Teil": DNS-Server, die
     * nicht mitgeliefert sind, werden eigene Server; Dienst-Schalter, die vom Standard abweichen,
     * werden gemerkte Abweichungen. Es geht nichts verloren.
     *
     * @return true, wenn config.json neu geschrieben werden sollte (alte Schluessel entfallen dann)
     */
    public boolean migrateLegacyLists()
    {
        boolean changed = false;
        if (legacyDnsServers != null)
            {
            int adopted = getDnsBenchmark().adoptLegacyList(legacyDnsServers);
            logger.info("config.json: alte DNS-Liste mit {} Eintraegen uebernommen, davon {} als eigene Server "
                    + "(die uebrigen sind mitgeliefert).", legacyDnsServers.size(), adopted);
            legacyDnsServers = null;
            changed = true;
            }
        ServiceReachabilityConfig reach = getServiceReachability();
        if (reach.adoptLegacyServices())
            {
            logger.info("config.json: alte Dienstliste uebernommen ({} abweichende Schalter, {} eigene Dienste).",
                    reach.getServiceOverrides().size(), reach.getCustomServices().size());
            changed = true;
            }
        return changed;
    }

    public MaintenanceWindow getMaintenanceWindow()
    {
        return maintenanceWindow;
    }

    public UserInfo getUserInfo()
    {
        return userInfo;
    }

    // Setter für dynamische Updates
    public void updateTargets(String ping, String dns, String http, int intervalSeconds)
    {
        this.measurement.getTargets().setPing(ping);
        this.measurement.getTargets().setDns(dns);
        this.measurement.getTargets().setHttp(http);
        this.measurement.setIntervalSeconds(intervalSeconds);
    }

    public void updateMaintenanceWindow(boolean enabled, int startHour, int startMinute, int endHour, int endMinute)
    {
        if (this.maintenanceWindow == null)
            {
            this.maintenanceWindow = new MaintenanceWindow();
            }
        this.maintenanceWindow.setEnabled(enabled);
        this.maintenanceWindow.setStartHour(startHour);
        this.maintenanceWindow.setStartMinute(startMinute);
        this.maintenanceWindow.setEndHour(endHour);
        this.maintenanceWindow.setEndMinute(endMinute);
    }

    public void updateUserInfo(String provider, String customerId, String userName)
    {
        if (this.userInfo == null)
            {
            this.userInfo = new UserInfo();
            }
        this.userInfo.setProvider(provider);
        this.userInfo.setCustomerId(customerId);
        this.userInfo.setUserName(userName);
    }

    // Lokale Gateway-Ueberwachung (naechster Router + Pforte ins Internet)
    private GatewayConfig gateway = new GatewayConfig();

    public GatewayConfig getGateway()
    {
        if (this.gateway == null)
            {
            this.gateway = new GatewayConfig();
            }
        return this.gateway;
    }

    public void setGateway(GatewayConfig gateway)
    {
        this.gateway = gateway;
    }

    /**
     * Uebernimmt die Gateway-Einstellungen aus dem Web-UI. Eine manuell gesetzte
     * IP wird nur uebernommen, wenn das jeweilige Manuell-Flag gesetzt und das Feld
     * nicht leer ist; andernfalls bleibt die zuletzt bekannte (erkannte) IP erhalten.
     */
    public void updateGateway(boolean nearManual, String near, boolean nearPersistent,
                              boolean farManual, String far, boolean farPersistent,
                              boolean farPingEnabled)
    {
        GatewayConfig gw = getGateway();
        gw.setNearManual(nearManual);
        gw.setNearPersistent(nearPersistent);
        gw.setFarManual(farManual);
        gw.setFarPersistent(farPersistent);
        gw.setFarPingEnabled(farPingEnabled);
        if (nearManual && near != null && !near.isBlank())
            {
            gw.setNear(near.trim());
            }
        if (farManual && far != null && !far.isBlank())
            {
            gw.setFar(far.trim());
            }
    }

    // Periodische Dienst-Erreichbarkeitspruefung (eigener, langsamer Takt; Standard: aus)
    private ServiceReachabilityConfig serviceReachability = new ServiceReachabilityConfig();

    public ServiceReachabilityConfig getServiceReachability()
    {
        if (this.serviceReachability == null)
            {
            this.serviceReachability = new ServiceReachabilityConfig();
            }
        return this.serviceReachability;
    }

    public void setServiceReachability(ServiceReachabilityConfig serviceReachability)
    {
        this.serviceReachability = serviceReachability;
    }

    // Daten-Aufbereitung: Verdichtung zu Stundenwerten, Aufbewahrung, Zeitfenster (Standard: aktiv, 03-05 Uhr, 90 Tage)
    private DataPrepConfig dataPrep = new DataPrepConfig();

    public DataPrepConfig getDataPrep()
    {
        if (this.dataPrep == null)
            {
            this.dataPrep = new DataPrepConfig();
            }
        return this.dataPrep;
    }

    public void setDataPrep(DataPrepConfig dataPrep)
    {
        this.dataPrep = dataPrep;
    }

    /** Uebernimmt die Einstellungen der Daten-Aufbereitung aus dem Web-UI (Werte werden begrenzt). */
    public void updateDataPrep(boolean enabled, int startHour, int startMinute, int endHour, int endMinute,
                               boolean useMaintenanceWindow, int retentionDays)
    {
        DataPrepConfig d = getDataPrep();
        d.setEnabled(enabled);
        d.setStartHour(startHour);
        d.setStartMinute(startMinute);
        d.setEndHour(endHour);
        d.setEndMinute(endMinute);
        d.setUseMaintenanceWindow(useMaintenanceWindow);
        d.setRetentionDays(retentionDays);
    }

    // Standard-Konfiguration erstellen
    public static Config createDefault()
    {
        Config config = new Config();

        // Neuinstallation: Systemsprache verwenden, falls unterstuetzt - sonst Englisch.
        // (Bestehende Installationen ohne language-Feld bleiben ueber getLanguage() auf Deutsch.)
        config.language = I18n.detectOsLanguage();

        config.measurement = new MeasurementConfig();
        config.measurement.setIntervalSeconds(30);

        Targets targets = new Targets();
        targets.setPing("8.8.8.8");
        targets.setDns("google.com");
        targets.setHttp("https://example.com");
        config.measurement.setTargets(targets);

        config.database = new DatabaseConfig();
        config.database.setPath("./data/signalreport");

        config.webserver = new WebserverConfig();
        config.webserver.setPort(4567);

        // DNS-Server fuer den Benchmark: Die Liste kommt mit dem Programm (BuiltinCatalog,
        // /catalog/dns-servers.json); in config.json steht nur der eigene Teil.
        config.dnsBenchmark = new DnsBenchmarkConfig();

        // Standard-Werte für Maintenance und UserInfo
        config.maintenanceWindow = new MaintenanceWindow();
        config.userInfo = new UserInfo();

        instance = config;
        return config;
    }
}
