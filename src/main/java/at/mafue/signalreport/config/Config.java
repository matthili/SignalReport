package at.mafue.signalreport.config;

import at.mafue.signalreport.i18n.I18n;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class Config
{
    private static final Logger logger = LoggerFactory.getLogger(Config.class);
    private static volatile Config instance;

    private MeasurementConfig measurement;
    private DatabaseConfig database;
    private WebserverConfig webserver;
    private List<DnsServer> dnsServers;
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

    public List<DnsServer> getDnsServers()
    {
        return dnsServers;
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

        config.dnsServers = new ArrayList<>();
        config.dnsServers.add(new DnsServer("Google Primary", "8.8.8.8", "Nordamerika", "Google"));
        config.dnsServers.add(new DnsServer("Google Secondary", "8.8.4.4", "Nordamerika", "Google"));
        config.dnsServers.add(new DnsServer("Cloudflare Primary", "1.1.1.1", "Nordamerika", "Cloudflare"));
        config.dnsServers.add(new DnsServer("Cloudflare Secondary", "1.0.0.1", "Nordamerika", "Cloudflare"));
        config.dnsServers.add(new DnsServer("Quad9 Primary", "9.9.9.9", "Weltweit", "Quad9"));
        config.dnsServers.add(new DnsServer("Quad9 Secondary", "149.112.112.112", "Weltweit", "Quad9"));
        config.dnsServers.add(new DnsServer("OpenDNS Primary", "208.67.222.222", "Nordamerika", "Cisco"));
        config.dnsServers.add(new DnsServer("OpenDNS Secondary", "208.67.220.220", "Nordamerika", "Cisco"));
        config.dnsServers.add(new DnsServer("AdGuard Primary", "94.140.14.14", "Weltweit", "AdGuard"));
        config.dnsServers.add(new DnsServer("AdGuard Secondary", "94.140.15.15", "Weltweit", "AdGuard"));
        config.dnsServers.add(new DnsServer("DNS.WATCH Primary", "84.200.69.80", "Europa", "DNS.WATCH"));
        config.dnsServers.add(new DnsServer("DNS.WATCH Secondary", "84.200.70.40", "Europa", "DNS.WATCH"));
        config.dnsServers.add(new DnsServer("Comodo Secure DNS Primary", "8.26.56.26", "Weltweit", "Comodo"));
        config.dnsServers.add(new DnsServer("Comodo Secure DNS Secondary", "8.20.247.20", "Weltweit", "Comodo"));
        config.dnsServers.add(new DnsServer("CleanBrowsing Primary", "185.228.168.168", "Weltweit", "CleanBrowsing"));
        config.dnsServers.add(new DnsServer("CleanBrowsing Secondary", "185.228.169.168", "Weltweit", "CleanBrowsing"));
        config.dnsServers.add(new DnsServer("Alternate DNS Primary", "76.76.19.19", "Nordamerika", "Alternate DNS"));
        config.dnsServers.add(new DnsServer("Alternate DNS Secondary", "76.223.122.150", "Nordamerika", "Alternate DNS"));
        config.dnsServers.add(new DnsServer("Verisign Primary", "64.6.64.6", "Nordamerika", "Verisign"));
        config.dnsServers.add(new DnsServer("Verisign Secondary", "64.6.65.6", "Nordamerika", "Verisign"));
        config.dnsServers.add(new DnsServer("OpenNIC Primary", "216.87.84.211", "Weltweit", "OpenNIC"));
        config.dnsServers.add(new DnsServer("OpenNIC Secondary", "23.90.4.6", "Weltweit", "OpenNIC"));
        config.dnsServers.add(new DnsServer("CenturyLink/Lumen Primary", "205.171.3.66", "Nordamerika", "Lumen"));
        config.dnsServers.add(new DnsServer("CenturyLink/Lumen Secondary", "205.171.202.166", "Nordamerika", "Lumen"));
        config.dnsServers.add(new DnsServer("Oracle Dyn Primary", "216.146.35.35", "Nordamerika", "Oracle"));
        config.dnsServers.add(new DnsServer("Oracle Dyn Secondary", "216.146.36.36", "Nordamerika", "Oracle"));
        config.dnsServers.add(new DnsServer("UncensoredDNS Primary", "91.239.100.100", "Europa", "UncensoredDNS"));
        config.dnsServers.add(new DnsServer("UncensoredDNS Secondary", "89.233.43.71", "Europa", "UncensoredDNS"));
        config.dnsServers.add(new DnsServer("Gcore Public DNS Primary", "95.85.95.85", "Weltweit", "Gcore"));
        config.dnsServers.add(new DnsServer("Gcore Public DNS Secondary", "2.56.220.2", "Weltweit", "Gcore"));
        config.dnsServers.add(new DnsServer("Neustar Security Services Primary", "156.154.70.5", "Nordamerika", "Neustar"));
        config.dnsServers.add(new DnsServer("Neustar Security Services Secondary", "156.154.71.5", "Nordamerika", "Neustar"));
        config.dnsServers.add(new DnsServer("GreenTeamDNS Primary", "81.218.119.11", "Europa", "GreenTeamDNS"));
        config.dnsServers.add(new DnsServer("GreenTeamDNS Secondary", "209.88.198.133", "Europa", "GreenTeamDNS"));
        config.dnsServers.add(new DnsServer("SafeDNS Primary", "195.46.39.39", "Nordamerika", "SafeDNS"));
        config.dnsServers.add(new DnsServer("SafeDNS Secondary", "195.46.39.40", "Nordamerika", "SafeDNS"));
        config.dnsServers.add(new DnsServer("ControlD Primary", "76.76.2.0", "Weltweit", "ControlD"));
        config.dnsServers.add(new DnsServer("ControlD Secondary", "76.76.10.0", "Weltweit", "ControlD"));
        config.dnsServers.add(new DnsServer("CIRA Canadian Shield Primary", "149.112.121.10", "Nordamerika", "CIRA"));
        config.dnsServers.add(new DnsServer("CIRA Canadian Shield Secondary", "149.112.122.10", "Nordamerika", "CIRA"));
        config.dnsServers.add(new DnsServer("CZ.NIC ODVR Primary", "193.17.47.1", "Europa", "CZ.NIC"));
        config.dnsServers.add(new DnsServer("CZ.NIC ODVR Secondary", "185.43.135.1", "Europa", "CZ.NIC"));
        config.dnsServers.add(new DnsServer("Cloudflare Security Primary", "1.1.1.2", "Weltweit", "Cloudflare"));
        config.dnsServers.add(new DnsServer("Cloudflare Security Secondary", "1.0.0.2", "Weltweit", "Cloudflare"));
        config.dnsServers.add(new DnsServer("Cloudflare Family Primary", "1.1.1.3", "Weltweit", "Cloudflare"));
        config.dnsServers.add(new DnsServer("Cloudflare Family Secondary", "1.0.0.3", "Weltweit", "Cloudflare"));
        config.dnsServers.add(new DnsServer("Applied Privacy DNS Primary", "94.130.106.88", "Europa", "Applied Privacy"));
        config.dnsServers.add(new DnsServer("LibreDNS Primary", "88.198.92.222", "Europa", "LibreOps"));
        config.dnsServers.add(new DnsServer("DNSlify Primary", "185.235.81.1", "Weltweit", "Peerix"));
        config.dnsServers.add(new DnsServer("DNSlify Secondary", "185.235.81.2", "Weltweit", "Peerix"));


        // Standard-Werte für Maintenance und UserInfo
        config.maintenanceWindow = new MaintenanceWindow();
        config.userInfo = new UserInfo();

        instance = config;
        return config;
    }
}
