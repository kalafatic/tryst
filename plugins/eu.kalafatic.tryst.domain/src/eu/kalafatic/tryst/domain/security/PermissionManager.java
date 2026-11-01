package eu.kalafatic.tryst.domain.security;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Practical, least-privilege security and permission manager for TRYST.
 * Enforces explicit workspace-root path containment, symlink escape protection,
 * multi-user project authorization, and concurrent workspace locks.
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
    private final ConcurrentHashMap<String, ReentrantLock> workspaceLocks = new ConcurrentHashMap<>();
    private eu.kalafatic.tryst.domain.repository.AuditEventRepository auditEventRepository;

    public PermissionManager() {
        this(null);
    }

    public PermissionManager(eu.kalafatic.tryst.domain.repository.AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    public void setAuditEventRepository(eu.kalafatic.tryst.domain.repository.AuditEventRepository repository) {
        this.auditEventRepository = repository;
    }

    /**
     * Acquires a lock for concurrent operations within a specific workspace root path.
     */
    public ReentrantLock getWorkspaceLock(Path workspaceRoot) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
        String canonicalKey;
        try {
            canonicalKey = workspaceRoot.toRealPath().normalize().toString();
        } catch (Exception e) {
            canonicalKey = workspaceRoot.toAbsolutePath().normalize().toString();
        }
        return workspaceLocks.computeIfAbsent(canonicalKey, k -> new ReentrantLock());
    }

    /**
     * Enforces user authorization for a specific project.
     * Prevents cross-tenant / unauthorized access at the service boundary.
     */
    public void validateUserProjectAccess(String userId, Project project) {
        validateUserProjectAccess(userId, project, null);
    }

    public void validateUserProjectAccess(String userId, Project project, SecurityContext context) {
        Objects.requireNonNull(project, "project must not be null");
        if (userId == null || userId.trim().isEmpty()) {
            recordAudit(userId != null ? userId : "anonymous", "PROJECT_ACCESS", "project", project.getId(), project.getId(), null, "DENIED", "Missing or blank userId");
            throw new TrystDomainException("Access denied: User identification is required.");
        }
        String normalizedUser = userId.trim();
        boolean isAdmin = (context != null && context.isAdmin());
        String ownerId = project.getOwnerId();
        if (ownerId != null && !ownerId.equals(normalizedUser) && !isAdmin) {
            recordAudit(normalizedUser, "PROJECT_ACCESS", "project", project.getId(), project.getId(), null, "DENIED", "User '" + normalizedUser + "' not authorized for project owned by '" + ownerId + "'");
            throw new TrystDomainException("Access denied: User '" + normalizedUser + "' is not authorized to access project '" + project.getName() + "'.");
        }
        recordAudit(normalizedUser, "PROJECT_ACCESS", "project", project.getId(), project.getId(), null, "ALLOWED", "Authorized access");
    }

    /**
     * Validates that targetPath is strictly contained within the workspace root.
     * Resolves canonical real paths and verifies symlinks do not escape the workspace root.
     */
    public void validatePathWithinWorkspace(Path targetPath, Workspace workspace) {
        Objects.requireNonNull(targetPath, "targetPath must not be null");
        if (workspace == null) {
            recordAudit("PATH_ACCESS", targetPath.toString(), false, "No active workspace");
            throw new TrystDomainException("Access denied: No active workspace available.");
        }

        Path rootPath = workspace.getRootPath();
        Path normalizedTarget = targetPath.toAbsolutePath().normalize();
        Path normalizedRoot = rootPath.toAbsolutePath().normalize();

        // 1. Lexical prefix check against traversal (e.g. /workspace/../../etc/passwd)
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            recordAudit("PATH_ACCESS", targetPath.toString(), false, "Path outside workspace root: " + normalizedRoot);
            throw new TrystDomainException("Access denied: Path '" + targetPath + "' is outside authorized workspace root '" + normalizedRoot + "'.");
        }

        // 2. Canonical real path resolution if paths exist (to catch symlink escapes)
        try {
            Path realRoot = Files.exists(rootPath) ? rootPath.toRealPath() : normalizedRoot;
            Path checkPath = normalizedTarget;
            // Climb up to the nearest existing ancestor
            while (!Files.exists(checkPath) && checkPath.getParent() != null && !checkPath.equals(normalizedRoot)) {
                checkPath = checkPath.getParent();
            }
            if (Files.exists(checkPath)) {
                Path realCheck = checkPath.toRealPath();
                if (!realCheck.startsWith(realRoot)) {
                    recordAudit("PATH_ACCESS", targetPath.toString(), false, "Symlink escape detected: " + realCheck + " escapes " + realRoot);
                    throw new TrystDomainException("Access denied: Path '" + targetPath + "' escapes authorized workspace root via symlink.");
                }
            }
        } catch (IOException e) {
            recordAudit("PATH_ACCESS", targetPath.toString(), false, "Failed to resolve real path: " + e.getMessage());
            throw new TrystDomainException("Access denied: Could not verify canonical path safety for '" + targetPath + "'.", e);
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
        recordAudit("system", action, "resource", target, null, null, granted ? "GRANTED" : "DENIED", reason);
    }

    public void recordAudit(String actorId, String action, String targetType, String targetId,
                            String projectId, String taskId, String outcome, String detailsJson) {
        auditLog.add(new AuditRecord(java.time.Instant.now().toString(), action, targetId != null ? targetId : targetType, "GRANTED".equalsIgnoreCase(outcome) || "ALLOWED".equalsIgnoreCase(outcome), detailsJson));
        if (auditEventRepository != null) {
            try {
                auditEventRepository.save(eu.kalafatic.tryst.domain.model.AuditEvent.create(
                        actorId, action, targetType, targetId, projectId, taskId, outcome, detailsJson
                ));
            } catch (Exception e) {
                // Keep resilient
            }
        }
    }

    public CopyOnWriteArrayList<AuditRecord> getAuditLog() {
        return auditLog;
    }
}
