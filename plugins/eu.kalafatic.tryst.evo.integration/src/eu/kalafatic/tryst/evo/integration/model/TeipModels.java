package eu.kalafatic.tryst.evo.integration.model;

import java.util.List;
import java.util.Map;

/**
 * TEIP 1.0 JSON Wire Records and Models conforming to normative TEIP-1.0.md.
 */
public final class TeipModels {

    private TeipModels() {}

    public record TeipCaller(
            String system,
            String projectId,
            String taskId
    ) {}

    public record TeipHealthResponse(
            String status,
            String protocol,
            String version,
            String engineVersion,
            String nodeId,
            long uptimeSeconds,
            String timestamp
    ) {}

    public record TeipCapability(
            String id,
            String name,
            List<String> supportedModes,
            List<String> requiredScopes
    ) {}

    public record TeipModel(
            String id,
            String name,
            long contextWindowTokens,
            boolean recommended
    ) {}

    public record TeipCapabilitiesResponse(
            List<TeipCapability> capabilities,
            List<TeipModel> availableModels
    ) {}

    public record TeipExecutionRequest(
            String protocol,
            String version,
            String requestId,
            String correlationId,
            String idempotencyKey,
            String timestamp,
            TeipCaller caller,
            String capability,
            String aiMode,
            String model,
            String instruction,
            List<String> contextReferences,
            List<String> mcpToolScopes,
            Map<String, Object> parameters
    ) {}

    public record TeipExecutionResponse(
            String executionId,
            String evoSessionId,
            String status,
            String createdAt
    ) {}

    public record TeipExecutionStatus(
            String executionId,
            String status,
            String phase,
            double progress,
            long lastSequence,
            String createdAt,
            String updatedAt
    ) {}

    public record TeipEvent(
            String eventId,
            String executionId,
            long sequence,
            String type,
            String timestamp,
            String correlationId,
            String dataJson
    ) {}

    public record TeipEventsResponse(
            String executionId,
            List<TeipEvent> events,
            boolean hasMore,
            long latestSequence
    ) {}

    public record TeipExecutionMetrics(
            long durationMs,
            long promptTokens,
            long completionTokens,
            int toolCallsCount
    ) {}

    public record TeipExecutionResult(
            String executionId,
            String status,
            String summary,
            String patch,
            List<String> modifiedFiles,
            TeipExecutionMetrics metrics,
            String completedAt
    ) {}

    public record TeipCancelResponse(
            String executionId,
            String status,
            String message
    ) {}

    public record TeipError(
            String code,
            String message,
            String requestId,
            String correlationId,
            boolean retryable,
            Map<String, Object> details
    ) {}
}
