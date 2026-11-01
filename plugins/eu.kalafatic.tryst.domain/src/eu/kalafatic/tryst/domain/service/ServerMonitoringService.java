package eu.kalafatic.tryst.domain.service;

import java.lang.management.*;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server resource monitoring service collecting CPU, memory, storage, and JVM metrics
 * using standard Java/JVM management beans.
 */
public class ServerMonitoringService {

    public record SystemMetrics(
            double systemCpuLoad,
            double processCpuLoad,
            int availableProcessors,
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long nonHeapUsedBytes,
            long totalPhysicalMemoryBytes,
            long freePhysicalMemoryBytes,
            long workspaceTotalBytes,
            long workspaceFreeBytes,
            long workspaceUsedBytes,
            int threadCount,
            int daemonThreadCount,
            long jvmUptimeMs,
            long garbageCollectionCount,
            long garbageCollectionTimeMs
    ) {}

    private final Path workspaceRoot;
    private final OperatingSystemMXBean osBean;
    private final MemoryMXBean memoryBean;
    private final ThreadMXBean threadBean;
    private final RuntimeMXBean runtimeBean;

    public ServerMonitoringService(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.threadBean = ManagementFactory.getThreadMXBean();
        this.runtimeBean = ManagementFactory.getRuntimeMXBean();
    }

    public SystemMetrics collectMetrics() {
        double sysCpu = -1.0;
        double procCpu = -1.0;
        long totalPhys = -1L;
        long freePhys = -1L;

        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            sysCpu = sunOs.getCpuLoad();
            procCpu = sunOs.getProcessCpuLoad();
            totalPhys = sunOs.getTotalMemorySize();
            freePhys = sunOs.getFreeMemorySize();
        }

        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();

        long wsTotal = -1L;
        long wsFree = -1L;
        long wsUsed = -1L;
        if (workspaceRoot != null && Files.exists(workspaceRoot)) {
            try {
                FileStore store = Files.getFileStore(workspaceRoot);
                wsTotal = store.getTotalSpace();
                wsFree = store.getUsableSpace();
                wsUsed = wsTotal - wsFree;
            } catch (Exception ignored) {
            }
        }

        long gcCount = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            if (count > 0) gcCount += count;
            long time = gc.getCollectionTime();
            if (time > 0) gcTime += time;
        }

        return new SystemMetrics(
                sysCpu >= 0 ? sysCpu * 100.0 : -1.0,
                procCpu >= 0 ? procCpu * 100.0 : -1.0,
                osBean.getAvailableProcessors(),
                heap.getUsed(),
                heap.getCommitted(),
                heap.getMax(),
                nonHeap.getUsed(),
                totalPhys,
                freePhys,
                wsTotal,
                wsFree,
                wsUsed,
                threadBean.getThreadCount(),
                threadBean.getDaemonThreadCount(),
                runtimeBean.getUptime(),
                gcCount,
                gcTime
        );
    }
}
