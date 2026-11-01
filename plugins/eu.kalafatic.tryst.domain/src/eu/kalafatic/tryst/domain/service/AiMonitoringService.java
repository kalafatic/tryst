package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.domain.ai.AiInferenceService;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service tracking AI inference health, latencies, tokens, and active model.
 */
public class AiMonitoringService {

    private final AiInferenceService inferenceService;
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicInteger successfulRequests = new AtomicInteger(0);
    private final AtomicInteger failedRequests = new AtomicInteger(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);
    private final AtomicLong tokensGenerated = new AtomicLong(0);
    private volatile String lastError = null;
    private volatile String activeModel = "codellama";

    public record AiHealthStatus(
            boolean endpointAvailable,
            String endpoint,
            String activeModel,
            int totalRequests,
            int successfulRequests,
            int failedRequests,
            double averageLatencyMs,
            long tokensGenerated,
            String lastError
    ) {}

    public AiMonitoringService(AiInferenceService inferenceService) {
        this.inferenceService = inferenceService;
    }

    public void setActiveModel(String model) {
        if (model != null && !model.isBlank()) {
            this.activeModel = model.trim();
        }
    }

    public void recordInference(boolean success, long durationMs, long tokens, String error) {
        totalRequests.incrementAndGet();
        if (success) {
            successfulRequests.incrementAndGet();
            totalLatencyMs.addAndGet(durationMs);
            tokensGenerated.addAndGet(tokens);
        } else {
            failedRequests.incrementAndGet();
            this.lastError = error;
        }
    }

    public AiHealthStatus checkHealth() {
        boolean available = false;
        String endpoint = "http://localhost:11434";

        if (inferenceService != null) {
            try {
                available = Boolean.TRUE.equals(inferenceService.checkConnection().get(1, TimeUnit.SECONDS));
            } catch (Exception e) {
                available = false;
                this.lastError = e.getMessage();
            }
        }

        int total = totalRequests.get();
        int succ = successfulRequests.get();
        double avgLat = succ > 0 ? (double) totalLatencyMs.get() / succ : 0.0;

        return new AiHealthStatus(
                available,
                endpoint,
                activeModel,
                total,
                succ,
                failedRequests.get(),
                avgLat,
                tokensGenerated.get(),
                lastError
        );
    }
}
