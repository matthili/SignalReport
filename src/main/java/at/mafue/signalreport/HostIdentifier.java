package at.mafue.signalreport;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HostIdentifier
{

    private static String cachedHash = null;
    private static String cachedHostname = null;
    private static String cachedOS = null;

    public static String getHostHash()
    {
        if (cachedHash == null)
            {
            try
                {
                // Hostname ermitteln
                String hostname = getHostname();

                // Betriebssystem ermitteln
                String os = getOperatingSystem();

                // MAC-Adresse des primären Netzwerkadapters (für Stabilität)
                String macAddress = getMacAddress();

                // Kombiniere alle Informationen
                String combined = hostname + "|" + os + "|" + macAddress;

                // SHA-256 Hash berechnen
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hashBytes = digest.digest(combined.getBytes());

                // Hex-String erstellen
                StringBuilder hexString = new StringBuilder();
                for (byte b : hashBytes)
                    {
                    String hex = Integer.toHexString(0xff & b);
                    if (hex.length() == 1) hexString.append('0');
                    hexString.append(hex);
                    }

                cachedHash = hexString.toString().substring(0, 16); // Kürzen auf 16 Zeichen
                } catch (Exception e)
                {
                cachedHash = "unknown";
                }
            }
        return cachedHash;
    }

    private static String getMacAddress()
    {
        // Ansatz 1: MAC-Adresse über das Interface ermitteln, das tatsächlich nach außen routet
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket())
            {
            socket.connect(InetAddress.getByName("8.8.8.8"), 53);
            InetAddress localAddr = socket.getLocalAddress();
            if (localAddr != null && !localAddr.isLoopbackAddress())
                {
                NetworkInterface network = NetworkInterface.getByInetAddress(localAddr);
                if (network != null)
                    {
                    byte[] mac = network.getHardwareAddress();
                    if (mac != null)
                        {
                        return formatMac(mac);
                        }
                    }
                }
            } catch (Exception e)
            {
            // Fallback auf getLocalHost()
            }

        // Ansatz 2: Fallback über getLocalHost()
        try
            {
            InetAddress ip = InetAddress.getLocalHost();
            NetworkInterface network = NetworkInterface.getByInetAddress(ip);
            if (network != null)
                {
                byte[] mac = network.getHardwareAddress();
                if (mac != null)
                    {
                    return formatMac(mac);
                    }
                }
            } catch (UnknownHostException | SocketException e)
            {
            // Fehler ignorieren, "unknown" wird zurückgegeben
            }
        return "unknown";
    }

    private static String formatMac(byte[] mac)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++)
            {
            sb.append(String.format("%02X%s", mac[i], (i < mac.length - 1) ? "-" : ""));
            }
        return sb.toString();
    }

    public static String getHostname()
    {
        if (cachedHostname == null)
            {
            try
                {
                cachedHostname = InetAddress.getLocalHost().getHostName();
                } catch (UnknownHostException e)
                {
                cachedHostname = "unknown";
                }
            }
        return cachedHostname;
    }

    public static String getOperatingSystem()
    {
        if (cachedOS == null)
            {
            cachedOS = System.getProperty("os.name") + " " + System.getProperty("os.version");
            }
        return cachedOS;
    }

    // Für Debugging
    public static String getFullHostInfo()
    {
        return String.format("Hostname: %s | OS: %s | Hash: %s",
                getHostname(), getOperatingSystem(), getHostHash());
    }
}