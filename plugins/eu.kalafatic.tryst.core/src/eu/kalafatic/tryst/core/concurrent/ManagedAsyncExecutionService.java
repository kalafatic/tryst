package eu.kalafatic.tryst.core.concurrent;

import eu.kalafatic.tryst.core.error.TrystApplicationException;
import eu.kalafatic.tryst.core.log.TrystLog;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standard implementation of AsyncExecutionService managing a bounded thread pool
 * with identifiable daemon threads and graceful shutdown.
 */
public class ManagedAsyncExecutionService implements AsyncExecutionService {

    private final ExecutorService executor;

    public ManagedAsyncExecutionService() {
        this(Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                new TrystThreadFactory()
        ));
    }

    public ManagedAsyncExecutionService(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    @Override
    public CompletableFuture<Void> runAsync(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable must not be null");
        if (isShutdown()) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new TrystApplicationException("AsyncExecutionService is already shut down"));
            return future;
        }
        return CompletableFuture.runAsync(runnable, executor);
    }

    @Override
    public <T> CompletableFuture<T> supplyAsync(Callable<T> callable) {
        Objects.requireNonNull(callable, "callable must not be null");
        CompletableFuture<T> future = new CompletableFuture<>();
        if (isShutdown()) {
            future.completeExceptionally(new TrystApplicationException("AsyncExecutionService is already shut down"));
            return future;
        }
        executor.submit(() -> {
            try {
                T result = callable.call();
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public synchronized void shutdown() {
        if (!executor.isShutdown()) {
            TrystLog.info(ManagedAsyncExecutionService.class, "Shutting down ManagedAsyncExecutionService...");
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isShutdown() {
        return executor.isShutdown();
    }

    private static class TrystThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "tryst-async-worker-" + threadNumber.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
