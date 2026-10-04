package at.mafue.signalreport;

import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.web.api.SystemRoutes;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Kommandozeilen-Stopp: {@code java -jar signalreport.jar stop}.
 * <p>
 * Bittet die laufende Instanz ueber den Loopback-Endpunkt {@link SystemRoutes#SHUTDOWN_PATH}
 * um einen geordneten Stopp (laufende Messrunde beenden, beide Datenbanken sauber
 * schliessen) und wartet, bis der Port frei ist. Der Windows-Dienst (prunsrv,
 * StopMode=exe) ruft genau dieses Kommando auf.
 * <p>
 * Gibt bewusst ueber System.out/err statt ueber den Logger aus: Die Stop-JVM laeuft im
 * selben Datenverzeichnis wie die Instanz und wuerde sonst in dieselbe Logdatei schreiben.
 */
public final class StopCommand
{
    static final int DEFAULT_PORT = 4567;

    private StopCommand()
    {
    }

    /** Liest den Port aus der Konfiguration (Fallback 4567) und stoppt die Instanz. */
    public static boolean run(String configFile)
    {
        int port = resolvePort(configFile);
        return requestShutdown(port, Duration.ofSeconds(90));
    }

    static int resolvePort(String configFile)
    {
        try
            {
            if (configFile != null && new File(configFile).exists())
                {
                return Config.load(configFile).getWebserver().getPort();
                }
            } catch (Exception e)
            {
            System.err.println("[stop] Konfiguration nicht lesbar (" + e.getMessage()
                    + "), verwende Port " + DEFAULT_PORT);
            }
        return DEFAULT_PORT;
    }

    /**
     * Fordert den Stopp an und wartet hoechstens {@code waitForExit}, bis der Port
     * geschlossen ist. Laeuft keine Instanz, gilt das als erfolgreich (nichts zu tun).
     *
     * @return true, wenn danach keine Instanz mehr auf dem Port lauscht
     */
    public static boolean requestShutdown(int port, Duration waitForExit)
    {
        if (!isPortOpen(port))
            {
            System.out.println("[stop] Keine laufende SignalReport-Instanz auf Port " + port + " – nichts zu tun.");
            return true;
            }

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + SystemRoutes.SHUTDOWN_PATH))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try
            {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                {
                System.err.println("[stop] Instanz lehnt den Stopp ab: HTTP " + response.statusCode()
                        + " " + response.body());
                return false;
                }
            System.out.println("[stop] Stopp angefordert, warte auf das Beenden...");
            } catch (IOException e)
            {
            // Die Verbindung kann beim Herunterfahren abreissen; danach zaehlt der Port-Status.
            System.out.println("[stop] Antwort nicht lesbar (" + e.getMessage() + "), pruefe den Port...");
            } catch (InterruptedException e)
            {
            Thread.currentThread().interrupt();
            return false;
            }

        long deadline = System.nanoTime() + waitForExit.toNanos();
        while (System.nanoTime() < deadline)
            {
            if (!isPortOpen(port))
                {
                System.out.println("[stop] SignalReport beendet.");
                return true;
                }
            try
                {
                Thread.sleep(500);
                } catch (InterruptedException e)
                {
                Thread.currentThread().interrupt();
                return false;
                }
            }
        System.err.println("[stop] Instanz laeuft nach " + waitForExit.toSeconds() + " s noch – Abbruch.");
        return false;
    }

    static boolean isPortOpen(int port)
    {
        try (Socket socket = new Socket())
            {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            return true;
            } catch (IOException e)
            {
            return false;
            }
    }
}
