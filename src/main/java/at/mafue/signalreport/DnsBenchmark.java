package at.mafue.signalreport;

import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class DnsBenchmark
{
    private final List<Config.DnsServer> dnsServers;
    private final String hostname;
    private final int timeoutMs;

    public DnsBenchmark(List<Config.DnsServer> dnsServers, String hostname, int timeoutMs)
    {
        this.dnsServers = dnsServers;
        this.hostname = hostname;
        this.timeoutMs = timeoutMs;
    }

    public List<DnsResult> benchmark() throws Exception
    {
        List<DnsResult> results = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(dnsServers.size());

        List<Future<DnsResult>> futures = new ArrayList<>();

        for (Config.DnsServer server : dnsServers)
            {
            futures.add(executor.submit(() -> measureDnsServer(server)));
            }

        try
            {
            for (Future<DnsResult> future : futures)
                {
                try
                    {
                    results.add(future.get(timeoutMs * 2L, TimeUnit.MILLISECONDS));
                    } catch (TimeoutException e)
                    {
                    // Timeout – überspringen
                    }
                }
            } finally
            {
            executor.shutdown();
            }
        return results;
    }

    private DnsResult measureDnsServer(Config.DnsServer server)
    {
        long start = System.nanoTime();

        try
            {
            // echte Queries auf dem konfigurierten Hostname
            SimpleResolver resolver = new SimpleResolver(server.getAddress());
            resolver.setTimeout(timeoutMs / 1000, timeoutMs % 1000);

            // DNS-Query auf den echten Hostname (z.B. "google.com")
            Record record = Record.newRecord(
                    Name.fromString(hostname + "."),
                    Type.A,
                    DClass.IN
            );

            Message query = Message.newQuery(record);
            Message response = resolver.send(query);

            long end = System.nanoTime();
            double latency = (end - start) / 1_000_000.0;

            // Erfolg bei NOERROR (0) oder NXDOMAIN (3) – beides sind gültige DNS-Antworten
            // NXDOMAIN bedeutet "Domain existiert nicht" – das ist kein Netzwerkfehler!
            int rcode = response.getRcode();
            boolean success = rcode == Rcode.NOERROR || rcode == Rcode.NXDOMAIN;

            return new DnsResult(
                    server.getName(),
                    server.getAddress(),
                    server.getRegion(),
                    server.getProvider(),
                    hostname,
                    latency,
                    success
            );
            } catch (Exception e)
            {
            long end = System.nanoTime();
            double latency = (end - start) / 1_000_000.0;

            return new DnsResult(
                    server.getName(),
                    server.getAddress(),
                    server.getRegion(),
                    server.getProvider(),
                    hostname,
                    latency,
                    false
            );
            }
    }

    public static class DnsResult
    {
        private final String serverName;
        private final String serverAddress;
        private final String region;
        private final String provider;
        private final String hostname;
        private final double latencyMs;
        private final boolean success;

        public DnsResult(String serverName, String serverAddress, String region,
                         String provider, String hostname, double latencyMs, boolean success)
        {
            this.serverName = serverName;
            this.serverAddress = serverAddress;
            this.region = region;
            this.provider = provider;
            this.hostname = hostname;
            this.latencyMs = latencyMs;
            this.success = success;
        }

        // Getter
        public String getServerName()
        {
            return serverName;
        }

        public String getServerAddress()
        {
            return serverAddress;
        }

        public String getRegion()
        {
            return region;
        }

        public String getProvider()
        {
            return provider;
        }

        public String getHostname()
        {
            return hostname;
        }

        public double getLatencyMs()
        {
            return latencyMs;
        }

        public boolean isSuccess()
        {
            return success;
        }
    }
}