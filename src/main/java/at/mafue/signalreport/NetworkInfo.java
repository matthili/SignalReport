package at.mafue.signalreport;

import java.net.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Enumeration;

public class NetworkInfo
{

    // LAN IPv4-Adresse ermitteln
    public static String getLocalIPv4()
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
            e.printStackTrace();
            }
        return "unknown";
    }

    // LAN IPv6-Adresse ermitteln
    public static String getLocalIPv6()
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
            e.printStackTrace();
            }
        return "unknown";
    }

    // Externe IPv4-Adresse ermitteln (über HTTP-Request)
    public static String getExternalIPv4()
    {
        try
            {
            URL url = new URL("https://api.ipify.org");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream())))
                {
                return reader.readLine();
                }
            } catch (Exception e)
            {
            return "unknown";
            }
    }

    // Externe IPv6-Adresse ermitteln
    public static String getExternalIPv6()
    {
        try
            {
            URL url = new URL("https://api6.ipify.org");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream())))
                {
                return reader.readLine();
                }
            } catch (Exception e)
            {
            return "unknown";
            }
    }
}