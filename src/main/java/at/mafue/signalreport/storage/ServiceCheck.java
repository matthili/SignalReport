package at.mafue.signalreport.storage;

import java.time.Instant;

/**
 * Eine gespeicherte Erreichbarkeits-Pruefung (eine Zeile der Tabelle
 * {@code service_checks}).
 * <p>
 * Das Verdikt wird bewusst als String (Name des report-Enums) gehalten, damit das
 * storage-Paket nicht vom report-Paket abhaengt (report greift bereits auf storage
 * zu -- die umgekehrte Abhaengigkeit waere ein Zyklus).
 */
public class ServiceCheck
{
    private final Instant timestamp;
    private final String serviceId;
    private final String verdict;
    private final String method;
    private final int httpStatus;     // -1, wenn keine HTTP-Antwort vorlag
    private final String resolvedIp;  // kann null sein
    private final double latencyMs;

    public ServiceCheck(Instant timestamp, String serviceId, String verdict, String method,
                        int httpStatus, String resolvedIp, double latencyMs)
    {
        this.timestamp = timestamp;
        this.serviceId = serviceId;
        this.verdict = verdict;
        this.method = method;
        this.httpStatus = httpStatus;
        this.resolvedIp = resolvedIp;
        this.latencyMs = latencyMs;
    }

    public Instant getTimestamp()
    {
        return timestamp;
    }

    public String getServiceId()
    {
        return serviceId;
    }

    public String getVerdict()
    {
        return verdict;
    }

    public String getMethod()
    {
        return method;
    }

    public int getHttpStatus()
    {
        return httpStatus;
    }

    public String getResolvedIp()
    {
        return resolvedIp;
    }

    public double getLatencyMs()
    {
        return latencyMs;
    }
}
