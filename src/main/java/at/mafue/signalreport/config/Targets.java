package at.mafue.signalreport.config;

public class Targets
{
    private String ping;
    private String dns;
    private String http;

    public String getPing()
    {
        return ping;
    }

    public void setPing(String ping)
    {
        this.ping = ping;
    }

    public String getDns()
    {
        return dns;
    }

    public void setDns(String dns)
    {
        this.dns = dns;
    }

    public String getHttp()
    {
        return http;
    }

    public void setHttp(String http)
    {
        this.http = http;
    }
}
