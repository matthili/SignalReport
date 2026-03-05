package at.mafue.signalreport;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;

public class SignalReportApp {
    static {
        System.setProperty("java.awt.headless", "true");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("📡 SignalReport – Starte Mess-Engine und Web-Interface");
        System.out.println("   " + HostIdentifier.getFullHostInfo());
        System.out.println("   (Beenden mit STRG+C)\n");

        // Konfiguration laden oder erstellen
        Config config;
        File configFile = new File("config.json");
        if (configFile.exists()) {
            try {
                config = Config.load("config.json");
                System.out.println("✅ Konfiguration geladen: " + configFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("❌ Fehler beim Laden der Konfiguration: " + e.getMessage());
                System.err.println("   Verwende Standard-Konfiguration...");
                config = Config.createDefault();
            }
        } else {
            System.out.println("⚠️  Keine Konfigurationsdatei gefunden – erstelle Standard-Konfiguration...");
            config = Config.createDefault();
            try {
                Config.save("config.json");
                System.out.println("✅ Standard-Konfiguration erstellt: " + configFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("❌ Fehler beim Speichern der Konfiguration: " + e.getMessage());
            }
        }

        // Messer initialisieren (aus Konfiguration)
        PingMeasurer pingMeasurer = new PingMeasurer(config.getMeasurement().getTargets().getPing());
        DnsMeasurer dnsMeasurer = new DnsMeasurer(config.getMeasurement().getTargets().getDns());
        HttpMeasurer httpMeasurer = new HttpMeasurer(config.getMeasurement().getTargets().getHttp());

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
        int intervalSeconds = config.getMeasurement().getIntervalSeconds();
        System.out.println("⏱️  Messintervall: " + intervalSeconds + " Sekunden\n");

        int round = 1;
        while (true) {
            System.out.println("🔄 Messrunde #" + round);

            try {
                repo.save(pingMeasurer.measure());
                repo.save(dnsMeasurer.measure());
                repo.save(httpMeasurer.measure());
                System.out.println("   ✅ 3 Messungen gespeichert");
            } catch (Exception e) {
                System.err.println("   ❌ Fehler bei Messung: " + e.getMessage());
            }

            System.out.println("   ---");

            Thread.sleep(intervalSeconds * 1000L);
            round++;
        }
    }
}