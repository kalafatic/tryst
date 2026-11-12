package eu.kalafatic.tryst.domain.studio;

import java.time.Instant;
import java.util.Objects;

public record StudioFileRevision(
        String taskId,
        String filePath,
        int revisionNumber,
        String diffContent,
        int additions,
        int deletions,
        Instant timestamp
) {
    public StudioFileRevision {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(filePath, "filePath must not be null");
        if (timestamp == null) timestamp = Instant.now();
        if (diffContent == null) diffContent = "";
    }
}
