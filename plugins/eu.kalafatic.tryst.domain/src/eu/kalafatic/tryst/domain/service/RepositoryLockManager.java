package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.log.TrystLog;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages fine-grained repository and workspace synchronization locks:
 * - Read locks for concurrent analysis tasks on the same repository
 * - Write locks for code modification or repository synchronization
 * - Thread-agnostic release: background tasks can safely release locks regardless
 *   of which worker thread concludes the task
 * - Automatic timeout expiration to prevent stale lock deadlocks on worker crash
 */
public class RepositoryLockManager {

    public enum LockType {
        READ,
        WRITE
    }

    public record LockHandle(
            String resourceKey,
            String taskId,
            String ownerId,
            LockType type,
            Instant acquiredAt,
            Instant expiresAt
    ) {
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private static class ResourceState {
        final AtomicInteger activeReaders = new AtomicInteger(0);
        volatile String writeLockedByTaskId = null;
    }

    private final Map<String, ResourceState> resourceStates = new ConcurrentHashMap<>();
    private final Map<String, LockHandle> activeHandles = new ConcurrentHashMap<>();

    /**
     * Acquires a read lock for analysis tasks.
     */
    public synchronized boolean acquireReadLock(String repositoryId, String taskId, String ownerId, long timeoutSeconds) {
        Objects.requireNonNull(repositoryId, "repositoryId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");

        ResourceState state = resourceStates.computeIfAbsent(repositoryId, k -> new ResourceState());
        if (state.writeLockedByTaskId != null && !taskId.equals(state.writeLockedByTaskId)) {
            return false;
        }

        state.activeReaders.incrementAndGet();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(timeoutSeconds > 0 ? timeoutSeconds : 1800);
        activeHandles.put(taskId, new LockHandle(repositoryId, taskId, ownerId, LockType.READ, now, expiresAt));
        TrystLog.info(RepositoryLockManager.class, "Acquired READ lock on repository " + repositoryId + " for task " + taskId);
        return true;
    }

    /**
     * Acquires a write lock for modification or synchronization tasks.
     */
    public synchronized boolean acquireWriteLock(String repositoryId, String taskId, String ownerId, long timeoutSeconds) {
        Objects.requireNonNull(repositoryId, "repositoryId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");

        ResourceState state = resourceStates.computeIfAbsent(repositoryId, k -> new ResourceState());
        if (state.writeLockedByTaskId != null || state.activeReaders.get() > 0) {
            return false;
        }

        state.writeLockedByTaskId = taskId;
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(timeoutSeconds > 0 ? timeoutSeconds : 1800);
        activeHandles.put(taskId, new LockHandle(repositoryId, taskId, ownerId, LockType.WRITE, now, expiresAt));
        TrystLog.info(RepositoryLockManager.class, "Acquired WRITE lock on repository " + repositoryId + " for task " + taskId);
        return true;
    }

    /**
     * Releases any locks held by the given taskId.
     */
    public synchronized void releaseLock(String taskId) {
        LockHandle handle = activeHandles.remove(taskId);
        if (handle == null) return;

        ResourceState state = resourceStates.get(handle.resourceKey());
        if (state != null) {
            if (handle.type() == LockType.READ) {
                state.activeReaders.decrementAndGet();
            } else if (handle.type() == LockType.WRITE) {
                if (taskId.equals(state.writeLockedByTaskId)) {
                    state.writeLockedByTaskId = null;
                }
            }
            TrystLog.info(RepositoryLockManager.class, "Released " + handle.type() + " lock on repository " + handle.resourceKey() + " for task " + taskId);
        }
    }

    /**
     * Checks if a resource is currently locked by a write lock.
     */
    public synchronized boolean isWriteLocked(String repositoryId) {
        ResourceState state = resourceStates.get(repositoryId);
        return state != null && state.writeLockedByTaskId != null;
    }

    /**
     * Cleans up expired locks held by crashed or aborted tasks.
     */
    public synchronized void cleanupExpiredLocks() {
        for (Map.Entry<String, LockHandle> entry : activeHandles.entrySet()) {
            if (entry.getValue().isExpired()) {
                TrystLog.warn(RepositoryLockManager.class, "Evicting expired lock for task " + entry.getKey() + " on " + entry.getValue().resourceKey());
                releaseLock(entry.getKey());
            }
        }
    }
}
