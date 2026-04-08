package at.mafue.signalreport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fuehrt ICMP-Ping-Messungen durch.
 *
 * Auf Windows wird InetAddress.isReachable() verwendet, da Windows auch ohne
 * Admin-Rechte echte ICMP-Pakete senden kann und Nachkommastellen liefert.
 *
 * Auf Linux/macOS wird der System-Ping-Befehl (/bin/ping) verwendet, da
 * Java's InetAddress.isReachable() ohne Root-Rechte auf einen TCP-Fallback
 * (Port 7) zurueckfaellt, der bei den meisten Zielen in den Timeout laeuft.
 * Der System-Ping hat ueber das SUID-Bit bzw. die Kernel-Capability
 * CAP_NET_RAW die noetige Berechtigung fuer echte ICMP-Pakete.
 */
public class PingMeasurer implements Measurer
{
    // Pattern fuer Latenz-Extraktion aus der Ping-Ausgabe
    // Linux/macOS: "time=12.3 ms" oder "zeit=12.3 ms"
    private static final Pattern LATENCY_PATTERN = Pattern.compile(
            "(?:time|zeit)[=<](\\d+[.,]?\\d*)\\s*ms", Pattern.CASE_INSENSITIVE);

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase().contains("mac");

    public Measurement measure(String target) throws Exception
    {
        if (IS_WINDOWS)
            {
            return measureWithJava(target);
            }
        else
            {
            return measureWithSystemPing(target);
            }
    }

    /**
     * Windows: InetAddress.isReachable() funktioniert hier korrekt,
     * da Windows auch ohne Admin-Rechte echte ICMP-Pakete senden kann.
     * Liefert praezise Nachkommastellen (z.B. 11.72ms).
     */
    private Measurement measureWithJava(String target) throws Exception
    {
        long start = System.nanoTime();
        try
            {
            boolean reachable = InetAddress.getByName(target).isReachable(3000);
            long end = System.nanoTime();
            double latency = (end - start) / 1_000_000.0;
            return new Measurement(target, latency, reachable, "PING");
            }
        catch (Exception e)
            {
            long end = System.nanoTime();
            double latency = (end - start) / 1_000_000.0;
            return new Measurement(target, latency, false, "PING");
            }
    }

    /**
     * Linux/macOS: System-Ping-Befehl verwenden, da InetAddress.isReachable()
     * ohne Root-Rechte keinen echten ICMP-Ping senden kann.
     * /bin/ping hat die noetigen Rechte ueber SUID-Bit oder CAP_NET_RAW.
     */
    private Measurement measureWithSystemPing(String target) throws Exception
    {
        long start = System.nanoTime();
        try
            {
            ProcessBuilder pb = createPingProcess(target);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Ausgabe lesen und Latenz extrahieren
            double latency = -1;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())))
                {
                String line;
                while ((line = reader.readLine()) != null)
                    {
                    Matcher matcher = LATENCY_PATTERN.matcher(line);
                    if (matcher.find())
                        {
                        String value = matcher.group(1).replace(',', '.');
                        latency = Double.parseDouble(value);
                        }
                    }
                }

            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished)
                {
                process.destroyForcibly();
                long end = System.nanoTime();
                return new Measurement(target, (end - start) / 1_000_000.0, false, "PING");
                }

            int exitCode = process.exitValue();
            boolean success = (exitCode == 0) && (latency >= 0);

            if (success)
                {
                return new Measurement(target, latency, true, "PING");
                }
            else
                {
                long end = System.nanoTime();
                return new Measurement(target, (end - start) / 1_000_000.0, false, "PING");
                }
            }
        catch (Exception e)
            {
            long end = System.nanoTime();
            return new Measurement(target, (end - start) / 1_000_000.0, false, "PING");
            }
    }

    /**
     * Erstellt den plattformspezifischen Ping-Befehl.
     *
     * Linux:  ping -c 1 -W 3 <target>        (-W in Sekunden)
     * macOS:  ping -c 1 -W 3000 <target>     (-W in Millisekunden)
     */
    private ProcessBuilder createPingProcess(String target)
    {
        if (IS_MAC)
            {
            return new ProcessBuilder("ping", "-c", "1", "-W", "3000", target);
            }
        else
            {
            // Linux, NAS (Synology, QNAP), Raspberry Pi OS
            return new ProcessBuilder("ping", "-c", "1", "-W", "3", target);
            }
    }
}
