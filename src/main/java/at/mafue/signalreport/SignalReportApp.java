package at.mafue.signalreport;

import java.io.FileWriter;

public class SignalReportApp {
    public static void main(String[] args) throws Exception {
        System.out.println("SignalReport – Starte kontinuierliche Messung");
        System.out.println("   (Beenden mit STRG+C)\n");

        // Messer initialisieren (einmalig)
        PingMeasurer pingMeasurer = new PingMeasurer("8.8.8.8");
        DnsMeasurer dnsMeasurer = new DnsMeasurer("google.com");
        HttpMeasurer httpMeasurer = new HttpMeasurer("https://heise.de");

        int round = 1;
        while (true) {
            System.out.println("🔄 Messrunde #" + round);

            // Ping
            Measurement ping = pingMeasurer.measure();
            saveToCsv(ping);
            printResult("PING", ping);

            // DNS
            Measurement dns = dnsMeasurer.measure();
            saveToCsv(dns);
            printResult("DNS ", dns);

            // HTTP
            Measurement http = httpMeasurer.measure();
            saveToCsv(http);
            printResult("HTTP", http);

            System.out.println("   ---");

            // 10 Sekunden warten (außer bei letzter Runde zum Testen)
            if (round < 3) {
                Thread.sleep(10_000);
            } else {
                System.out.println("Test beendet nach 3 Runden");
                break;
            }

            round++;
        }
    }

    private static void saveToCsv(Measurement m) throws Exception {
        try (FileWriter fw = new FileWriter("measurements.csv", true)) {
            fw.write(m.toString() + "\n");
        }
    }

    private static void printResult(String type, Measurement m) {
        String status = m.isSuccess() ? "Ja" : "Nein";
        String latency = String.format("%.1f ms", m.getLatencyMs());
        System.out.println("   " + type + " " + status + " " + m.getTarget() + ": " + latency);
    }
}