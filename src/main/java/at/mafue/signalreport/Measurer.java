package at.mafue.signalreport;

public interface Measurer
{
    Measurement measure(String target) throws Exception;
}
