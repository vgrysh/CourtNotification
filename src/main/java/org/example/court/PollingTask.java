package org.example.court;

import java.util.concurrent.ScheduledFuture;

public class PollingTask {

    private final String id;
    private final PollingConfig config;
    private final ScheduledFuture<?> future;
    private volatile String lastCheckTime;
    private volatile String lastCheckResult;

    public PollingTask(String id, PollingConfig config, ScheduledFuture<?> future) {
        this.id = id;
        this.config = config;
        this.future = future;
    }

    public String getId() { return id; }
    public PollingConfig getConfig() { return config; }
    public ScheduledFuture<?> getFuture() { return future; }
    public String getLastCheckTime() { return lastCheckTime; }
    public String getLastCheckResult() { return lastCheckResult; }
    public void setLastCheckTime(String t) { this.lastCheckTime = t; }
    public void setLastCheckResult(String r) { this.lastCheckResult = r; }
    public boolean isRunning() { return !future.isDone() && !future.isCancelled(); }
}
