package eu.kalafatic.tryst.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain entity mapping a TRYST task/session to an external EVOLUTION (EVO) execution.
 * Tracks external execution status, capability, AI mode, idempotency, and progress metrics
 * independently of TRYST's core TaskStatus.
 */
public class EvoExecution {

    public enum ExecutionStatus {
        QUEUED,
        RUNNING,
        WAITING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private final String id; // Local TRYST tracking ID (UUID)
    private final String trystTaskId;
    private final String trystSessionId;
    private String evoExecutionId;
    private String evoSessionId;
    private String capability;
    private String aiMode;
    private ExecutionStatus status;
    private String phase;
    private double progress;
    private long lastSequence;
    private String idempotencyKey;
    private String summary;
    private String error;
    private final Instant createdAt;
    private Instant updatedAt;

    public EvoExecution(
            String id,
            String trystTaskId,
            String trystSessionId,
            String evoExecutionId,
            String evoSessionId,
            String capability,
            String aiMode,
            ExecutionStatus status,
            String phase,
            double progress,
            long lastSequence,
            String idempotencyKey,
            String summary,
            String error,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.trystTaskId = Objects.requireNonNull(trystTaskId, "trystTaskId must not be null");
        this.trystSessionId = trystSessionId;
        this.evoExecutionId = evoExecutionId;
        this.evoSessionId = evoSessionId;
        this.capability = capability != null ? capability : "DEFAULT";
        this.aiMode = aiMode != null ? aiMode : "DEFAULT";
        this.status = status != null ? status : ExecutionStatus.QUEUED;
        this.phase = phase != null ? phase : "";
        this.progress = progress;
        this.lastSequence = lastSequence;
        this.idempotencyKey = idempotencyKey;
        this.summary = summary != null ? summary : "";
        this.error = error != null ? error : "";
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
    }

    public static EvoExecution create(String trystTaskId, String trystSessionId, String capability, String aiMode, String idempotencyKey) {
        String id = java.util.UUID.randomUUID().toString();
        Instant now = Instant.now();
        return new EvoExecution(
                id,
                trystTaskId,
                trystSessionId,
                null,
                null,
                capability,
                aiMode,
                ExecutionStatus.QUEUED,
                "INITIATED",
                0.0,
                0,
                idempotencyKey,
                "",
                "",
                now,
                now
        );
    }

    public String getId() {
        return id;
    }

    public String getTrystTaskId() {
        return trystTaskId;
    }

    public String getTrystSessionId() {
        return trystSessionId;
    }

    public String getEvoExecutionId() {
        return evoExecutionId;
    }

    public void setEvoExecutionId(String evoExecutionId) {
        this.evoExecutionId = evoExecutionId;
        this.updatedAt = Instant.now();
    }

    public String getEvoSessionId() {
        return evoSessionId;
    }

    public void setEvoSessionId(String evoSessionId) {
        this.evoSessionId = evoSessionId;
        this.updatedAt = Instant.now();
    }

    public String getCapability() {
        return capability;
    }

    public String getAiMode() {
        return aiMode;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public void updateStatus(ExecutionStatus status, String phase, double progress) {
        this.status = status != null ? status : this.status;
        this.phase = phase != null ? phase : this.phase;
        this.progress = progress;
        this.updatedAt = Instant.now();
    }

    public String getPhase() {
        return phase;
    }

    public double getProgress() {
        return progress;
    }

    public long getLastSequence() {
        return lastSequence;
    }

    public void setLastSequence(long lastSequence) {
        this.lastSequence = Math.max(this.lastSequence, lastSequence);
        this.updatedAt = Instant.now();
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary != null ? summary : "";
        this.updatedAt = Instant.now();
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error != null ? error : "";
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
