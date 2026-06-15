package at.mafue.signalreport.config;

public class AuthConfig
{
    private boolean enabled = false;
    private String adminPasswordHash = "";
    private String userPasswordHash = "";

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public String getAdminPasswordHash()
    {
        return adminPasswordHash;
    }

    public void setAdminPasswordHash(String hash)
    {
        this.adminPasswordHash = hash;
    }

    public String getUserPasswordHash()
    {
        return userPasswordHash;
    }

    public void setUserPasswordHash(String hash)
    {
        this.userPasswordHash = hash;
    }
}
