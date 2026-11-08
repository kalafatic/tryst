package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.log.TrystLog;

/**
 * Monitors and enforces resource thresholds (CPU, active tasks, memory)
 * to throttle concurrent task dispatch and prevent server exhaustion.
 */
public class ResourceGovernor {

    private final int maxConcurrentTasks;
    private final int maxConcurrentBuilds;
    private final double maxCpuLoadRatio;

    public ResourceGovernor() {
        this(Math.max(2, Runtime.getRuntime().availableProcessors()), 2, 0.90);
    }

    public ResourceGovernor(int maxConcurrentTasks, int maxConcurrentBuilds, double maxCpuLoadRatio) {
        this.maxConcurrentTasks = maxConcurrentTasks;
        this.maxConcurrentBuilds = maxConcurrentBuilds;
        this.maxCpuLoadRatio = maxCpuLoadRatio;
    }

    public boolean canDispatchTask(int activeTaskCount, int activeBuildCount) {
        if (activeTaskCount >= maxConcurrentTasks) {
            TrystLog.debug(ResourceGovernor.class, "Throttling dispatch: active tasks (" + activeTaskCount + ") >= max (" + maxConcurrentTasks + ")");
            return false;
        }

        // Check system memory headroom
        Runtime runtime = Runtime.getRuntime();
        long freeMemory = runtime.freeMemory();
        long totalMemory = runtime.totalMemory();
        long maxMemory = runtime.maxMemory();
        long availableMemory = maxMemory - (totalMemory - freeMemory);

        // Require at least 64MB free heap
        if (availableMemory < 64 * 1024 * 1024) {
            TrystLog.warn(ResourceGovernor.class, "Throttling dispatch: low memory headroom (" + (availableMemory / 1024 / 1024) + " MB)");
            return false;
        }

        return true;
    }

    public boolean canDispatchBuild(int activeBuildCount) {
        return activeBuildCount < maxConcurrentBuilds;
    }

    public int getMaxConcurrentTasks() {
        return maxConcurrentTasks;
    }

    public int getMaxConcurrentBuilds() {
        return maxConcurrentBuilds;
    }
}
