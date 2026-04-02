package at.mafue.signalreport;

import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Enumeration;

public class NetworkInfo
{
    private static final long CACHE_DURATION_SECONDS = 120;

    private static String cachedExternalIPv4 = null;
    private static String cachedExternalIPv6 = null;
    private static String cachedLocalIPv4 = null;
    private static String cachedLocalIPv6 = null;
    private static Instant cacheTimestamp = Instant.EPOCH;

    private static synchronized void refreshCacheIfNeeded()
    {
        if (Duration.between(cacheTimestamp, Instant.now()).getSeconds() >= CACHE_DURATION_SECONDS)
            {
            cachedLocalIPv4 = fetchLocalIPv4();
            cachedLocalIPv6 = fetchLocalIPv6();
            cachedExternalIPv4 = fetchExternalIPv4();
            cachedExternalIPv6 = fetchExternalIPv6();
            cacheTimestamp = Instant.now();
            }
    }

    public static synchronized String getLocalIPv4()
    {
        refreshCacheIfNeeded();
        return cachedLocalIPv4;
    }

    public static synchronized String getLocalIPv6()
    {
        refreshCacheIfNeeded();
        return cachedLocalIPv6;
    }

    public static synchronized String getExternalIPv4()
    {
        refreshCacheIfNeeded();
        return cachedExternalIPv4;
    }

    public static synchronized String getExternalIPv6()
    {
        refreshCacheIfNeeded();
        return cachedExternalIPv6;
    }

    // LAN IPv4-Adresse ermitteln
    private static String fetchLocalIPv4()
    {
        try
            {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements())
                {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements())
                    {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress())
                        {
                        return addr.getHostAddress();
                        }
                    }
                }
            } catch (SocketException e)
            {
            // Fehler ignorieren, "unknown" wird zurückgegeben
            }
        return "unknown";
    }

    // LAN IPv6-Adresse ermitteln
    private static String fetchLocalIPv6()
    {
        try
            {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements())
                {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements())
                    {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet6Address && !addr.isLoopbackAddress())
                        {
                        return addr.getHostAddress();
                        }
                    }
                }
            } catch (SocketException e)
            {
            // Fehler ignorieren, "unknown" wird zurückgegeben
            }
        return "unknown";
    }

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // Externe IPv4-Adresse ermitteln (über HTTP-Request)
    private static String fetchExternalIPv4()
    {
        try
            {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.ipify.org"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body().trim();
            } catch (Exception e)
            {
            return "unknown";
            }
    }

    // Externe IPv6-Adresse ermitteln
    private static String fetchExternalIPv6()
    {
        try
            {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api6.ipify.org"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body().trim();
            } catch (Exception e)
            {
            return "unknown";
            }
    }
}