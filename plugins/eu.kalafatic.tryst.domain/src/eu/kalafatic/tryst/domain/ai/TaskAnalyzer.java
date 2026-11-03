package eu.kalafatic.tryst.domain.ai;

import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.security.PermissionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Stage 1-3 Analyzer: analyzes task request, inspects repository structure,
 * identifies relevant source files, and extracts project conventions.
 */
public class TaskAnalyzer {

    private final PermissionManager permissionManager;

    public record AnalysisResult(
            String summary,
            List<Path> identifiedFiles,
            List<String> discoveredConventions,
            String buildSystem
    ) {}

    public TaskAnalyzer(PermissionManager permissionManager) {
        this.permissionManager = Objects.requireNonNull(permissionManager, "permissionManager must not be null");
    }

    public AnalysisResult analyze(Task task, Workspace workspace) {
        String description = (task.getTitle() != null ? task.getTitle() : "") + " "
                + (task.getDescription() != null ? task.getDescription() : "");

        List<Path> identified = new ArrayList<>();
        List<String> conventions = new ArrayList<>();
        String buildSystem = "unknown";

        if (workspace != null && Files.exists(workspace.getRootPath())) {
            Path root = workspace.getRootPath();
            permissionManager.validatePathWithinWorkspace(root, workspace);

            // Detect build system
            if (Files.exists(root.resolve("pom.xml"))) {
                buildSystem = "maven";
                conventions.add("Maven project structure (src/main/java, src/test/java, pom.xml)");
            } else if (Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts"))) {
                buildSystem = "gradle";
                conventions.add("Gradle build automation");
            } else if (Files.exists(root.resolve("package.json"))) {
                buildSystem = "npm";
                conventions.add("Node.js / npm package structure");
            }

            // Identify potential source files mentioned in description or key files
            try (Stream<Path> walk = Files.walk(root, 4)) {
                walk.filter(Files::isRegularFile)
                    .forEach(file -> {
                        String rel = root.relativize(file).toString();
                        if (rel.contains(".git") || rel.contains("target/") || rel.contains("node_modules/")) {
                            return;
                        }
                        // If file name or stem is mentioned in task description
                        String fileName = file.getFileName().toString();
                        if (description.toLowerCase().contains(fileName.toLowerCase())) {
                            identified.add(root.relativize(file));
                        }
                    });
            } catch (IOException ignored) {}
        }

        String summary = String.format("Analyzed task '%s': Build system '%s', %d relevant files identified, %d conventions.",
                task.getTitle(), buildSystem, identified.size(), conventions.size());

        return new AnalysisResult(summary, identified, conventions, buildSystem);
    }
}
