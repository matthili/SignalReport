package at.mafue.signalreport;

public class SignalReportApp {
    public static void main(String[] args) throws Exception {
        System.out.println("📡 SignalReport – Starte Mess-Engine und Web-Interface");
        System.out.println("   Datenbank: ./data/signalreport.mv.db");
        System.out.println("   Web-UI:    http://localhost:4567");
        System.out.println("   (Beenden mit STRG+C)\n");

        // Messer initialisieren (OHNE Leerzeichen!)
        PingMeasurer pingMeasurer = new PingMeasurer("8.8.8.8");
        DnsMeasurer dnsMeasurer = new DnsMeasurer("google.com");
        HttpMeasurer httpMeasurer = new HttpMeasurer("https://example.com");

        // Datenbank initialisieren
        H2MeasurementRepository repo = new H2MeasurementRepository();

        // Webserver starten (NUR EINMAL!)
        WebServer webServer = new WebServer(repo);
        webServer.start(4567);

        // Kontinuierliche Messung
        int round = 1;
        while (true) {
            System.out.println("🔄 Messrunde #" + round);

            repo.save(pingMeasurer.measure());
            repo.save(dnsMeasurer.measure());
            repo.save(httpMeasurer.measure());

            System.out.println("   ✅ 3 Messungen gespeichert");
            System.out.println("   ---");

            if (round < 3) {
                Thread.sleep(10_000);
            } else {
                System.out.println("✅ Test beendet nach 3 Runden");
                System.out.println("   Öffne im Browser: http://localhost:4567");
                break;
            }

            round++;
        }
    }
}