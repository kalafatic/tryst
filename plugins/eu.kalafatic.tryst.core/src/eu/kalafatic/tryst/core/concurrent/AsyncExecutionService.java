package eu.kalafatic.tryst.core.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * Contract for managed asynchronous background task execution.
 * Guarantees that long-running operations do not block the UI thread and provides
 * controlled lifecycle management and cleanup.
 */
public interface AsyncExecutionService {

    /**
     * Executes a runnable task asynchronously on a managed background worker.
     */
    CompletableFuture<Void> runAsync(Runnable runnable);

    /**
     * Executes a callable task asynchronously returning a CompletableFuture of the result.
     */
    <T> CompletableFuture<T> supplyAsync(Callable<T> callable);

    /**
     * Shuts down background workers and disposes resources.
     */
    void shutdown();

    /**
     * Checks if executor is active.
     */
    boolean isShutdown();
}
