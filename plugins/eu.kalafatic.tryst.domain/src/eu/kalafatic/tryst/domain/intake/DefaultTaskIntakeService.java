package eu.kalafatic.tryst.domain.intake;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.github.GitHubCredentialRepository;
import eu.kalafatic.tryst.domain.github.GitHubService;
import eu.kalafatic.tryst.domain.model.Permission;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskInstruction;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.repository.ProjectRepository;
import eu.kalafatic.tryst.domain.repository.TaskInstructionRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.security.SecurityContext;
import eu.kalafatic.tryst.domain.service.TaskOrchestrator;
import eu.kalafatic.tryst.domain.service.WorkspaceService;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Default implementation of TaskIntakeService.
 * Validates repository, branch, and user requests; detects missing context and acceptance criteria;
 * generates suggested titles; classifies request types; and orchestrates non-destructive intake submission.
 */
public class DefaultTaskIntakeService implements TaskIntakeService {

    private final TaskOrchestrator taskOrchestrator;
    private final TaskRepository taskRepository;
    private final TaskInstructionRepository taskInstructionRepository;
    private final ProjectRepository projectRepository;
    private final WorkspaceService workspaceService;
    private final GitHubService gitHubService;
    private final GitHubCredentialRepository gitHubCredentialRepository;
    private final PermissionManager permissionManager;

    public DefaultTaskIntakeService(
            TaskOrchestrator taskOrchestrator,
            TaskRepository taskRepository,
            TaskInstructionRepository taskInstructionRepository,
            ProjectRepository projectRepository,
            WorkspaceService workspaceService,
            GitHubService gitHubService,
            GitHubCredentialRepository gitHubCredentialRepository,
            PermissionManager permissionManager
    ) {
        this.taskOrchestrator = taskOrchestrator;
        this.taskRepository = taskRepository;
        this.taskInstructionRepository = taskInstructionRepository;
        this.projectRepository = projectRepository;
        this.workspaceService = workspaceService;
        this.gitHubService = gitHubService;
        this.gitHubCredentialRepository = gitHubCredentialRepository;
        this.permissionManager = permissionManager != null ? permissionManager : new PermissionManager();
    }

    @Override
    public TaskIntakeAnalysis analyzeAndValidate(TaskIntakeRequest request, String userId, SecurityContext context) {
        Objects.requireNonNull(request, "request must not be null");
        String effectiveUser = (userId != null && !userId.isBlank()) ? userId : "default-user";

        List<String> errors = new ArrayList<>();
        List<String> missingInfo = new ArrayList<>();

        // 1. Authorization check
        if (context != null && !context.user().hasPermission(Permission.EXECUTE_TASKS)) {
            errors.add("User lacks EXECUTE_TASKS permission to submit development tasks.");
        }

        // 2. Description validation
        String desc = request.description() != null ? request.description().trim() : "";
        if (desc.isEmpty()) {
            errors.add("Task description is required. Please provide details of the desired change or dictate a request.");
        } else if (desc.length() < 10) {
            missingInfo.add("Task description is very short (" + desc.length() + " chars). Providing more detailed instructions improves AI execution accuracy.");
        }

        // 3. Repository & Branch validation
        String repoUrl = request.repositoryUrl() != null ? request.repositoryUrl().trim() : "";
        String branch = (request.branch() != null && !request.branch().trim().isEmpty()) ? request.branch().trim() : "main";
        String projectId = request.projectId() != null ? request.projectId().trim() : null;

        String repoContext = "None";

        if (projectId != null && !projectId.isEmpty() && projectRepository != null) {
            Optional<Project> projOpt = projectRepository.findById(projectId);
            if (projOpt.isPresent()) {
                Project p = projOpt.get();
                if (context != null) {
                    try {
                        permissionManager.validateUserProjectAccess(effectiveUser, p, context);
                    } catch (Exception e) {
                        errors.add("Access denied to project: " + e.getMessage());
                    }
                }
                repoContext = "Local Project: " + p.getName() + " (" + p.getLocation() + ")";
            } else {
                errors.add("Referenced project ID does not exist: " + projectId);
            }
        } else if (!repoUrl.isEmpty()) {
            boolean isLocalPath = repoUrl.startsWith("/") || repoUrl.startsWith("./") || repoUrl.startsWith("../") ||
                    repoUrl.startsWith("file://") || repoUrl.startsWith("~") || (repoUrl.length() > 2 && repoUrl.charAt(1) == ':');
            if (isLocalPath) {
                repoContext = "Local Repository: " + repoUrl + " (" + branch + ")";
            } else {
                repoContext = "Repository: " + repoUrl + " (" + branch + ")";
                // Validate repository clone URL format
                if (!repoUrl.startsWith("http://") && !repoUrl.startsWith("https://") && !repoUrl.startsWith("git@") && !repoUrl.startsWith("ssh://")) {
                    errors.add("Repository URL must be a valid HTTP(S), Git, or local repository path: " + repoUrl);
                }
                if (gitHubCredentialRepository != null) {
                    boolean hasCred = gitHubCredentialRepository.findByUserId(effectiveUser).isPresent();
                    if (!hasCred && (repoUrl.contains("github.com") && !repoUrl.contains("/public/"))) {
                        missingInfo.add("No connected GitHub credential found for user. Access to private repositories will fail unless public.");
                    }
                }
            }
        } else if (workspaceService != null && workspaceService.getCurrentWorkspace() != null) {
            Workspace ws = workspaceService.getCurrentWorkspace();
            repoContext = "Current Workspace: " + ws.getRootPath();
        } else {
            missingInfo.add("No repository or project selected. Task will execute in default sandbox root.");
        }

        // 4. Missing Information analysis
        if (request.acceptanceCriteria() == null || request.acceptanceCriteria().isEmpty()) {
            missingInfo.add("No acceptance criteria defined. Clear criteria help verify when the change is successfully completed.");
        }
        if (request.expectedOutcome() == null || request.expectedOutcome().trim().isEmpty()) {
            missingInfo.add("Expected outcome is not explicitly defined. Consider summarizing the expected behavior after this change.");
        }

        // 5. Title generation & Refinement
        String title = request.title() != null ? request.title().trim() : "";
        if (title.isEmpty()) {
            title = generateTitleFromDescription(desc);
        }

        // 6. Classification & Priority
        RequestClassification classification = classifyRequest(title, desc);
        TaskPriority priority = request.priority() != null ? request.priority() : suggestPriority(title, desc);
        String complexity = estimateComplexity(desc, request.constraints(), request.acceptanceCriteria());

        boolean isValid = errors.isEmpty();
        return new TaskIntakeAnalysis(
                isValid,
                title,
                classification,
                priority,
                missingInfo,
                errors,
                repoContext,
                complexity
        );
    }

    @Override
    public TaskIntakeResult submitIntake(TaskIntakeRequest request, String userId, SecurityContext context) {
        String effectiveUser = (userId != null && !userId.isBlank()) ? userId : "default-user";

        // Non-destructive validation first
        TaskIntakeAnalysis analysis = analyzeAndValidate(request, effectiveUser, context);
        if (!analysis.valid()) {
            throw new TrystDomainException("Task intake validation failed: " + String.join("; ", analysis.validationErrors()));
        }

        String finalTitle = (request.title() != null && !request.title().trim().isEmpty())
                ? request.title().trim()
                : analysis.suggestedTitle();
        String finalDescription = request.description() != null ? request.description().trim() : "";
        TaskPriority finalPriority = request.priority() != null ? request.priority() : analysis.suggestedPriority();

        // 1. Resolve or create Project / Workspace context
        String projectId = request.projectId() != null && !request.projectId().trim().isEmpty() ? request.projectId().trim() : null;
        String repoUrl = request.repositoryUrl() != null && !request.repositoryUrl().trim().isEmpty() ? request.repositoryUrl().trim() : null;
        String branch = (request.branch() != null && !request.branch().trim().isEmpty()) ? request.branch().trim() : "main";

        // Auto-provision or register Project if repository URL/path is specified without a local project
        if (projectId == null && repoUrl != null && projectRepository != null) {
            String repoName = extractRepoName(repoUrl);
            Path projectPath;
            if (repoUrl.startsWith("/") || (repoUrl.length() > 2 && repoUrl.charAt(1) == ':')) {
                projectPath = Path.of(repoUrl);
            } else if (repoUrl.startsWith("file://")) {
                projectPath = Path.of(URI.create(repoUrl));
            } else {
                projectPath = Path.of(System.getProperty("user.home"), ".tryst", "projects", effectiveUser, repoName);
            }
            Project project = new Project(UUID.randomUUID().toString(), effectiveUser, repoName, "Repository: " + repoUrl, projectPath, eu.kalafatic.tryst.domain.model.ProjectStatus.ACTIVE, false, java.time.Instant.now(), java.time.Instant.now());
            projectRepository.save(project);
            projectId = project.getId();
        }

        // 2. Submit task via TaskOrchestrator
        if (taskOrchestrator == null) {
            throw new TrystDomainException("TaskOrchestrator is unavailable; cannot submit intake");
        }

        Task task = taskOrchestrator.submitTask(
                projectId,
                null,
                finalTitle,
                finalDescription,
                finalPriority,
                effectiveUser,
                null,
                context
        );

        // 3. Allocate isolated workspace with repository URL and branch if available
        if (workspaceService != null && (repoUrl != null || projectId != null)) {
            Workspace ws = workspaceService.allocateTaskWorkspace(effectiveUser, task.getId(), projectId, repoUrl, branch);
            TrystLog.info(DefaultTaskIntakeService.class, "Allocated workspace for intake task " + task.getId() + " at " + ws.getRootPath());
        }

        // 4. Persist original request and instructions
        List<TaskInstruction> instructions = new ArrayList<>();

        // Original request
        TaskInstruction orig = new TaskInstruction(
                task.getId(),
                TaskInstruction.InstructionType.ORIGINAL_REQUEST,
                finalDescription,
                effectiveUser
        );
        instructions.add(orig);
        if (taskInstructionRepository != null) {
            taskInstructionRepository.save(orig);
        }

        // Expected outcome
        if (request.expectedOutcome() != null && !request.expectedOutcome().trim().isEmpty()) {
            TaskInstruction out = new TaskInstruction(
                    task.getId(),
                    TaskInstruction.InstructionType.EXPECTED_OUTCOME,
                    request.expectedOutcome().trim(),
                    effectiveUser
            );
            instructions.add(out);
            if (taskInstructionRepository != null) {
                taskInstructionRepository.save(out);
            }
        }

        // Acceptance criteria
        if (request.acceptanceCriteria() != null) {
            for (String crit : request.acceptanceCriteria()) {
                if (crit != null && !crit.trim().isEmpty()) {
                    TaskInstruction inst = new TaskInstruction(
                            task.getId(),
                            TaskInstruction.InstructionType.ACCEPTANCE_CRITERIA,
                            crit.trim(),
                            effectiveUser
                    );
                    instructions.add(inst);
                    if (taskInstructionRepository != null) {
                        taskInstructionRepository.save(inst);
                    }
                }
            }
        }

        // Execution constraints
        if (request.constraints() != null) {
            for (String constraint : request.constraints()) {
                if (constraint != null && !constraint.trim().isEmpty()) {
                    TaskInstruction inst = new TaskInstruction(
                            task.getId(),
                            TaskInstruction.InstructionType.CONSTRAINT,
                            constraint.trim(),
                            effectiveUser
                    );
                    instructions.add(inst);
                    if (taskInstructionRepository != null) {
                        taskInstructionRepository.save(inst);
                    }
                }
            }
        }

        // Supplementary instructions
        if (request.supplementaryInstructions() != null) {
            for (String supp : request.supplementaryInstructions()) {
                if (supp != null && !supp.trim().isEmpty()) {
                    TaskInstruction inst = new TaskInstruction(
                            task.getId(),
                            TaskInstruction.InstructionType.SUPPLEMENTARY,
                            supp.trim(),
                            effectiveUser
                    );
                    instructions.add(inst);
                    if (taskInstructionRepository != null) {
                        taskInstructionRepository.save(inst);
                    }
                }
            }
        }

        // Attachments
        if (request.attachments() != null) {
            for (TaskAttachment att : request.attachments()) {
                if (att != null && att.content() != null && !att.content().isBlank()) {
                    String formatted = "Attachment: " + att.name() + " (" + att.size() + " bytes)\n" + att.content();
                    TaskInstruction inst = new TaskInstruction(
                            task.getId(),
                            TaskInstruction.InstructionType.ATTACHMENT,
                            formatted,
                            effectiveUser
                    );
                    instructions.add(inst);
                    if (taskInstructionRepository != null) {
                        taskInstructionRepository.save(inst);
                    }
                }
            }
        }

        TrystLog.info(DefaultTaskIntakeService.class, "Task intake submitted successfully: " + task.getId() + " (" + finalTitle + ") with " + instructions.size() + " persisted instructions");
        return new TaskIntakeResult(task, analysis, instructions);
    }

    @Override
    public TaskInstruction addSupplementaryInstruction(String taskId, String instructionText,
                                                       TaskInstruction.InstructionType type,
                                                       String userId, SecurityContext context) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (instructionText == null || instructionText.trim().isEmpty()) {
            throw new TrystDomainException("Instruction text must not be blank");
        }
        String effectiveUser = (userId != null && !userId.isBlank()) ? userId : "default-user";

        if (taskRepository != null) {
            Optional<Task> opt = taskRepository.findTaskById(taskId);
            if (opt.isEmpty()) {
                throw new TrystDomainException("Task not found: " + taskId);
            }
            Task task = opt.get();
            if (context != null) {
                permissionManager.validateTaskAccess(effectiveUser, task, context, Permission.EXECUTE_TASKS);
            }
        }

        TaskInstruction.InstructionType effectiveType = type != null ? type : TaskInstruction.InstructionType.SUPPLEMENTARY;
        TaskInstruction instruction = new TaskInstruction(taskId, effectiveType, instructionText.trim(), effectiveUser);

        if (taskInstructionRepository != null) {
            taskInstructionRepository.save(instruction);
        }

        TrystLog.info(DefaultTaskIntakeService.class, "Added " + effectiveType + " instruction to task " + taskId + " by " + effectiveUser);
        return instruction;
    }

    @Override
    public List<TaskInstruction> getInstructionsForTask(String taskId, String userId, SecurityContext context) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (taskInstructionRepository == null) {
            return List.of();
        }
        return taskInstructionRepository.findByTaskId(taskId);
    }

    private String generateTitleFromDescription(String desc) {
        if (desc == null || desc.isBlank()) {
            return "Autonomous Task";
        }

        String firstLine = desc.lines().findFirst().orElse(desc).trim();
        // Remove markdown bullets or numbers
        firstLine = firstLine.replaceFirst("^[-*#0-9.]+\\s*", "");

        if (firstLine.length() > 60) {
            int lastSpace = firstLine.substring(0, 57).lastIndexOf(' ');
            if (lastSpace > 20) {
                return firstLine.substring(0, lastSpace) + "...";
            }
            return firstLine.substring(0, 57) + "...";
        }
        return firstLine.isEmpty() ? "Autonomous Task" : firstLine;
    }

    private RequestClassification classifyRequest(String title, String desc) {
        String combined = (title + " " + desc).toLowerCase(Locale.ROOT);

        if (combined.contains("bug") || combined.contains("fix") || combined.contains("error") ||
            combined.contains("defect") || combined.contains("issue") || combined.contains("broken") ||
            combined.contains("regression") || combined.contains("exception") || combined.contains("fail")) {
            return RequestClassification.BUG_FIX;
        }
        if (combined.contains("test") || combined.contains("coverage") || combined.contains("unit test") ||
            combined.contains("integration test") || combined.contains("assert")) {
            return RequestClassification.TEST;
        }
        if (combined.contains("refactor") || combined.contains("clean up") || combined.contains("optimize") ||
            combined.contains("simplify") || combined.contains("decouple") || combined.contains("restructure")) {
            return RequestClassification.REFACTORING;
        }
        if (combined.contains("doc") || combined.contains("readme") || combined.contains("guide") ||
            combined.contains("comment") || combined.contains("specification")) {
            return RequestClassification.DOCUMENTATION;
        }
        if (combined.contains("security") || combined.contains("vulnerability") || combined.contains("auth") ||
            combined.contains("sanitize") || combined.contains("leak") || combined.contains("cve")) {
            return RequestClassification.SECURITY;
        }
        if (combined.contains("add") || combined.contains("implement") || combined.contains("create") ||
            combined.contains("feature") || combined.contains("support") || combined.contains("new")) {
            return RequestClassification.FEATURE;
        }
        return RequestClassification.GENERAL;
    }

    private TaskPriority suggestPriority(String title, String desc) {
        String combined = (title + " " + desc).toLowerCase(Locale.ROOT);
        if (combined.contains("urgent") || combined.contains("critical") || combined.contains("cve") ||
            combined.contains("hotfix") || combined.contains("outage") || combined.contains("blocker")) {
            return TaskPriority.URGENT;
        }
        if (combined.contains("high priority") || combined.contains("asap") || combined.contains("important") ||
            combined.contains("crash") || combined.contains("regression")) {
            return TaskPriority.HIGH;
        }
        if (combined.contains("low priority") || combined.contains("minor") || combined.contains("cosmetic") ||
            combined.contains("typo") || combined.contains("nitpick")) {
            return TaskPriority.LOW;
        }
        return TaskPriority.NORMAL;
    }

    private String estimateComplexity(String desc, List<String> constraints, List<String> criteria) {
        int factors = 0;
        if (desc.length() > 500) factors++;
        if (constraints != null && !constraints.isEmpty()) factors += constraints.size();
        if (criteria != null && !criteria.isEmpty()) factors += criteria.size();

        if (factors >= 5) return "HIGH";
        if (factors >= 2) return "MEDIUM";
        return "LOW";
    }

    private String extractRepoName(String repoUrl) {
        String clean = repoUrl.trim();
        if (clean.endsWith(".git")) {
            clean = clean.substring(0, clean.length() - 4);
        }
        int slash = clean.lastIndexOf('/');
        if (slash != -1 && slash < clean.length() - 1) {
            return clean.substring(slash + 1);
        }
        int colon = clean.lastIndexOf(':');
        if (colon != -1 && colon < clean.length() - 1) {
            return clean.substring(colon + 1);
        }
        return "repo-" + System.currentTimeMillis();
    }
}
