package at.mafue.signalreport;

import java.io.File;
import java.io.IOException;

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
                System.out.println("✅ Konfiguration geladen (SignalReportApp): " + configFile.getAbsolutePath());
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

        // Measurer initialisieren (OHNE festes Ziel!)
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
        System.out.println("⏱️  Starte kontinuierliche Messung...\n");

        int round = 1;
        while (true) {
            Config currentConfig = Config.getInstance();
            Config.MaintenanceWindow maintenance = currentConfig.getMaintenanceWindow();

            if (maintenance == null) {
                maintenance = new Config.MaintenanceWindow();
            }

            // 🔑 Maintenance-Prüfung
            if (maintenance.isMaintenanceTime()) {
                System.out.println("⏸️  Maintenance-Fenster aktiv (" +
                    String.format("%02d:%02d", maintenance.getStartHour(), maintenance.getStartMinute()) + "–" +
                    String.format("%02d:%02d", maintenance.getEndHour(), maintenance.getEndMinute()) +
                    ") – überspringe Messung");
            } else {
                // Normale Messung durchführen
                String pingTarget = currentConfig.getMeasurement().getTargets().getPing();
                String dnsTarget = currentConfig.getMeasurement().getTargets().getDns();
                String httpTarget = currentConfig.getMeasurement().getTargets().getHttp();
                int intervalSeconds = currentConfig.getMeasurement().getIntervalSeconds();

                System.out.println("🔄 Messrunde #" + round + " | Ziele: " + pingTarget + ", " + dnsTarget + ", " + httpTarget);

                try {
                    // 🔑 IP-Tracking: Aktuelle externe IP ermitteln
                    String currentExternalIp = NetworkInfo.getExternalIPv4();
                    String hostHash = HostIdentifier.getHostHash();

                    // 🔑 IP-Änderung erkennen und speichern
                    repo.trackIpChange(currentExternalIp, hostHash);

                    // Messungen durchführen
                    repo.save(pingMeasurer.measure(pingTarget));
                    repo.save(dnsMeasurer.measure(dnsTarget));
                    repo.save(httpMeasurer.measure(httpTarget));

                    System.out.println("   ✅ 3 Messungen gespeichert | IP: " + currentExternalIp);
                } catch (Exception e) {
                    System.err.println("   ❌ Fehler bei Messung: " + e.getMessage());
                }
            }

            System.out.println("   ---");

            // Nächstes Intervall warten
            Config updatedConfig = Config.getInstance();
            int nextInterval = updatedConfig.getMeasurement().getIntervalSeconds();
            Thread.sleep(nextInterval * 1000L);
            round++;
        }
    }
}