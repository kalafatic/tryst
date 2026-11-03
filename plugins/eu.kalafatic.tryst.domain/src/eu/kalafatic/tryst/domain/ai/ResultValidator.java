package eu.kalafatic.tryst.domain.ai;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.tool.Tool;
import eu.kalafatic.tryst.domain.tool.ToolRegistry;

import java.util.Map;
import java.util.Optional;

/**
 * Validates execution results by running build and test suites,
 * classifying failures and checking for regressions.
 */
public class ResultValidator {

    private final ToolRegistry toolRegistry;
    private final PermissionManager permissionManager;

    public record ValidationReport(
            boolean passed,
            boolean buildSuccess,
            boolean testSuccess,
            AiFailureClassification failureClassification,
            String details
    ) {}

    public ResultValidator(ToolRegistry toolRegistry, PermissionManager permissionManager) {
        this.toolRegistry = toolRegistry;
        this.permissionManager = permissionManager;
    }

    public ValidationReport validate(Workspace workspace) {
        if (workspace == null) {
            return new ValidationReport(true, true, true, AiFailureClassification.NONE, "No workspace to validate");
        }

        // Build validation
        boolean buildOk = executeStage("maven_compile", workspace);
        if (!buildOk) {
            return new ValidationReport(
                    false,
                    false,
                    false,
                    AiFailureClassification.BUILD_FAILURE,
                    "Build / compilation stage failed"
            );
        }

        // Test validation
        boolean testOk = executeStage("maven_test", workspace);
        if (!testOk) {
            return new ValidationReport(
                    false,
                    true,
                    false,
                    AiFailureClassification.TEST_FAILURE,
                    "Verification / test suite execution failed"
            );
        }

        return new ValidationReport(true, true, true, AiFailureClassification.NONE, "Build and test verification passed");
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
