package at.mafue.signalreport.storage;

import java.time.Instant;

/**
 * Schlanke Rohmessung fuer die Verdichtung (nur die Felder, die die Stundenwerte
 * brauchen; keine IP-Adressen).
 */
public record RawRow(Instant timestamp, String type, String target, double latencyMs,
                     boolean success, boolean excluded, String hostHash)
{
}
