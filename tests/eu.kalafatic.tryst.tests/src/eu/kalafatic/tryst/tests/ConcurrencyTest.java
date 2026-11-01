package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ConcurrencyTest {

    private SqliteDatabaseManager dbManager;
    private SqliteProjectRepository projectRepo;

    @BeforeEach
    public void setup(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("concurrency_test.db");
        dbManager = new SqliteDatabaseManager(dbPath);
        dbManager.initialize();
        new SchemaMigrationService(dbManager).migrate();
        projectRepo = new SqliteProjectRepository(dbManager);
    }

    @AfterEach
    public void tearDown() {
        if (dbManager != null) {
            dbManager.close();
        }
    }

    @Test
    public void testConcurrentWritesAreSafelySerialized() throws Exception {
        int threads = 10;
        int operationsPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int threadIdx = i;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < operationsPerThread; j++) {
                        Project p = new Project("Proj-" + threadIdx + "-" + j, Path.of("/tmp/" + threadIdx + "/" + j));
                        projectRepo.save(p);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        List<Project> all = projectRepo.findAll();
        assertEquals(threads * operationsPerThread, all.size());
    }
}
