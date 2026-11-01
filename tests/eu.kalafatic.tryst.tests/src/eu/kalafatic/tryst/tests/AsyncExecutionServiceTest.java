package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.concurrent.AsyncExecutionService;
import eu.kalafatic.tryst.core.concurrent.ManagedAsyncExecutionService;
import eu.kalafatic.tryst.core.error.TrystApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AsyncExecutionServiceTest {

    private AsyncExecutionService asyncService;

    @BeforeEach
    void setUp() {
        asyncService = new ManagedAsyncExecutionService();
    }

    @AfterEach
    void tearDown() {
        if (asyncService != null && !asyncService.isShutdown()) {
            asyncService.shutdown();
        }
    }

    @Test
    void testRunAsyncExecutesOnBackgroundThread() throws Exception {
        AtomicBoolean isBackground = new AtomicBoolean(false);
        String mainThread = Thread.currentThread().getName();
        CountDownLatch latch = new CountDownLatch(1);

        CompletableFuture<Void> future = asyncService.runAsync(() -> {
            isBackground.set(!Thread.currentThread().getName().equals(mainThread));
            latch.countDown();
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        future.get(1, TimeUnit.SECONDS);
        assertTrue(isBackground.get());
    }

    @Test
    void testSupplyAsyncReturnsResult() throws Exception {
        CompletableFuture<String> future = asyncService.supplyAsync(() -> "ExecutionResult");
        assertEquals("ExecutionResult", future.get(2, TimeUnit.SECONDS));
    }

    @Test
    void testAsyncExceptionPropagation() {
        CompletableFuture<String> future = asyncService.supplyAsync(() -> {
            throw new IllegalStateException("Simulated background error");
        });

        ExecutionException exception = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalStateException.class, exception.getCause());
        assertEquals("Simulated background error", exception.getCause().getMessage());
    }

    @Test
    void testShutdownRejectsSubsequentTasks() {
        asyncService.shutdown();
        assertTrue(asyncService.isShutdown());

        CompletableFuture<Void> future = asyncService.runAsync(() -> {});
        ExecutionException exception = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(TrystApplicationException.class, exception.getCause());
    }
}
