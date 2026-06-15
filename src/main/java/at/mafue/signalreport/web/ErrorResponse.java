package at.mafue.signalreport.web;

/** Einfache JSON-Fehlerantwort fuer die API. */
public class ErrorResponse
{
    public final String error;

    public ErrorResponse(String error)
    {
        this.error = error;
    }
}
