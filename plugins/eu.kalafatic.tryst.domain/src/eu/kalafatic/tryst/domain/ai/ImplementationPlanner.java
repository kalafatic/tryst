package eu.kalafatic.tryst.domain.ai;

import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.Workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Implementation Planner formulating multi-step actionable plans for task execution.
 */
public class ImplementationPlanner {

    public record PlanStep(int order, String description, List<Path> targetFiles) {}

    public record ExecutionPlan(String goal, List<PlanStep> steps, String reasoning) {}

    public ExecutionPlan createPlan(Task task, TaskAnalyzer.AnalysisResult analysis, Workspace workspace) {
        Objects.requireNonNull(task, "task must not be null");

        List<PlanStep> steps = new ArrayList<>();
        int stepNum = 1;

        steps.add(new PlanStep(stepNum++, "Inspect identified source files and dependencies",
                analysis != null ? analysis.identifiedFiles() : List.of()));

        steps.add(new PlanStep(stepNum++, "Apply necessary code and configuration modifications",
                analysis != null ? analysis.identifiedFiles() : List.of()));

        steps.add(new PlanStep(stepNum++, "Verify compilation and project build", List.of()));

        steps.add(new PlanStep(stepNum++, "Execute automated test suites to verify correctness", List.of()));

        steps.add(new PlanStep(stepNum++, "Generate unified diff and prepare review package", List.of()));

        String reasoning = "Step-by-step autonomous execution adhering to project conventions (" +
                (analysis != null ? analysis.discoveredConventions() : "standard") + ").";

        return new ExecutionPlan("Implement: " + task.getTitle(), steps, reasoning);
    }
}
