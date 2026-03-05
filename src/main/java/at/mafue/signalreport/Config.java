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
    private static Config instance;

    private MeasurementConfig measurement;
    private DatabaseConfig database;
    private WebserverConfig webserver;
    private List<DnsServer> dnsServers;

    public static Config load(String path) throws IOException {
        if (instance == null) {
            ObjectMapper mapper = new ObjectMapper();
            instance = mapper.readValue(new File(path), Config.class);
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

    // Getter
    public MeasurementConfig getMeasurement() { return measurement; }
    public DatabaseConfig getDatabase() { return database; }
    public WebserverConfig getWebserver() { return webserver; }
    public List<DnsServer> getDnsServers() { return dnsServers; }

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
        public String getDns() { return dns; }
        public String getHttp() { return http; }
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

    // No-arg Konstruktor für Jackson
    public DnsServer() {
        this.provider = "Unknown"; // Default-Wert
    }

    // Konstruktor mit 3 Parametern (für createDefault())
    public DnsServer(String name, String address, String region) {
        this.name = name;
        this.address = address;
        this.region = region;
        this.provider = "Unknown"; // Default-Wert
    }

    // Vollständiger Konstruktor mit 4 Parametern
    public DnsServer(String name, String address, String region, String provider) {
        this.name = name;
        this.address = address;
        this.region = region;
        this.provider = provider != null && !provider.isEmpty() ? provider : "Unknown";
    }

    // Getter + Setter (für Jackson!)
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
        config.dnsServers.add(new DnsServer("Google", "8.8.8.8", "Nordamerika"));
        config.dnsServers.add(new DnsServer("Cloudflare", "1.1.1.1", "Nordamerika"));
        config.dnsServers.add(new DnsServer("Quad9", "9.9.9.9", "Nordamerika"));
        config.dnsServers.add(new DnsServer("OpenDNS", "208.67.222.222", "Nordamerika"));
        config.dnsServers.add(new DnsServer("AdGuard", "176.103.130.130", "Europa"));

        instance = config;
        return config;
    }


}