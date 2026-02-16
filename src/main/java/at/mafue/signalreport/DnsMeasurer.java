package at.mafue.signalreport;

import java.net.InetAddress;

public class DnsMeasurer {
    private final String hostname;

    public DnsMeasurer(String hostname) {
        this.hostname = hostname;
    }

    public Measurement measure() throws Exception {
        long start = System.nanoTime();
        InetAddress address = InetAddress.getByName(hostname);
        long end = System.nanoTime();
        double latency = (end - start) / 1_000_000.0;

    return new Measurement(hostname, latency, true, "DNS");
}
}