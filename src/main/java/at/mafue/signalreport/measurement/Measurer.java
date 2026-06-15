package at.mafue.signalreport.measurement;

public interface Measurer
{
    Measurement measure(String target) throws Exception;
}
