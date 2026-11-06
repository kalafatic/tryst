package eu.kalafatic.tryst.core.port;

import java.util.List;
import java.util.Map;

/**
 * Report containing details of detected port conflicts across a coherent port group.
 */
public record PortConflictReport(
        PortAllocationGroup candidateGroup,
        List<PortConflictItem> conflicts,
        PortAllocationGroup alternativeGroup
) {
    public boolean hasConflicts() {
        return conflicts != null && !conflicts.isEmpty();
    }

    public record PortConflictItem(
            String serviceId,
            int port,
            ProcessPortInfo processInfo
    ) {}
}
