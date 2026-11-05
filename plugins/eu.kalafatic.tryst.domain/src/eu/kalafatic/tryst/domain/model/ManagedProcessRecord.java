package eu.kalafatic.tryst.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing an operating system child process managed by TRYST.
 */
public class ManagedProcessRecord {

    private final String id;
    private final String taskId;
    private final long pid;
    private final String commandClass;
    private final String workingDir;
    private final Instant startTime;
    private long durationMs;
    private String status;
    private Integer exitCode;
    private static final int MAX_OUTPUT_LINES = 500;
    private final java.util.concurrent.ConcurrentLinkedDeque<String> outputLines = new java.util.concurrent.ConcurrentLinkedDeque<>();

    public ManagedProcessRecord(String id, String taskId, long pid, String commandClass, String workingDir,
                                Instant startTime, long durationMs, String status, Integer exitCode) {
        this(id, taskId, pid, commandClass, workingDir, startTime, durationMs, status, exitCode, null);
    }

    public ManagedProcessRecord(String id, String taskId, long pid, String commandClass, String workingDir,
                                Instant startTime, long durationMs, String status, Integer exitCode,
                                java.util.List<String> initialOutput) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.taskId = taskId;
        this.pid = pid;
        this.commandClass = Objects.requireNonNull(commandClass, "commandClass must not be null");
        this.workingDir = Objects.requireNonNull(workingDir, "workingDir must not be null");
        this.startTime = startTime != null ? startTime : Instant.now();
        this.durationMs = durationMs;
        this.status = status != null ? status : "RUNNING";
        this.exitCode = exitCode;
        if (initialOutput != null) {
            for (String line : initialOutput) {
                appendOutput(line);
            }
        }
    }

    public ManagedProcessRecord(String taskId, long pid, String commandClass, String workingDir) {
        this(UUID.randomUUID().toString(), taskId, pid, commandClass, workingDir, Instant.now(), 0, "RUNNING", null);
    }

    public String getId() {
        return id;
    }

    public String getTaskId() {
        return taskId;
    }

    public long getPid() {
        return pid;
    }

    public String getCommandClass() {
        return commandClass;
    }

    public String getWorkingDir() {
        return workingDir;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getStatus() {
        return status;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void markTerminated(int exitCode, long durationMs) {
        this.status = (exitCode == 0) ? "COMPLETED" : "TERMINATED";
        this.exitCode = exitCode;
        this.durationMs = durationMs;
    }

    public void markKilled() {
        this.status = "KILLED";
        this.exitCode = 137;
    }

    public void appendOutput(String line) {
        if (line != null) {
            outputLines.add(line);
            while (outputLines.size() > MAX_OUTPUT_LINES) {
                outputLines.pollFirst();
            }
        }
    }

    public java.util.List<String> getOutputLines() {
        return new java.util.ArrayList<>(outputLines);
    }
}
