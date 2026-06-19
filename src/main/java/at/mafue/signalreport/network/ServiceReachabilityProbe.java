package at.mafue.signalreport.network;

import at.mafue.signalreport.config.ServiceReachabilityConfig;
import at.mafue.signalreport.config.ServiceTarget;
import at.mafue.signalreport.config.ServiceTarget.ServiceKind;
import at.mafue.signalreport.report.ServiceReachabilityAssessment;
import at.mafue.signalreport.report.ServiceReachabilityAssessment.Observation;
import at.mafue.signalreport.report.ServiceReachabilityAssessment.Verdict;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Fuehrt die mehrstufige Erreichbarkeits-Probe fuer einen Dienst aus und leitet
 * daraus ueber {@link ServiceReachabilityAssessment} ein Verdikt ab.
 * <p>
 * Schichten, von innen nach aussen: <ol>
 *   <li><b>DNS</b> -- Aufloesung ueber den System-/ISP-Resolver und einen
 *       oeffentlichen Resolver (1.1.1.1); loest nur der oeffentliche auf bzw.
 *       liefert der ISP eine private/Loopback-IP, deutet das auf eine DNS-Sperre.</li>
 *   <li><b>TCP</b> -- Verbindungsaufbau zur aufgeloesten IP.</li>
 *   <li><b>TLS/SNI</b> -- Handshake mit dem echten SNI; scheitert er, wird
 *       optional ein harmloses Kontroll-SNI zur selben IP getestet (klappt es,
 *       liegt eine SNI-/DPI-Sperre vor).</li>
 *   <li><b>HTTP</b> -- Statuscode/Sperrseite (nur WEB/CONTROL; Messenger gelten
 *       bei TLS-Erfolg als erreichbar).</li>
 * </ol>
 * Zertifikate werden bewusst nicht geprueft -- gemessen wird Erreichbarkeit, nicht
 * Cert-Gueltigkeit (gleicher Ansatz wie in {@code HttpMeasurer}).
 */
public class ServiceReachabilityProbe
{
    private static final Logger logger = LoggerFactory.getLogger(ServiceReachabilityProbe.class);

    private static final String PUBLIC_RESOLVER = "1.1.1.1";
    private static final String CONTROL_DOMAIN = "example.com";
    private static final String CONTROL_SNI = "example.com";
    private static final String USER_AGENT = "SignalReport/2.0";

    private static final int DNS_TIMEOUT_MS = 4000;
    private static final int TCP_TIMEOUT_MS = 5000;
    private static final int TLS_TIMEOUT_MS = 5000;
    private static final int HTTP_TIMEOUT_MS = 5000;
    private static final long PER_SERVICE_TIMEOUT_MS = 25000L;

    // Sehr konservative Sperrseiten-Marker: Phrasen, die auf normalen Startseiten
    // praktisch nie vorkommen. Nur in Kombination mit einer kurzen Antwort gewertet.
    private static final String[] BLOCKPAGE_MARKERS = {
            "behoerdlich gesperrt", "behördlich gesperrt", "richterliche anordnung",
            "diese seite wurde gesperrt", "zugang gesperrt", "site is blocked",
            "this site has been blocked", "access to this site is denied",
            "blocked by order", "this website is not available in your country"
    };

    private final HttpClient httpClient;
    private final SSLContext sslContext;

    public ServiceReachabilityProbe()
    {
        this.sslContext = createTrustAllSslContext();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(TCP_TIMEOUT_MS))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .sslContext(sslContext)
                .build();
    }

    /** Prueft alle aktivierten Dienste parallel (Virtual Threads). */
    public List<ServiceReachabilityResult> probeAll(ServiceReachabilityConfig cfg)
    {
        List<ServiceTarget> active = new ArrayList<>();
        for (ServiceTarget t : cfg.getServices())
            {
            if (t.isEnabled())
                {
                active.add(t);
                }
            }

        boolean controlReachable = quickReachable(CONTROL_DOMAIN);
        boolean useControlSni = cfg.isUseControlSni();

        List<ServiceReachabilityResult> results = new ArrayList<>();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try
            {
            List<Future<ServiceReachabilityResult>> futures = new ArrayList<>();
            for (ServiceTarget t : active)
                {
                futures.add(executor.submit(() -> probeOne(t, controlReachable, useControlSni)));
                }
            for (int i = 0; i < futures.size(); i++)
                {
                try
                    {
                    results.add(futures.get(i).get(PER_SERVICE_TIMEOUT_MS, TimeUnit.MILLISECONDS));
                    } catch (Exception e)
                    {
                    ServiceTarget t = active.get(i);
                    results.add(new ServiceReachabilityResult(t.getId(), Verdict.UNKNOWN,
                            "Timeout/Fehler", null, -1, 0.0));
                    }
                }
            } finally
            {
            executor.shutdown();
            }
        return results;
    }

    /** Prueft genau einen Dienst und liefert das Verdikt samt Kurzbegruendung. */
    public ServiceReachabilityResult probeOne(ServiceTarget target, boolean controlReachable, boolean useControlSni)
    {
        long start = System.nanoTime();
        String domain = target.getDomain();
        Observation o = new Observation().controlReachable(controlReachable);

        // 1. DNS: System-/ISP-Resolver vs. oeffentlicher Resolver
        List<InetAddress> ispIps = resolve(null, domain);
        List<InetAddress> pubIps = resolve(PUBLIC_RESOLVER, domain);
        boolean ispResolved = !ispIps.isEmpty();
        boolean ispBogus = ispResolved && allBogus(ispIps);
        boolean publicResolved = !pubIps.isEmpty();
        o.dns(ispResolved, ispBogus, publicResolved);

        InetAddress ip = pickIp(pubIps, ispIps);
        String resolvedIp = (ip != null) ? ip.getHostAddress() : null;
        if (ip == null)
            {
            return finish(target, o, resolvedIp, -1, start, "kein aufloesbares Ziel");
            }

        // 2. TCP
        boolean tcp = tcpConnect(ip, target.getPort());
        o.tcp(tcp);
        if (!tcp)
            {
            return finish(target, o, resolvedIp, -1, start, "TCP-Connect fehlgeschlagen");
            }

        // 3. TLS mit echtem SNI (bei Fehlschlag optional Kontroll-SNI zum Vergleich)
        boolean tlsReal = tlsHandshake(ip, target.getPort(), domain);
        o.tlsRealSni(tlsReal);
        if (!tlsReal)
            {
            if (useControlSni)
                {
                boolean tlsCtrl = tlsHandshake(ip, target.getPort(), CONTROL_SNI);
                o.tlsControlSni(true, tlsCtrl);
                }
            return finish(target, o, resolvedIp, -1, start, "TLS mit echtem SNI fehlgeschlagen");
            }

        // 4. HTTP -- nur fuer WEB/CONTROL; Messenger gelten bei TLS-Erfolg als erreichbar
        if (target.getKind() == ServiceKind.MESSENGER)
            {
            o.http(200);
            return finish(target, o, resolvedIp, 200, start, "Endpunkt erreichbar (TLS)");
            }

        HttpProbe hp = httpGet("https://" + domain + "/");
        o.http(hp.status());
        o.blockpage(hp.blockpage());
        String detail = "HTTP " + hp.status() + (hp.blockpage() ? " (Sperrseite erkannt)" : "");
        return finish(target, o, resolvedIp, hp.status(), start, detail);
    }

    private ServiceReachabilityResult finish(ServiceTarget t, Observation o, String ip,
                                             int httpStatus, long startNanos, String detail)
    {
        Verdict v = ServiceReachabilityAssessment.classify(o);
        double latency = (System.nanoTime() - startNanos) / 1_000_000.0;
        return new ServiceReachabilityResult(t.getId(), v, detail, ip, httpStatus, latency);
    }

    // --- DNS ---

    private List<InetAddress> resolve(String resolverAddress, String domain)
    {
        List<InetAddress> out = new ArrayList<>();
        try
            {
            SimpleResolver resolver = (resolverAddress == null)
                    ? new SimpleResolver()
                    : new SimpleResolver(resolverAddress);
            resolver.setTimeout(Duration.ofMillis(DNS_TIMEOUT_MS));

            Record question = Record.newRecord(Name.fromString(domain + "."), Type.A, DClass.IN);
            Message response = resolver.send(Message.newQuery(question));
            if (response.getRcode() == Rcode.NOERROR)
                {
                for (Record r : response.getSection(Section.ANSWER))
                    {
                    if (r instanceof ARecord a)
                        {
                        out.add(a.getAddress());
                        }
                    }
                }
            } catch (Exception e)
            {
            // Aufloesung fehlgeschlagen -> leere Liste (vom Aufrufer als "nicht aufgeloest" gewertet)
            }
        return out;
    }

    private static boolean allBogus(List<InetAddress> ips)
    {
        if (ips.isEmpty())
            {
            return false;
            }
        for (InetAddress a : ips)
            {
            if (!isBogus(a))
                {
                return false;
                }
            }
        return true;
    }

    private static boolean isBogus(InetAddress a)
    {
        return a.isAnyLocalAddress() || a.isLoopbackAddress()
                || a.isSiteLocalAddress() || a.isLinkLocalAddress();
    }

    private static InetAddress pickIp(List<InetAddress> pub, List<InetAddress> isp)
    {
        for (InetAddress a : pub)
            {
            if (!isBogus(a))
                {
                return a;
                }
            }
        for (InetAddress a : isp)
            {
            if (!isBogus(a))
                {
                return a;
                }
            }
        if (!pub.isEmpty())
            {
            return pub.get(0);
            }
        if (!isp.isEmpty())
            {
            return isp.get(0);
            }
        return null;
    }

    // --- TCP / TLS ---

    private boolean quickReachable(String domain)
    {
        try
            {
            InetAddress ip = InetAddress.getByName(domain);
            return tcpConnect(ip, 443);
            } catch (Exception e)
            {
            return false;
            }
    }

    private boolean tcpConnect(InetAddress ip, int port)
    {
        try (Socket s = new Socket())
            {
            s.connect(new InetSocketAddress(ip, port), TCP_TIMEOUT_MS);
            return true;
            } catch (Exception e)
            {
            return false;
            }
    }

    private boolean tlsHandshake(InetAddress ip, int port, String sni)
    {
        Socket plain = new Socket();
        try
            {
            plain.connect(new InetSocketAddress(ip, port), TCP_TIMEOUT_MS);
            SSLSocketFactory factory = sslContext.getSocketFactory();
            try (SSLSocket ssl = (SSLSocket) factory.createSocket(plain, sni, port, true))
                {
                ssl.setSoTimeout(TLS_TIMEOUT_MS);
                SSLParameters params = ssl.getSSLParameters();
                params.setServerNames(List.<SNIServerName>of(new SNIHostName(sni)));
                ssl.setSSLParameters(params);
                ssl.startHandshake();
                return true;
                }
            } catch (Exception e)
            {
            return false;
            } finally
            {
            try
                {
                if (!plain.isClosed())
                    {
                    plain.close();
                    }
                } catch (Exception ignore)
                {
                }
            }
    }

    // --- HTTP ---

    private HttpProbe httpGet(String url)
    {
        try
            {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(HTTP_TIMEOUT_MS))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new HttpProbe(response.statusCode(), looksLikeBlockpage(response.body()));
            } catch (Exception e)
            {
            return new HttpProbe(-1, false);
            }
    }

    private static boolean looksLikeBlockpage(String body)
    {
        if (body == null || body.length() > 20000)
            {
            return false; // echte Sperrseiten sind typischerweise klein
            }
        String lower = body.toLowerCase();
        for (String marker : BLOCKPAGE_MARKERS)
            {
            if (lower.contains(marker))
                {
                return true;
                }
            }
        return false;
    }

    private record HttpProbe(int status, boolean blockpage)
    {
    }

    // Trust-All-SSLContext: spiegelt bewusst den Ansatz aus HttpMeasurer, da wir
    // Erreichbarkeit (Handshake kommt zustande?) messen, nicht Cert-Gueltigkeit.
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
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            return context;
            } catch (Exception e)
            {
            logger.warn("Trust-All SSLContext nicht erstellbar, verwende Standard: {}", e.getMessage());
            try
                {
                return SSLContext.getDefault();
                } catch (Exception ex)
                {
                throw new RuntimeException("SSLContext nicht verfuegbar", ex);
                }
            }
    }
}
