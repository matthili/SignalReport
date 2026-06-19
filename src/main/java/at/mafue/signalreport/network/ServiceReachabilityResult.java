package at.mafue.signalreport.network;

import at.mafue.signalreport.report.ServiceReachabilityAssessment.Verdict;

/**
 * Ergebnis einer Erreichbarkeits-Probe fuer genau einen Dienst. Wird von
 * {@link ServiceReachabilityProbe} erzeugt und spaeter (Schritt 4) in der Tabelle
 * {@code service_checks} abgelegt sowie ueber die API/UI angezeigt.
 */
public class ServiceReachabilityResult
{
    private final String serviceId;
    private final Verdict verdict;
    private final String method;      // menschenlesbare Kurzbegruendung
    private final String resolvedIp;  // kann null sein
    private final int httpStatus;     // -1, wenn keine HTTP-Antwort vorlag
    private final double latencyMs;

    public ServiceReachabilityResult(String serviceId, Verdict verdict, String method,
                                     String resolvedIp, int httpStatus, double latencyMs)
    {
        this.serviceId = serviceId;
        this.verdict = verdict;
        this.method = method;
        this.resolvedIp = resolvedIp;
        this.httpStatus = httpStatus;
        this.latencyMs = latencyMs;
    }

    public String getServiceId()
    {
        return serviceId;
    }

    public Verdict getVerdict()
    {
        return verdict;
    }

    public String getMethod()
    {
        return method;
    }

    public String getResolvedIp()
    {
        return resolvedIp;
    }

    public int getHttpStatus()
    {
        return httpStatus;
    }

    public double getLatencyMs()
    {
        return latencyMs;
    }
}
