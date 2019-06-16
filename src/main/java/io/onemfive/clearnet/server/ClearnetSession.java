package io.onemfive.clearnet.server;

import io.onemfive.data.DID;

/**
 * TODO: Add Description
 *
 * @author objectorange
 */
public class ClearnetSession {

    public static int SESSION_INACTIVITY_INTERVAL = 60 * 60; // 60 minutes

    private String id;
    private DID did = new DID();
    private long lastRequestTime = System.currentTimeMillis();
    private boolean authenticated = false;

    public ClearnetSession(String id) {
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

    public boolean getAuthenticated() {
        return authenticated;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }
}
