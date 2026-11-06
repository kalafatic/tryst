package eu.kalafatic.tryst.core.log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Non-blocking, asynchronous file log writer with size-based rotation and file retention.
 * Never blocks worker or UI threads on slow file I/O operations.
 */
public class AsyncFileLogAppender {

    private static final long DEFAULT_MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB
    private static final int DEFAULT_MAX_BACKUP_FILES = 5;

    private final Path logDir;
    private final Path currentLogFile;
    private final long maxFileSizeBytes;
    private final int maxBackupFiles;

    private final BlockingQueue<LogEntry> queue = new LinkedBlockingQueue<>(10_000);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread workerThread;

    public AsyncFileLogAppender(Path logDir) {
        this(logDir, DEFAULT_MAX_FILE_SIZE_BYTES, DEFAULT_MAX_BACKUP_FILES);
    }

    public AsyncFileLogAppender(Path logDir, long maxFileSizeBytes, int maxBackupFiles) {
        this.logDir = logDir;
        this.currentLogFile = logDir.resolve("tryst.log");
        this.maxFileSizeBytes = Math.max(1024 * 1024, maxFileSizeBytes);
        this.maxBackupFiles = Math.max(1, maxBackupFiles);
    }

    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            try {
                Files.createDirectories(logDir);
            } catch (IOException e) {
                System.err.println("[AsyncFileLogAppender] Failed to create log directory: " + e.getMessage());
            }

            workerThread = new Thread(this::runWorker, "tryst-log-writer");
            workerThread.setDaemon(true);
            workerThread.start();
        }
    }

    public boolean append(LogEntry entry) {
        if (!running.get() || entry == null) {
            return false;
        }
        return queue.offer(entry);
    }

    private void runWorker() {
        BufferedWriter writer = null;
        try {
            writer = openWriter();
            while (running.get() || !queue.isEmpty()) {
                LogEntry entry = queue.poll(200, TimeUnit.MILLISECONDS);
                if (entry != null) {
                    checkRotation();
                    if (writer == null) {
                        writer = openWriter();
                    }
                    writeEntry(writer, entry);
                    // Batch flush remaining entries if present
                    int batch = 0;
                    while ((entry = queue.poll()) != null && batch < 50) {
                        checkRotation();
                        writeEntry(writer, entry);
                        batch++;
                    }
                    if (writer != null) {
                        writer.flush();
                    }
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[AsyncFileLogAppender] Exception in log worker: " + e.getMessage());
        } finally {
            if (writer != null) {
                try {
                    // Drain remaining queue before final close
                    LogEntry remaining;
                    while ((remaining = queue.poll()) != null) {
                        writeEntry(writer, remaining);
                    }
                    writer.flush();
                    writer.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private BufferedWriter openWriter() {
        try {
            return Files.newBufferedWriter(
                    currentLogFile,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            return null;
        }
    }

    private void writeEntry(BufferedWriter writer, LogEntry entry) throws IOException {
        if (writer == null || entry == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(entry.timestamp().toString())
          .append(" [").append(entry.level()).append("]")
          .append(" [").append(entry.threadName()).append("]")
          .append(" (").append(entry.bundleId()).append(":")
          .append(entry.category()).append(")");

        if (entry.correlationId() != null) {
            sb.append(" [corr:").append(entry.correlationId()).append("]");
        }
        if (entry.taskId() != null) {
            sb.append(" [task:").append(entry.taskId()).append("]");
        }
        sb.append(" - ").append(entry.message()).append(System.lineSeparator());

        if (entry.formattedStackTrace() != null) {
            sb.append(entry.formattedStackTrace());
        }
        writer.write(sb.toString());
    }

    private void checkRotation() {
        try {
            if (Files.exists(currentLogFile) && Files.size(currentLogFile) >= maxFileSizeBytes) {
                rotate();
            }
        } catch (IOException ignored) {}
    }

    private synchronized void rotate() {
        try {
            for (int i = maxBackupFiles - 1; i >= 1; i--) {
                Path src = logDir.resolve("tryst.log." + i);
                Path dest = logDir.resolve("tryst.log." + (i + 1));
                if (Files.exists(src)) {
                    Files.move(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (Files.exists(currentLogFile)) {
                Files.move(currentLogFile, logDir.resolve("tryst.log.1"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            pruneExcessBackups();
        } catch (IOException e) {
            System.err.println("[AsyncFileLogAppender] Rotation failed: " + e.getMessage());
        }
    }

    private void pruneExcessBackups() {
        try (Stream<Path> stream = Files.list(logDir)) {
            List<Path> backups = stream
                    .filter(p -> p.getFileName().toString().matches("^tryst\\.log\\.\\d+$"))
                    .sorted(Comparator.comparing(Path::toString).reversed())
                    .toList();
            for (int i = maxBackupFiles; i < backups.size(); i++) {
                try {
                    Files.deleteIfExists(backups.get(i));
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            if (workerThread != null) {
                workerThread.interrupt();
                try {
                    workerThread.join(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public Path getCurrentLogFile() {
        return currentLogFile;
    }
}
