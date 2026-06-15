package at.mafue.signalreport.config;

public class SetupConfig
{
    private boolean setupCompleted = false;
    private String adminPasswordHash = "";

    public boolean isSetupCompleted()
    {
        return setupCompleted;
    }

    public void setSetupCompleted(boolean completed)
    {
        this.setupCompleted = completed;
    }

    public String getAdminPasswordHash()
    {
        return adminPasswordHash;
    }

    public void setAdminPasswordHash(String hash)
    {
        this.adminPasswordHash = hash;
    }
}
