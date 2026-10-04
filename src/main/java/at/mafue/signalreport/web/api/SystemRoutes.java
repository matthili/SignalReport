package at.mafue.signalreport.web.api;

import at.mafue.signalreport.web.ErrorResponse;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

/**
 * System-Endpunkte. Derzeit nur der geordnete Stopp, den
 * {@code java -jar signalreport.jar stop} (und damit der Windows-Dienst) ausloest.
 * <p>
 * Ausschliesslich von Loopback-Adressen erlaubt. Eine Anmeldung ist nicht noetig, weil
 * die Stop-JVM keine Session besitzt; der Webserver nimmt den Pfad deshalb von der
 * Login-Pflicht aus.
 */
public final class SystemRoutes
{
    private static final Logger logger = LoggerFactory.getLogger(SystemRoutes.class);

    public static final String SHUTDOWN_PATH = "/api/system/shutdown";

    private SystemRoutes()
    {
    }

    public static void register(Javalin app, Runnable stopRequest)
    {
        app.post(SHUTDOWN_PATH, ctx ->
        {
        String ip = ctx.ip();
        if (!isLoopback(ip))
            {
            logger.warn("Stopp-Anforderung von {} abgelehnt (nur localhost erlaubt)", ip);
            ctx.status(403);
            ctx.json(new ErrorResponse("Stopp nur von localhost erlaubt"));
            return;
            }
        logger.info("Stopp-Anforderung von {} angenommen", ip);
        ctx.json(Map.of("status", "stopping"));
        stopRequest.run();
        });
    }

    /**
     * true fuer 127.x.x.x, ::1 und deren Schreibvarianten (auch in eckigen Klammern);
     * false fuer alles andere, fuer Hostnamen (keine DNS-Aufloesung) und fuer null.
     */
    static boolean isLoopback(String ip)
    {
        if (ip == null || ip.isBlank())
            {
            return false;
            }
        String literal = ip.trim();
        if (literal.startsWith("[") && literal.endsWith("]"))
            {
            literal = literal.substring(1, literal.length() - 1);
            }
        // Nur IP-Literale zulassen: ein Hostname wuerde eine DNS-Aufloesung ausloesen
        if (!literal.matches("[0-9A-Fa-f:.%]+"))
            {
            return false;
            }
        try
            {
            return InetAddress.getByName(literal).isLoopbackAddress();
            } catch (UnknownHostException e)
            {
            return false;
            }
    }
}
