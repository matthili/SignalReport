package at.mafue.signalreport.report;

import at.mafue.signalreport.storage.H2MeasurementRepository.Statistics;

/**
 * Lokalisiert eine Verbindungsstoerung anhand der Mess-Statistiken der lokalen
 * Gateways (naechster Router, weitester lokaler Hop) und des Internet-Pings.
 * <p>
 * Kernidee: Eine Stoerung wird dort verortet, wo die Kette zuerst bricht.
 * Antwortet der eigene Router sauber, das Internet aber nicht, liegt der Fehler
 * oberhalb des eigenen Netzes – ein starkes Argument fuer eine Provider-Beschwerde.
 * <p>
 * Das Urteil stuetzt sich bewusst auf den <b>Paketverlust</b> als klarstes
 * "kaputt/nicht kaputt"-Signal; die Latenz wird in der Darstellung gezeigt,
 * kippt aber nicht das Urteil (hohe Latenz ist leitungsabhaengig und waere ein
 * unzuverlaessiges Kriterium).
 */
public final class ConnectivityAssessment
{
    /** Ab diesem Paketverlust (in %) gilt ein Segment als gestoert. */
    private static final double LOSS_THRESHOLD_PERCENT = 20.0;

    public enum Verdict
    {
        ALL_GOOD,        // keine Auffaelligkeiten
        LOCAL_NETWORK,   // naechster Gateway gestoert -> eigener Router/WLAN
        LOCAL_EDGE,      // weitester lokaler Gateway gestoert -> Modem/Provider-Box
        ISP_OR_BEYOND,   // lokales Netz unauffaellig, aber Internet gestoert -> Provider o. dahinter
        NO_DATA          // (noch) keine verwertbaren Messungen
    }

    private ConnectivityAssessment()
    {
    }

    /**
     * Ein Segment liefert verwertbare Daten, wenn es im Zeitfenster ueberhaupt
     * gemessen wurde. Ohne Messung sind avg und loss beide 0; bei reinen
     * Fehlschlaegen ist loss &gt; 0; im Normalfall ist avg &gt; 0.
     */
    public static boolean hasData(Statistics s)
    {
        return s != null && (s.getAvgLatency() > 0 || s.getPacketLossPercent() > 0);
    }

    /** Ein Segment ist "gesund", wenn es Daten hat und der Paketverlust unter der Schwelle liegt. */
    public static boolean isHealthy(Statistics s)
    {
        return hasData(s) && s.getPacketLossPercent() < LOSS_THRESHOLD_PERCENT;
    }

    /**
     * Verortet die Stoerung. {@code near}/{@code far} duerfen {@code null} sein
     * (Gateway nicht konfiguriert/entdeckt); {@code internet} sind die PING-Stats
     * gegen das oeffentliche Ziel.
     */
    public static Verdict assess(Statistics near, Statistics far, Statistics internet)
    {
        boolean nearData = hasData(near);
        boolean farData = hasData(far);
        boolean netData = hasData(internet);

        if (!nearData && !farData && !netData)
            {
            return Verdict.NO_DATA;
            }

        // Stoerungen von innen nach aussen pruefen – die erste gebrochene Stufe gewinnt.
        if (nearData && !isHealthy(near))
            {
            return Verdict.LOCAL_NETWORK;
            }
        if (farData && !isHealthy(far))
            {
            return Verdict.LOCAL_EDGE;
            }
        if (netData && !isHealthy(internet))
            {
            // Lokale Kette ist gesund oder nicht vorhanden, aber das Internet bricht.
            return Verdict.ISP_OR_BEYOND;
            }
        return Verdict.ALL_GOOD;
    }

    /** i18n-Schluessel fuer den Klartext eines Verdikts (von Web-UI und PDF genutzt). */
    public static String verdictKey(Verdict v)
    {
        return switch (v)
                {
                case ALL_GOOD -> "connectivity.verdict.allGood";
                case LOCAL_NETWORK -> "connectivity.verdict.localNetwork";
                case LOCAL_EDGE -> "connectivity.verdict.localEdge";
                case ISP_OR_BEYOND -> "connectivity.verdict.ispOrBeyond";
                case NO_DATA -> "connectivity.verdict.noData";
                };
    }
}
