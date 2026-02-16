package at.mafue.signalreport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HttpMeasurer {
    private final String url;
    private final HttpClient client;

    public HttpMeasurer(String url) {
        this.url = url;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

public Measurement measure() throws Exception {
    long start = System.nanoTime();

    try {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "SignalReport/1.0") // WICHTIG: Verhindert Blockierung
                .GET()
                .build();

        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

        long end = System.nanoTime();
        double latency = (end - start) / 1_000_000.0;
        boolean success = response.statusCode() == 200;

        return new Measurement(url, latency, success, "HTTP");
    } catch (Exception e) {
        long end = System.nanoTime();
        double latency = (end - start) / 1_000_000.0;
        return new Measurement(url, latency, false, "HTTP");
    }
}
}