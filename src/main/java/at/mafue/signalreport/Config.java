package at.mafue.signalreport;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class Config {
    private static volatile Config instance;

    private MeasurementConfig measurement;
    private DatabaseConfig database;
    private WebserverConfig webserver;
    private List<DnsServer> dnsServers;
    private MaintenanceWindow maintenanceWindow;
    private UserInfo userInfo;
    private AuthConfig auth = new AuthConfig();

    public AuthConfig getAuth() { return auth; }
    public void setAuth(AuthConfig auth) { this.auth = auth; }
    private SetupConfig setup = new SetupConfig();

    public SetupConfig getSetup() { return setup; }
    public void setSetup(SetupConfig setup) { this.setup = setup; }
    private PushConfig push = new PushConfig();

    public PushConfig getPush() { return push; }
    public void setPush(PushConfig push) { this.push = push; }

public static Config load(String path) throws IOException {

    if (instance == null) {
        // Sicherstellen, dass data-Verzeichnis existiert
        Files.createDirectories(Paths.get("./data"));

        File configFile = new File(path);
        if (configFile.exists()) {
            // Jackson konfigurieren: Unbekannte Felder ignorieren
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            instance = mapper.readValue(configFile, Config.class);
            System.out.println("✅ Konfiguration geladen (Config): " + configFile.getAbsolutePath());
        } else {
            instance = createDefault();
            save(path);
            System.out.println("✅ Standard-Konfiguration erstellt: " + configFile.getAbsolutePath());
        }
    }
    return instance;
}

    public static void save(String path) throws IOException {
        if (instance != null) {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(new File(path), instance);
        }
    }

    public static Config getInstance() {
        return instance;
    }

    // Getter
    public MeasurementConfig getMeasurement() { return measurement; }
    public DatabaseConfig getDatabase() { return database; }
    public WebserverConfig getWebserver() { return webserver; }
    public List<DnsServer> getDnsServers() { return dnsServers; }
    public MaintenanceWindow getMaintenanceWindow() { return maintenanceWindow; }
    public UserInfo getUserInfo() { return userInfo; }

    // Setter für dynamische Updates
    public void updateTargets(String ping, String dns, String http, int intervalSeconds) {
        this.measurement.targets.ping = ping;
        this.measurement.targets.dns = dns;
        this.measurement.targets.http = http;
        this.measurement.intervalSeconds = intervalSeconds;
    }

    public void updateMaintenanceWindow(boolean enabled, int startHour, int startMinute, int endHour, int endMinute) {
        if (this.maintenanceWindow == null) {
            this.maintenanceWindow = new MaintenanceWindow();
        }
        this.maintenanceWindow.enabled = enabled;
        this.maintenanceWindow.startHour = startHour;
        this.maintenanceWindow.startMinute = startMinute;
        this.maintenanceWindow.endHour = endHour;
        this.maintenanceWindow.endMinute = endMinute;
    }

    public void updateUserInfo(String provider, String customerId, String userName) {
        if (this.userInfo == null) {
            this.userInfo = new UserInfo();
        }
        this.userInfo.provider = provider;
        this.userInfo.customerId = customerId;
        this.userInfo.userName = userName;
    }

    public static class MeasurementConfig {
        @JsonProperty("intervalSeconds")
        private int intervalSeconds;
        private Targets targets;

        public int getIntervalSeconds() { return intervalSeconds; }
        public Targets getTargets() { return targets; }
    }

    public static class Targets {
        private String ping;
        private String dns;
        private String http;

        public String getPing() { return ping; }
        public void setPing(String ping) { this.ping = ping; }

        public String getDns() { return dns; }
        public void setDns(String dns) { this.dns = dns; }

        public String getHttp() { return http; }
        public void setHttp(String http) { this.http = http; }
    }

    public static class DatabaseConfig {
        private String path;

        public String getPath() { return path; }
    }

    public static class WebserverConfig {
        private int port;

        public int getPort() { return port; }
    }

    public static class DnsServer {
        private String name;
        private String address;
        private String region;
        private String provider;

        // Jackson benötigt no-arg Konstruktor
        public DnsServer() {
            this.provider = "Unknown";
        }

        // Konstruktor mit 3 Parametern (für createDefault())
        public DnsServer(String name, String address, String region) {
            this.name = name;
            this.address = address;
            this.region = region;
            this.provider = "Unknown";
        }

        // Vollständiger Konstruktor mit 4 Parametern
        public DnsServer(String name, String address, String region, String provider) {
            this.name = name;
            this.address = address;
            this.region = region;
            this.provider = provider != null && !provider.isEmpty() ? provider : "Unknown";
        }

        // Getter + Setter
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }

        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }

        public String getProvider() { return provider; }
        public void setProvider(String provider) {
            this.provider = provider != null && !provider.isEmpty() ? provider : "Unknown";
        }
    }

    // NEU: MaintenanceWindow-Klasse
    public static class MaintenanceWindow {
        private boolean enabled = false;
        private int startHour = 4;
        private int startMinute = 0;
        private int endHour = 4;
        private int endMinute = 10;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getStartHour() { return startHour; }
        public void setStartHour(int startHour) { this.startHour = startHour; }

        public int getStartMinute() { return startMinute; }
        public void setStartMinute(int startMinute) { this.startMinute = startMinute; }

        public int getEndHour() { return endHour; }
        public void setEndHour(int endHour) { this.endHour = endHour; }

        public int getEndMinute() { return endMinute; }
        public void setEndMinute(int endMinute) { this.endMinute = endMinute; }

        // Prüft, ob aktuelle Zeit im Maintenance-Fenster liegt
        public boolean isMaintenanceTime() {
            if (!enabled) return false;

            java.time.LocalTime now = java.time.LocalTime.now();
            java.time.LocalTime start = java.time.LocalTime.of(startHour, startMinute);
            java.time.LocalTime end = java.time.LocalTime.of(endHour, endMinute);

            if (start.isBefore(end)) {
                // Normales Fenster (z.B. 04:00–04:10)
                return !now.isBefore(start) && now.isBefore(end);
            } else {
                // Über Mitternacht (z.B. 23:00–01:00)
                return !now.isBefore(start) || now.isBefore(end);
            }
        }
    }

    // UserInfo-Klasse
    public static class UserInfo {
        private String provider = "";
        private String customerId = "";
        private String userName = "";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public String getCustomerId() { return customerId; }
        public void setCustomerId(String customerId) { this.customerId = customerId; }

        public String getUserName() { return userName; }
        public void setUserName(String userName) { this.userName = userName; }
    }

    // Standard-Konfiguration erstellen
    public static Config createDefault() {
        Config config = new Config();

        config.measurement = new MeasurementConfig();
        config.measurement.intervalSeconds = 10;

        config.measurement.targets = new Targets();
        config.measurement.targets.ping = "8.8.8.8";
        config.measurement.targets.dns = "google.com";
        config.measurement.targets.http = "https://example.com";

        config.database = new DatabaseConfig();
        config.database.path = "./data/signalreport";

        config.webserver = new WebserverConfig();
        config.webserver.port = 4567;

        config.dnsServers = new ArrayList<>();
        config.dnsServers.add(new DnsServer("Google Primary", "8.8.8.8", "Nordamerika", "Google"));
        config.dnsServers.add(new DnsServer("Google Secondary", "8.8.4.4", "Nordamerika", "Google"));
        config.dnsServers.add(new DnsServer("Cloudflare Primary", "1.1.1.1", "Nordamerika", "Cloudflare"));
        config.dnsServers.add(new DnsServer("Cloudflare Secondary", "1.0.0.1", "Nordamerika", "Cloudflare"));
        config.dnsServers.add(new DnsServer("Quad9 Primary", "9.9.9.9", "Nordamerika", "Quad9"));
        config.dnsServers.add(new DnsServer("Quad9 Secondary", "149.112.112.112", "Nordamerika", "Quad9"));
        config.dnsServers.add(new DnsServer("OpenDNS Primary", "208.67.222.222", "Nordamerika", "Cisco"));
        config.dnsServers.add(new DnsServer("OpenDNS Secondary", "208.67.220.220", "Nordamerika", "Cisco"));
        config.dnsServers.add(new DnsServer("AdGuard Primary", "176.103.130.130", "Europa", "AdGuard"));
        config.dnsServers.add(new DnsServer("AdGuard Secondary", "176.103.130.131", "Europa", "AdGuard"));
        config.dnsServers.add(new DnsServer("DNS.WATCH Primary", "84.200.69.80", "Europa", "DNS.WATCH"));
        config.dnsServers.add(new DnsServer("DNS.WATCH Secondary", "84.200.70.40", "Europa", "DNS.WATCH"));

        // Standard-Werte für Maintenance und UserInfo
        config.maintenanceWindow = new MaintenanceWindow();
        config.userInfo = new UserInfo();

        instance = config;
        return config;
    }

    public static class AuthConfig {
    private boolean enabled = false;
    private String adminPasswordHash = "";
    private String userPasswordHash = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getAdminPasswordHash() { return adminPasswordHash; }
    public void setAdminPasswordHash(String hash) { this.adminPasswordHash = hash; }

    public String getUserPasswordHash() { return userPasswordHash; }
    public void setUserPasswordHash(String hash) { this.userPasswordHash = hash; }

    // Passwort-Hashing
    public static String hashPassword(String password) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(password.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public boolean verifyAdminPassword(String password) {
        return adminPasswordHash.equals(hashPassword(password));
    }

    public boolean verifyUserPassword(String password) {
        return userPasswordHash.equals(hashPassword(password));
    }

}
    public static class PushConfig {
    private boolean enabled = false;
    private double latencyThreshold = 100.0; // ms
    private int consecutiveBadMeasurements = 2;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getLatencyThreshold() { return latencyThreshold; }
    public void setLatencyThreshold(double threshold) { this.latencyThreshold = threshold; }

    public int getConsecutiveBadMeasurements() { return consecutiveBadMeasurements; }
    public void setConsecutiveBadMeasurements(int count) { this.consecutiveBadMeasurements = count; }
}

    public static class SetupConfig {
    private boolean setupCompleted = false;
    private String adminPasswordHash = "";

    public boolean isSetupCompleted() { return setupCompleted; }
    public void setSetupCompleted(boolean completed) { this.setupCompleted = completed; }

    public String getAdminPasswordHash() { return adminPasswordHash; }
    public void setAdminPasswordHash(String hash) { this.adminPasswordHash = hash; }

    public boolean verifyAdminPassword(String password) {
        return adminPasswordHash.equals(hashPassword(password));
    }

    public static String hashPassword(String password) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(password.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }
}

}