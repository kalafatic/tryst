package eu.kalafatic.tryst.domain.studio;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record StudioAlternative(
        String id,
        String taskId,
        String title,
        String description,
        String advantages,
        String disadvantages,
        List<String> affectedFiles,
        String risks,
        String compatibilityImpact,
        AlternativeStatus status,
        Instant createdAt
) {
    public enum AlternativeStatus {
        PROPOSED,
        SELECTED,
        REJECTED
    }

    public StudioAlternative {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(description, "description must not be null");
        if (status == null) status = AlternativeStatus.PROPOSED;
        if (createdAt == null) createdAt = Instant.now();
        if (affectedFiles == null) affectedFiles = List.of();
    }

    public static StudioAlternative create(
            String taskId,
            String title,
            String description,
            String advantages,
            String disadvantages,
            List<String> affectedFiles,
            String risks,
            String compatibilityImpact
    ) {
        return new StudioAlternative(
                UUID.randomUUID().toString(),
                taskId,
                title,
                description,
                advantages,
                disadvantages,
                affectedFiles,
                risks,
                compatibilityImpact,
                AlternativeStatus.PROPOSED,
                Instant.now()
        );
    }
}
