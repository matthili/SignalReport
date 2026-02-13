package at.mafue.signalreport;

import java.sql.SQLException;

public class SignalReportApp {
    static void main(String[] args) throws Exception {
        System.out.println("📡 SignalReport – Starte Mess-Engine und Web-Interface");
        System.out.println("   Datenbank: ./data/signalreport.mv.db");
        System.out.println("   Web-UI:    http://localhost:4567");
        System.out.println("   (Beenden mit STRG+C)\n");

        // Messer initialisieren
        PingMeasurer pingMeasurer = new PingMeasurer("8.8.8.8");
        DnsMeasurer dnsMeasurer = new DnsMeasurer("google.com");
        HttpMeasurer httpMeasurer = new HttpMeasurer("https://heise.de");

        // Datenbank initialisieren
        H2MeasurementRepository repo = new H2MeasurementRepository();

        // Webserver starten (läuft im Hintergrund)
        WebServer webServer = new WebServer(repo);
        webServer.start(4567);

        // Kontinuierliche Messung
        int round = 1;
        while (true) {
            System.out.println("Messrunde #" + round);

            repo.save(pingMeasurer.measure());
            repo.save(dnsMeasurer.measure());
            repo.save(httpMeasurer.measure());

            System.out.println("   3 Messungen gespeichert");
            System.out.println("   ---");

            if (round < 3) {
                Thread.sleep(10_000);
            } else {
                System.out.println("   Test beendet nach 3 Runden");
                System.out.println("   Öffne im Browser: http://localhost:4567");
                System.out.println("   Die Seite aktualisiert sich automatisch alle 5 Sekunden!");
                // Webserver läuft weiter – zum Beenden STRG+C drücken
                break;
            }

            round++;
        }
    }
}