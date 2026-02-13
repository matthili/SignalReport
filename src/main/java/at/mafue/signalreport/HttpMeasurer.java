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
        long start = System.currentTimeMillis();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

            long latency = System.currentTimeMillis() - start;
            boolean success = response.statusCode() == 200;

            return new Measurement(url, latency, success, "HTTP");
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            return new Measurement(url, latency, false, "HTTP");
        }
    }
}