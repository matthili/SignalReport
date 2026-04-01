package at.mafue.signalreport;

import java.net.InetAddress;

public class PingMeasurer implements Measurer
{
    // KEIN festes Ziel mehr – wird bei jeder Messung übergeben
    public Measurement measure(String target) throws Exception
    {
        long start = System.nanoTime();
        boolean reachable = InetAddress.getByName(target).isReachable(3000);
        long end = System.nanoTime();
        double latency = (end - start) / 1_000_000.0;
        return new Measurement(target, latency, reachable, "PING");
    }
}