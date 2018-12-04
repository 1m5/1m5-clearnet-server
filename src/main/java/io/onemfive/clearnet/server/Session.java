package io.onemfive.clearnet.server;

import io.onemfive.data.DID;

/**
 * TODO: Add Description
 *
 * @author objectorange
 */
public class Session {

    private String id;
    private DID did = new DID();
    private long lastRequestTime = System.currentTimeMillis();
    private long maxSession = 60 * 60 * 1000; // 60 minutes
    private boolean authenticated = false;

    public Session(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public DID getDid() {
        return did;
    }

    public long getLastRequestTime() {
        return lastRequestTime;
    }

    public void setLastRequestTime(long lastRequestTime) {
        this.lastRequestTime = lastRequestTime;
    }

    public long getMaxSession() {
        return maxSession;
    }

    public void setMaxSession(long maxSession) {
        this.maxSession = maxSession;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }
}
