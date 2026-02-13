package at.mafue.signalreport;

import java.net.InetAddress;

public class PingMeasurer {
    private final String target;

    public PingMeasurer(String target) {
        this.target = target;
    }

    public Measurement measure() throws Exception {
        long start = System.currentTimeMillis();
        boolean reachable = InetAddress.getByName(target).isReachable(3000);
        long latency = System.currentTimeMillis() - start;

        return new Measurement(target, latency, reachable, "PING");
    }
}