package at.mafue.signalreport.report;

/**
 * Klassifiziert die Erreichbarkeit eines einzelnen Dienstes anhand der rohen
 * Beobachtungen einer Schicht-Probe (DNS, TCP, TLS/SNI, HTTP) zu einem Verdikt.
 * <p>
 * Schwesterklasse zu {@link ConnectivityAssessment}: reine, seiteneffektfreie
 * Logik, damit sie vollstaendig offline testbar ist. Der eigentliche Netz-Zugriff
 * liegt in der Probe (Paket {@code network}); diese Klasse trifft nur das Urteil.
 * <p>
 * Kernidee: von innen nach aussen pruefen -- die erste gebrochene Schicht
 * bestimmt das Verdikt. Loest der Name nur ueber einen oeffentlichen, nicht aber
 * ueber den ISP-Resolver auf, ist das eine DNS-Sperre; bricht erst TLS mit dem
 * echten SNI (waehrend ein Kontroll-SNI durchgeht), ist es eine SNI-/DPI-Sperre;
 * antwortet der Server hingegen sauber mit 5xx, ist der Dienst selbst gestoert.
 */
public final class ServiceReachabilityAssessment
{
    public enum Verdict
    {
        REACHABLE,           // Dienst antwortet wie erwartet
        SERVICE_DOWN,        // erreichbar, aber server-seitige Stoerung (HTTP 5xx)
        DNS_BLOCKED,         // nur oeffentlicher Resolver loest auf / ISP liefert Fake-IP
        CONNECTION_BLOCKED,  // aufgeloest, aber TCP/TLS wird gezielt unterbunden
        SNI_BLOCKED,         // TLS mit echtem SNI stirbt, Kontroll-SNI klappt (DPI)
        BLOCKPAGE,           // Sperrseite / HTTP 451
        UNKNOWN,             // kein eindeutiges Urteil moeglich
        NOT_CHECKED,         // Dienst/Feature deaktiviert oder noch nie geprueft
        LINE_DOWN            // Lauf uebersprungen, weil die Internet-Leitung unten war
    }

    private ServiceReachabilityAssessment()
    {
    }

    /**
     * Trifft das Erreichbarkeits-Urteil. {@code null} liefert {@link Verdict#UNKNOWN}.
     * Liefert nie {@link Verdict#NOT_CHECKED} oder {@link Verdict#LINE_DOWN} -- diese
     * werden vom Scheduler gesetzt, nicht aus einer tatsaechlichen Probe abgeleitet.
     */
    public static Verdict classify(Observation o)
    {
        if (o == null)
            {
            return Verdict.UNKNOWN;
            }

        // 1. DNS-Ebene: ISP-Resolver verweigert/faelscht, oeffentlicher loest auf.
        if ((!o.ispResolved || o.ispBogusIp) && o.publicResolved)
            {
            return Verdict.DNS_BLOCKED;
            }
        // Kein Resolver liefert eine IP -> ohne aufgeloesten Namen nicht entscheidbar.
        if (!o.ispResolved && !o.publicResolved)
            {
            return Verdict.UNKNOWN;
            }

        // 2. TCP-Ebene: aufgeloest, aber keine Verbindung.
        if (!o.tcpConnected)
            {
            return o.controlReachable ? Verdict.CONNECTION_BLOCKED : Verdict.UNKNOWN;
            }

        // 3. TLS/SNI-Ebene: TCP steht, aber TLS mit echtem SNI scheitert.
        if (!o.tlsRealSniOk)
            {
            if (o.tlsControlSniTested && o.tlsControlSniOk)
                {
                return Verdict.SNI_BLOCKED;
                }
            return o.controlReachable ? Verdict.CONNECTION_BLOCKED : Verdict.UNKNOWN;
            }

        // 4. HTTP-Ebene: TLS mit echtem SNI steht.
        if (o.blockpageDetected || o.httpStatus == 451)
            {
            return Verdict.BLOCKPAGE;
            }
        if (o.httpStatus < 0)
            {
            return Verdict.UNKNOWN; // keine HTTP-Antwort trotz TLS
            }
        if (o.httpStatus >= 500)
            {
            return Verdict.SERVICE_DOWN;
            }
        return Verdict.REACHABLE; // 2xx/3xx/4xx (ausser 451): der Dienst antwortet
    }

    /** True fuer alle Verdikte, die auf eine Sperre hindeuten (rote UI-Zustaende). */
    public static boolean isBlocked(Verdict v)
    {
        return v == Verdict.DNS_BLOCKED
                || v == Verdict.CONNECTION_BLOCKED
                || v == Verdict.SNI_BLOCKED
                || v == Verdict.BLOCKPAGE;
    }

    /** i18n-Schluessel fuer den Klartext eines Verdikts (von Web-UI und PDF genutzt). */
    public static String verdictKey(Verdict v)
    {
        return switch (v)
                {
                case REACHABLE -> "reachability.verdict.reachable";
                case SERVICE_DOWN -> "reachability.verdict.serviceDown";
                case DNS_BLOCKED -> "reachability.verdict.dnsBlocked";
                case CONNECTION_BLOCKED -> "reachability.verdict.connectionBlocked";
                case SNI_BLOCKED -> "reachability.verdict.sniBlocked";
                case BLOCKPAGE -> "reachability.verdict.blockpage";
                case UNKNOWN -> "reachability.verdict.unknown";
                case NOT_CHECKED -> "reachability.verdict.notChecked";
                case LINE_DOWN -> "reachability.verdict.lineDown";
                };
    }

    /**
     * Rohe Beobachtungen einer Schicht-Probe fuer genau einen Dienst. Wird von der
     * Probe (Paket {@code network}) gefuellt und an {@link #classify(Observation)}
     * uebergeben. Fluent-Setter halten die Konstruktion (und die Tests) knapp.
     * <p>
     * {@code controlReachable} ist standardmaessig {@code true}: ist ein neutraler
     * Referenzpfad nachweislich unten, werden Sperr-Verdikte vorsichtshalber zu
     * {@link Verdict#UNKNOWN} abgestuft.
     */
    public static final class Observation
    {
        private boolean ispResolved = false;
        private boolean ispBogusIp = false;
        private boolean publicResolved = false;
        private boolean tcpConnected = false;
        private boolean tlsRealSniOk = false;
        private boolean tlsControlSniTested = false;
        private boolean tlsControlSniOk = false;
        private int httpStatus = -1;
        private boolean blockpageDetected = false;
        private boolean controlReachable = true;

        public Observation dns(boolean ispResolved, boolean ispBogusIp, boolean publicResolved)
        {
            this.ispResolved = ispResolved;
            this.ispBogusIp = ispBogusIp;
            this.publicResolved = publicResolved;
            return this;
        }

        public Observation tcp(boolean connected)
        {
            this.tcpConnected = connected;
            return this;
        }

        public Observation tlsRealSni(boolean ok)
        {
            this.tlsRealSniOk = ok;
            return this;
        }

        public Observation tlsControlSni(boolean tested, boolean ok)
        {
            this.tlsControlSniTested = tested;
            this.tlsControlSniOk = ok;
            return this;
        }

        public Observation http(int status)
        {
            this.httpStatus = status;
            return this;
        }

        public Observation blockpage(boolean detected)
        {
            this.blockpageDetected = detected;
            return this;
        }

        public Observation controlReachable(boolean reachable)
        {
            this.controlReachable = reachable;
            return this;
        }
    }
}
