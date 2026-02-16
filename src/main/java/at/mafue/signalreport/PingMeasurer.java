package at.mafue.signalreport;

import java.net.InetAddress;

public class PingMeasurer {
    private final String target;

    public PingMeasurer(String target) {
        this.target = target;
    }

public Measurement measure() throws Exception {
    long start = System.nanoTime(); // Präziser Start
    boolean reachable = InetAddress.getByName(target).isReachable(3000);
    long end = System.nanoTime();
    double latency = (end - start) / 1_000_000.0; // Nanosekunden → Millisekunden (als double)

    return new Measurement(target, latency, reachable, "PING");
}
}