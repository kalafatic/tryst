package eu.kalafatic.tryst.domain.ai;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.build.BuildAndQualityService;
import eu.kalafatic.tryst.domain.build.BuildExecutionStatus;
import eu.kalafatic.tryst.domain.build.QualityReport;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.tool.Tool;
import eu.kalafatic.tryst.domain.tool.ToolRegistry;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Validates execution results by running build and test suites,
 * classifying failures and checking for regressions.
 * Decoupled from specific build tools via BuildAndQualityService.
 */
public class ResultValidator {

    private final ToolRegistry toolRegistry;
    private final PermissionManager permissionManager;
    private final BuildAndQualityService buildAndQualityService;

    public record ValidationReport(
            boolean passed,
            boolean buildSuccess,
            boolean testSuccess,
            AiFailureClassification failureClassification,
            String details,
            QualityReport qualityReport
    ) {}

    public ResultValidator(ToolRegistry toolRegistry, PermissionManager permissionManager) {
        this(toolRegistry, permissionManager, null);
    }

    public ResultValidator(ToolRegistry toolRegistry,
                           PermissionManager permissionManager,
                           BuildAndQualityService buildAndQualityService) {
        this.toolRegistry = toolRegistry;
        this.permissionManager = permissionManager != null ? permissionManager : new PermissionManager();
        this.buildAndQualityService = buildAndQualityService;
    }

    public ValidationReport validate(Workspace workspace) {
        return validate(workspace, "unknown-task", Duration.ofMinutes(10), () -> false, null);
    }

    public ValidationReport validate(
            Workspace workspace,
            String taskId,
            Duration timeout,
            BooleanSupplier cancellationSupplier,
            Consumer<String> outputListener
    ) {
        if (workspace == null) {
            return new ValidationReport(
                    true, true, true, AiFailureClassification.NONE, "No workspace to validate", null
            );
        }

        // If BuildAndQualityService is provided, use the robust multi-technology quality pipeline
        if (buildAndQualityService != null) {
            QualityReport report = buildAndQualityService.runQualityAssurance(
                    workspace, taskId, timeout, cancellationSupplier, outputListener
            );

            boolean buildOk = report.buildStatus() == BuildExecutionStatus.SUCCESSFUL ||
                    report.buildStatus() == BuildExecutionStatus.NOT_EXECUTED;
            boolean testOk = report.testStatus() == BuildExecutionStatus.SUCCESSFUL ||
                    report.testStatus() == BuildExecutionStatus.NOT_EXECUTED;

            AiFailureClassification failureClassification = AiFailureClassification.NONE;
            if (report.buildStatus() == BuildExecutionStatus.TIMED_OUT || report.testStatus() == BuildExecutionStatus.TIMED_OUT) {
                failureClassification = AiFailureClassification.TIMEOUT;
            } else if (report.buildStatus() == BuildExecutionStatus.CANCELLED || report.testStatus() == BuildExecutionStatus.CANCELLED) {
                failureClassification = AiFailureClassification.CANCELLATION;
            } else if (!buildOk) {
                failureClassification = AiFailureClassification.BUILD_FAILURE;
            } else if (!testOk) {
                failureClassification = AiFailureClassification.TEST_FAILURE;
            }

            boolean passed = buildOk && testOk;
            String details = report.failureDetails().isBlank() ? report.summary() : report.failureDetails();

            return new ValidationReport(
                    passed,
                    report.buildStatus() == BuildExecutionStatus.SUCCESSFUL,
                    report.testStatus() == BuildExecutionStatus.SUCCESSFUL,
                    failureClassification,
                    details,
                    report
            );
        }

        // Fallback backward-compatible tool stage execution if BuildAndQualityService is not wired
        boolean buildOk = executeStage("maven_compile", workspace);
        if (!buildOk) {
            return new ValidationReport(
                    false,
                    false,
                    false,
                    AiFailureClassification.BUILD_FAILURE,
                    "Build / compilation stage failed",
                    null
            );
        }

        boolean testOk = executeStage("maven_test", workspace);
        if (!testOk) {
            return new ValidationReport(
                    false,
                    true,
                    false,
                    AiFailureClassification.TEST_FAILURE,
                    "Verification / test suite execution failed",
                    null
            );
        }

        return new ValidationReport(
                true, true, true, AiFailureClassification.NONE, "Build and test verification passed", null
        );
    }

    private boolean executeStage(String toolName, Workspace workspace) {
        if (toolRegistry == null) {
            return true;
        }
        Optional<Tool> tool = toolRegistry.getTool(toolName);
        if (tool.isEmpty()) {
            return true;
        }
        try {
            Tool.ToolResult res = tool.get().execute(Map.of(), workspace, permissionManager);
            return res.success();
        } catch (Exception e) {
            TrystLog.warn(ResultValidator.class, "Failed running tool " + toolName + ": " + e.getMessage());
            return false;
        }
    }
}
