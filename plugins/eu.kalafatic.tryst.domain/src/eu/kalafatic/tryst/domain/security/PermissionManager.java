package eu.kalafatic.tryst.domain.security;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.domain.model.Workspace;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Practical, least-privilege security and permission manager for desktop TRYST.
 * Enforces explicit workspace-root path containment, tool permissions, and audit logging.
 */
public class PermissionManager {

    public enum PermissionLevel {
        READ_ONLY,
        WORKSPACE_WRITE,
        PROCESS_EXECUTION
    }

    public record AuditRecord(
            String timestamp,
            String action,
            String target,
            boolean granted,
            String reason
    ) {}

    private final CopyOnWriteArrayList<AuditRecord> auditLog = new CopyOnWriteArrayList<>();

    public void validatePathWithinWorkspace(Path targetPath, Workspace workspace) {
        Objects.requireNonNull(targetPath, "targetPath must not be null");
        if (workspace == null) {
            recordAudit("PATH_ACCESS", targetPath.toString(), false, "No active workspace");
            throw new TrystDomainException("Access denied: No active workspace available.");
        }

        Path normalizedTarget = targetPath.toAbsolutePath().normalize();
        Path normalizedRoot = workspace.getRootPath().toAbsolutePath().normalize();

        if (!normalizedTarget.startsWith(normalizedRoot)) {
            recordAudit("PATH_ACCESS", targetPath.toString(), false, "Path outside workspace root: " + normalizedRoot);
            throw new TrystDomainException("Access denied: Path '" + targetPath + "' is outside authorized workspace root '" + normalizedRoot + "'.");
        }

        recordAudit("PATH_ACCESS", targetPath.toString(), true, "Allowed within workspace");
    }

    public void checkPermission(PermissionLevel requiredLevel, boolean allowExecution) {
        if (requiredLevel == PermissionLevel.PROCESS_EXECUTION && !allowExecution) {
            recordAudit("PROCESS_EXECUTION", "system", false, "Execution not permitted");
            throw new TrystDomainException("Access denied: Process execution is disabled by configuration or user policy.");
        }
        recordAudit(requiredLevel.name(), "system", true, "Granted");
    }

    public void recordAudit(String action, String target, boolean granted, String reason) {
        auditLog.add(new AuditRecord(java.time.Instant.now().toString(), action, target, granted, reason));
    }

    public CopyOnWriteArrayList<AuditRecord> getAuditLog() {
        return auditLog;
    }
}
