package at.mafue.signalreport.measurement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP-Messung der Leitung: eine GET-Anfrage an das konfigurierte Ziel, gemessen wird die Zeit
 * bis zur Antwort.
 * <p>
 * <b>Jede empfangene HTTP-Antwort zaehlt als Erfolg</b>, auch 4xx und 5xx: Die Antwort beweist,
 * dass Verbindung, TLS und HTTP bis zum Server und zurueck funktionieren. Bewertet wird die
 * Leitung, nicht die Gesundheit der Website; ein 403 einer Bot-Abwehr oder ein 503 des
 * Betreibers ist kein Leitungsproblem. Fehlschlag sind nur Timeout (5 s) und Verbindungs-,
 * TLS- oder Namensaufloesungs-Fehler. Bis 2.2.0 galten nur 2xx/3xx als Erfolg, davor nur 200.
 * <p>
 * Antworten ab Status 400 und jeder Fehlschlag werden mit Grund ins Log geschrieben, je Grund
 * hoechstens alle {@value #LOG_THROTTLE_MS} ms, damit die Ursache eines roten HTTP-Werts
 * nachvollziehbar ist (bis 2.2.0 verschwand sie stillschweigend).
 */
public class HttpMeasurer implements Measurer
{
    private static final Logger logger = LoggerFactory.getLogger(HttpMeasurer.class);
    /** Gleicher Grund hoechstens alle 10 Minuten im Log. */
    static final long LOG_THROTTLE_MS = 10 * 60_000L;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client;
    private final Map<String, Long> lastLoggedByReason = new HashMap<>();

    public HttpMeasurer()
    {
        this.client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .sslContext(createTrustAllSslContext())
                .build();
    }

    // To prevent HTTP-measurements from being invalid when SSL-certificates are incorrect.
    private static SSLContext createTrustAllSslContext()
    {
        try
            {
            TrustManager[] trustAll = {new X509TrustManager()
            {
                public X509Certificate[] getAcceptedIssuers()
                {
                    return new X509Certificate[0];
                }

                public void checkClientTrusted(X509Certificate[] certs, String authType)
                {
                }

                public void checkServerTrusted(X509Certificate[] certs, String authType)
                {
                }
            }};

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());
            return sslContext;
            } catch (Exception e)
            {
            logger.warn("Trust-All SSLContext konnte nicht erstellt werden, verwende Standard: {}", e.getMessage());
            try
                {
                return SSLContext.getDefault();
                } catch (Exception ex)
                {
                throw new RuntimeException("SSLContext nicht verfügbar", ex);
                }
            }
    }

    public Measurement measure(String url) throws Exception
    {
        long start = System.nanoTime();
        try
            {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "SignalReport/2.0")
                    .GET()
                    .build();

            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            double latency = (System.nanoTime() - start) / 1_000_000.0;

            int statusCode = response.statusCode();
            if (statusCode >= 400)
                {
                noteThrottled("HTTP " + statusCode, () -> logger.info(
                        "HTTP-Messung {}: Status {} nach {} ms (zaehlt als erreichbar: der Server hat geantwortet).",
                        url, statusCode, Math.round(latency)));
                }
            return new Measurement(url, latency, true, "HTTP");
            } catch (Exception e)
            {
            double latency = (System.nanoTime() - start) / 1_000_000.0;
            String reason = describe(e);
            noteThrottled(reason, () -> logger.warn("HTTP-Messung {} fehlgeschlagen nach {} ms: {}",
                    url, Math.round(latency), reason));
            return new Measurement(url, latency, false, "HTTP");
            }
    }

    /** Kurze Fehlerbeschreibung: Timeouts klar benannt, sonst Ausnahmeklasse plus Meldung. */
    static String describe(Exception e)
    {
        if (e instanceof HttpConnectTimeoutException)
            {
            return "Verbindungs-Timeout (" + TIMEOUT.toSeconds() + " s)";
            }
        if (e instanceof HttpTimeoutException)
            {
            return "Timeout, keine Antwort innerhalb von " + TIMEOUT.toSeconds() + " s";
            }
        String msg = e.getMessage();
        return e.getClass().getSimpleName() + (msg != null && !msg.isBlank() ? ": " + msg : "");
    }

    /** Drosselung je Grund: dieselbe Meldung hoechstens alle {@value #LOG_THROTTLE_MS} ms. */
    private synchronized void noteThrottled(String reason, Runnable log)
    {
        long now = System.currentTimeMillis();
        Long last = lastLoggedByReason.get(reason);
        if (last == null || now - last >= LOG_THROTTLE_MS)
            {
            lastLoggedByReason.put(reason, now);
            log.run();
            }
    }
}
