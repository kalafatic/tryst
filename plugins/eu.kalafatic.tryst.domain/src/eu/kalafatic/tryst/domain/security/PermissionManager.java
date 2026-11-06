package eu.kalafatic.tryst.domain.security;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.Permission;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.review.CodeReview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Authoritative, least-privilege security and permission manager for TRYST.
 * Enforces explicit ALLOW/DENY decisions (where DENY strictly takes precedence),
 * canonical real-path workspace containment, symlink escape protection, null-byte rejection,
 * multi-user project/workspace/task authorization, Git commit gating, and persistent audit logging.
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
    private eu.kalafatic.tryst.domain.repository.UserRepository userRepository;

    public PermissionManager() {
        this(null, null);
    }

    public PermissionManager(eu.kalafatic.tryst.domain.repository.AuditEventRepository auditEventRepository) {
        this(auditEventRepository, null);
    }

    public PermissionManager(eu.kalafatic.tryst.domain.repository.AuditEventRepository auditEventRepository,
                             eu.kalafatic.tryst.domain.repository.UserRepository userRepository) {
        this.auditEventRepository = auditEventRepository;
        this.userRepository = userRepository;
    }

    public void setUserRepository(eu.kalafatic.tryst.domain.repository.UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public void setAuditEventRepository(eu.kalafatic.tryst.domain.repository.AuditEventRepository repository) {
        this.auditEventRepository = repository;
    }

    public PermissionDecision decide(PermissionDecision... decisions) {
        return PermissionDecision.combine(decisions);
    }

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

        String username = context != null ? context.getUsername() : null;
        String ctxUserId = context != null ? context.getUserId() : null;
        boolean matchesOwner = (ownerId != null) && (
                ownerId.equals(normalizedUser) ||
                (ctxUserId != null && ownerId.equals(ctxUserId)) ||
                (username != null && ownerId.equals(username)) ||
                (context == null && ownerId.equals(normalizedUser))
        );
        if (!matchesOwner && context != null && normalizedUser.equals(context.getUsername()) && ownerId != null && ownerId.equals(context.getUserId())) {
            matchesOwner = true;
        }
        // If owner is a user ID, check if normalizedUser is the username of that user
        if (!matchesOwner && userRepository != null && ownerId != null) {
            matchesOwner = userRepository.findById(ownerId)
                    .map(u -> u.getUsername().equalsIgnoreCase(normalizedUser) || u.getId().equals(normalizedUser))
                    .orElse(false);
        }
        boolean allowed = isAdmin || matchesOwner;
        if (!allowed) {
            recordAudit(normalizedUser, "PROJECT_ACCESS", "project", project.getId(), project.getId(), null, "DENIED",
                    "User '" + normalizedUser + "' not authorized for project owned by '" + ownerId + "'");
            throw new TrystDomainException("Access denied: User '" + normalizedUser + "' is not authorized to access project '" + project.getName() + "'.");
        }

        if (context != null && !context.user().hasPermission(Permission.ACCESS_PROJECTS)) {
            recordAudit(normalizedUser, "PROJECT_ACCESS", "project", project.getId(), project.getId(), null, "DENIED",
                    "User lacks ACCESS_PROJECTS permission");
            throw new TrystDomainException("Access denied: User lacks ACCESS_PROJECTS permission.");
        }

        recordAudit(normalizedUser, "PROJECT_ACCESS", "project", project.getId(), project.getId(), null, "ALLOWED", "Authorized access");
    }

    public void validateWorkspaceAccess(String userId, Workspace workspace, SecurityContext context) {
        Objects.requireNonNull(workspace, "workspace must not be null");
        if (userId == null || userId.trim().isEmpty()) {
            recordAudit("anonymous", "WORKSPACE_ACCESS", "workspace", workspace.getId(), null, workspace.getTaskId(), "DENIED", "Missing userId");
            throw new TrystDomainException("Access denied: User identification is required.");
        }
        String normalizedUser = userId.trim();
        boolean isAdmin = (context != null && context.isAdmin());
        String ownerId = workspace.getOwnerId();

        if (ownerId != null && !ownerId.equals(normalizedUser) && !isAdmin) {
            recordAudit(normalizedUser, "WORKSPACE_ACCESS", "workspace", workspace.getId(), null, workspace.getTaskId(), "DENIED",
                    "User '" + normalizedUser + "' not authorized for workspace owned by '" + ownerId + "'");
            throw new TrystDomainException("Access denied: Workspace is owned by '" + ownerId + "'.");
        }

        if (context != null && !context.user().hasPermission(Permission.ACCESS_WORKSPACE)) {
            recordAudit(normalizedUser, "WORKSPACE_ACCESS", "workspace", workspace.getId(), null, workspace.getTaskId(), "DENIED",
                    "User lacks ACCESS_WORKSPACE permission");
            throw new TrystDomainException("Access denied: ACCESS_WORKSPACE permission required.");
        }

        recordAudit(normalizedUser, "WORKSPACE_ACCESS", "workspace", workspace.getId(), null, workspace.getTaskId(), "ALLOWED", "Workspace access authorized");
    }

    public void validateTaskAccess(String userId, Task task, SecurityContext context, Permission requiredPermission) {
        Objects.requireNonNull(task, "task must not be null");
        if (userId == null || userId.trim().isEmpty()) {
            recordAudit("anonymous", "TASK_ACCESS", "task", task.getId(), task.getProjectId(), task.getId(), "DENIED", "Missing userId");
            throw new TrystDomainException("Access denied: User identification is required.");
        }
        String normalizedUser = userId.trim();

        if (requiredPermission != null && context != null && !context.user().hasPermission(requiredPermission)) {
            recordAudit(normalizedUser, "TASK_ACCESS", "task", task.getId(), task.getProjectId(), task.getId(), "DENIED",
                    "User lacks permission " + requiredPermission);
            throw new TrystDomainException("Access denied: Missing required permission " + requiredPermission);
        }

        recordAudit(normalizedUser, "TASK_ACCESS", "task", task.getId(), task.getProjectId(), task.getId(), "ALLOWED", "Task access authorized");
    }

    public void validateReviewAccess(String userId, CodeReview review, SecurityContext context, Permission requiredPermission) {
        Objects.requireNonNull(review, "review must not be null");
        if (userId == null || userId.trim().isEmpty()) {
            recordAudit("anonymous", "REVIEW_ACCESS", "review", review.getId(), review.getProjectId(), review.getTaskId(), "DENIED", "Missing userId");
            throw new TrystDomainException("Access denied: User identification is required.");
        }
        String normalizedUser = userId.trim();

        if (requiredPermission != null && context != null && !context.user().hasPermission(requiredPermission)) {
            recordAudit(normalizedUser, "REVIEW_ACCESS", "review", review.getId(), review.getProjectId(), review.getTaskId(), "DENIED",
                    "User lacks permission " + requiredPermission);
            throw new TrystDomainException("Access denied: Missing required permission " + requiredPermission);
        }

        recordAudit(normalizedUser, "REVIEW_ACCESS", "review", review.getId(), review.getProjectId(), review.getTaskId(), "ALLOWED", "Review access authorized");
    }

    public void validateGitOperation(String userId, Project project, SecurityContext context, Permission requiredPermission) {
        if (project != null) {
            validateUserProjectAccess(userId, project, context);
        }
        if (requiredPermission != null && context != null && !context.user().hasPermission(requiredPermission)) {
            String normalizedUser = userId != null ? userId.trim() : "anonymous";
            recordAudit(normalizedUser, "GIT_OPERATION", "git", project != null ? project.getId() : "system",
                    project != null ? project.getId() : null, null, "DENIED", "User lacks permission " + requiredPermission);
            throw new TrystDomainException("Access denied: Missing required Git permission " + requiredPermission);
        }
    }

    public void validatePathWithinWorkspace(Path targetPath, Workspace workspace) {
        Objects.requireNonNull(targetPath, "targetPath must not be null");
        if (workspace == null) {
            recordAudit("PATH_ACCESS", targetPath.toString(), false, "No active workspace");
            throw new TrystDomainException("Access denied: No active workspace available.");
        }

        if (targetPath.toString().contains("\0")) {
            recordAudit("PATH_ACCESS", targetPath.toString(), false, "Null-byte injection detected");
            throw new TrystDomainException("Access denied: Path contains illegal characters.");
        }

        Path rootPath = workspace.getRootPath();
        Path normalizedTarget = targetPath.toAbsolutePath().normalize();
        Path normalizedRoot = rootPath.toAbsolutePath().normalize();

        if (!normalizedTarget.startsWith(normalizedRoot)) {
            recordAudit("PATH_ACCESS", targetPath.toString(), false, "Path outside workspace root: " + normalizedRoot);
            throw new TrystDomainException("Access denied: Path '" + targetPath + "' is outside authorized workspace root '" + normalizedRoot + "'.");
        }

        try {
            Path realRoot = Files.exists(rootPath) ? rootPath.toRealPath() : normalizedRoot;
            Path checkPath = normalizedTarget;
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
        boolean granted = "GRANTED".equalsIgnoreCase(outcome) || "ALLOWED".equalsIgnoreCase(outcome);
        auditLog.add(new AuditRecord(java.time.Instant.now().toString(), action, targetId != null ? targetId : targetType, granted, detailsJson));
        if (auditEventRepository != null) {
            try {
                auditEventRepository.save(eu.kalafatic.tryst.domain.model.AuditEvent.create(
                        actorId, action, targetType, targetId, projectId, taskId, outcome, detailsJson
                ));
            } catch (Exception e) {
                // Keep resilient
            }
        }

        if (!granted) {
            TrystLog.warn(PermissionManager.class,
                    "Authorization DENIED: Actor '" + actorId + "' action '" + action + "' on " + targetType + " " + targetId + ": " + detailsJson);
        }
    }

    public CopyOnWriteArrayList<AuditRecord> getAuditLog() {
        return auditLog;
    }
}
