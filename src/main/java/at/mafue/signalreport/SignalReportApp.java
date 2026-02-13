package at.mafue.signalreport;

import java.io.FileWriter;

public class SignalReportApp {
    public static void main(String[] args) throws Exception {
        System.out.println("📡 SignalReport – Messung gestartet\n");

        // Ping-Messung
        PingMeasurer pingMeasurer = new PingMeasurer("8.8.8.8");
        Measurement pingResult = pingMeasurer.measure();
        saveToCsv(pingResult);
        System.out.println("PING  " + pingResult.getTarget() + ": " + pingResult.getLatencyMs() + " ms");

        // DNS-Messung
        DnsMeasurer dnsMeasurer = new DnsMeasurer("google.com");
        Measurement dnsResult = dnsMeasurer.measure();
        saveToCsv(dnsResult);
        System.out.println("DNS   " + dnsResult.getTarget() + ": " + dnsResult.getLatencyMs() + " ms");

        // HTTP-Messung
        HttpMeasurer httpMeasurer = new HttpMeasurer("https://heise.de");
        Measurement httpResult = httpMeasurer.measure();
        saveToCsv(httpResult);
        System.out.println("HTTP  " + httpResult.getTarget() + ": " + httpResult.getLatencyMs() + " ms");

        System.out.println("\nAlle Messungen in measurements.csv gespeichert");
    }

    private static void saveToCsv(Measurement m) throws Exception {
        try (FileWriter fw = new FileWriter("measurements.csv", true)) {
            fw.write(m.toString() + "\n");
        }
    }
}