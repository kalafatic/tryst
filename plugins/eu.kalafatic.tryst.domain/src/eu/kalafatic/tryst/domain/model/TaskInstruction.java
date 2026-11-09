package eu.kalafatic.tryst.domain.model;

import eu.kalafatic.tryst.core.error.TrystDomainException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing an instruction, requirement, constraint,
 * or supplementary context associated with a task intake and execution lifecycle.
 * Original user requests and all subsequent user instructions are permanently persisted.
 */
public class TaskInstruction {

    public enum InstructionType {
        ORIGINAL_REQUEST,
        SUPPLEMENTARY,
        ACCEPTANCE_CRITERIA,
        CONSTRAINT,
        EXPECTED_OUTCOME,
        CONTEXT,
        ATTACHMENT
    }

    private final String id;
    private final String taskId;
    private final InstructionType instructionType;
    private final String content;
    private final String authorId;
    private final Instant createdAt;

    public TaskInstruction(String taskId, InstructionType instructionType, String content, String authorId) {
        this(UUID.randomUUID().toString(), taskId, instructionType, content, authorId, Instant.now());
    }

    public TaskInstruction(String id, String taskId, InstructionType instructionType, String content, String authorId, Instant createdAt) {
        if (taskId == null || taskId.trim().isEmpty()) {
            throw new TrystDomainException("TaskInstruction taskId must not be null or blank");
        }
        if (instructionType == null) {
            throw new TrystDomainException("TaskInstruction instructionType must not be null");
        }
        if (content == null || content.trim().isEmpty()) {
            throw new TrystDomainException("TaskInstruction content must not be null or blank");
        }
        if (authorId == null || authorId.trim().isEmpty()) {
            throw new TrystDomainException("TaskInstruction authorId must not be null or blank");
        }

        this.id = Objects.requireNonNull(id, "id must not be null");
        this.taskId = taskId.trim();
        this.instructionType = instructionType;
        this.content = content.trim();
        this.authorId = authorId.trim();
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public String getId() {
        return id;
    }

    public String getTaskId() {
        return taskId;
    }

    public InstructionType getInstructionType() {
        return instructionType;
    }

    public String getContent() {
        return content;
    }

    public String getAuthorId() {
        return authorId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TaskInstruction that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "TaskInstruction{" +
                "id='" + id + '\'' +
                ", taskId='" + taskId + '\'' +
                ", instructionType=" + instructionType +
                ", authorId='" + authorId + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
