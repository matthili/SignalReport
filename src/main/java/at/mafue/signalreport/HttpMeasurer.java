package at.mafue.signalreport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

public class HttpMeasurer implements Measurer
{
    private static final Logger logger = LoggerFactory.getLogger(HttpMeasurer.class);
    private final HttpClient client;

    public HttpMeasurer()
    {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .sslContext(createTrustAllSslContext())
                .build();
    }

    private static SSLContext createTrustAllSslContext()
    {
        try
        {
            TrustManager[] trustAll = {new X509TrustManager()
            {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
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
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "SignalReport/1.0")
                    .GET()
                    .build();

            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            long end = System.nanoTime();
            double latency = (end - start) / 1_000_000.0;

            // Wertung als Test-Erfolg bei allen 2xx und 3xx Statuscodes (nicht nur 200!)
            int statusCode = response.statusCode();
            boolean success = statusCode >= 200 && statusCode < 400;

            return new Measurement(url, latency, success, "HTTP");
            } catch (Exception e)
            {
            long end = System.nanoTime();
            double latency = (end - start) / 1_000_000.0;
            return new Measurement(url, latency, false, "HTTP");
            }
    }
}
