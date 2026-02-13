package at.mafue.signalreport;

import java.net.InetAddress;

public class DnsMeasurer {
    private final String hostname;

    public DnsMeasurer(String hostname) {
        this.hostname = hostname;
    }

    public Measurement measure() throws Exception {
        long start = System.currentTimeMillis();
        InetAddress address = InetAddress.getByName(hostname); // <-- Das ist der DNS-Lookup
        long latency = System.currentTimeMillis() - start;

        return new Measurement(hostname, latency, true, "DNS");
    }
}