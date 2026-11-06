package eu.kalafatic.tryst.infrastructure.build;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.build.*;
import eu.kalafatic.tryst.domain.model.ExecutionResult;
import eu.kalafatic.tryst.domain.model.ManagedProcessRecord;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.service.ProcessExecutionManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Robust Build and Quality Assurance subsystem implementation.
 * Validates AI-generated changes before making them available for final review.
 *
 * Enforces:
 * 1. Multi-technology and build system detection.
 * 2. Strict sandbox path validation via PermissionManager.
 * 3. Controlled environment variables (stripping sensitive credentials/tokens).
 * 4. Process tracking with ProcessExecutionManager preventing cross-workspace leakage.
 * 5. Execution timeouts and cooperative cancellation.
 * 6. Explicit capture of exit codes, stdout, and stderr.
 * 7. Distinction between NOT_EXECUTED, SUCCESSFUL, FAILED, CANCELLED, and TIMED_OUT.
 */
public class BuildAndTestService implements BuildAndQualityService {

    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "mvn", "mvn.cmd", "./mvnw", "mvnw", "mvnw.cmd",
            "gradle", "gradle.bat", "./gradlew", "gradlew", "gradlew.bat",
            "npm", "npm.cmd", "yarn", "pnpm",
            "cargo", "cargo.exe",
            "python", "python3", "python.exe", "pytest",
            "make", "sh", "bash"
    );

    private final BuildTechnologyDetector technologyDetector;
    private final PermissionManager permissionManager;
    private ProcessExecutionManager processExecutionManager;

    public BuildAndTestService() {
        this(new DefaultBuildTechnologyDetector(), new PermissionManager(), null);
    }

    public BuildAndTestService(BuildTechnologyDetector technologyDetector,
                               PermissionManager permissionManager,
                               ProcessExecutionManager processExecutionManager) {
        this.technologyDetector = technologyDetector != null ? technologyDetector : new DefaultBuildTechnologyDetector();
        this.permissionManager = permissionManager != null ? permissionManager : new PermissionManager();
        this.processExecutionManager = processExecutionManager;
    }

    public void setProcessExecutionManager(ProcessExecutionManager processExecutionManager) {
        this.processExecutionManager = processExecutionManager;
    }

    @Override
    public BuildTechnologyDetector getTechnologyDetector() {
        return technologyDetector;
    }

    @Override
    public BuildSystemType detectTechnology(Path repositoryRoot) {
        return technologyDetector.detectBuildSystem(repositoryRoot);
    }

    @Override
    public StageExecutionResult executeBuild(
            Workspace workspace,
            String taskId,
            Duration timeout,
            BooleanSupplier cancellationSupplier,
            Consumer<String> outputListener
    ) {
        if (workspace == null || workspace.getRootPath() == null || !Files.exists(workspace.getRootPath())) {
            return StageExecutionResult.notExecuted("build", "Workspace path does not exist");
        }

        Path root = workspace.getRootPath();
        try {
            permissionManager.validatePathWithinWorkspace(root, workspace);
        } catch (Exception e) {
            return new StageExecutionResult("build", BuildExecutionStatus.FAILED, 1, List.of(), "", e.getMessage(), 0L, e.getMessage());
        }

        List<String> buildCmd = technologyDetector.resolveBuildCommand(root);
        if (buildCmd.isEmpty()) {
            return StageExecutionResult.notExecuted("build", "No build command detected for repository");
        }

        Duration effectiveTimeout = resolveTimeout(root, timeout, Duration.ofMinutes(5));
        return executeProcessStage("build", root, taskId, buildCmd, effectiveTimeout, cancellationSupplier, outputListener);
    }

    @Override
    public StageExecutionResult executeTests(
            Workspace workspace,
            String taskId,
            Duration timeout,
            BooleanSupplier cancellationSupplier,
            Consumer<String> outputListener
    ) {
        if (workspace == null || workspace.getRootPath() == null || !Files.exists(workspace.getRootPath())) {
            return StageExecutionResult.notExecuted("test", "Workspace path does not exist");
        }

        Path root = workspace.getRootPath();
        try {
            permissionManager.validatePathWithinWorkspace(root, workspace);
        } catch (Exception e) {
            return new StageExecutionResult("test", BuildExecutionStatus.FAILED, 1, List.of(), "", e.getMessage(), 0L, e.getMessage());
        }

        List<String> testCmd = technologyDetector.resolveTestCommand(root);
        if (testCmd.isEmpty()) {
            return StageExecutionResult.notExecuted("test", "No test command detected for repository");
        }

        Duration effectiveTimeout = resolveTimeout(root, timeout, Duration.ofMinutes(10));
        return executeProcessStage("test", root, taskId, testCmd, effectiveTimeout, cancellationSupplier, outputListener);
    }

    @Override
    public QualityReport runQualityAssurance(
            Workspace workspace,
            String taskId,
            Duration timeout,
            BooleanSupplier cancellationSupplier,
            Consumer<String> outputListener
    ) {
        if (workspace == null || workspace.getRootPath() == null || !Files.exists(workspace.getRootPath())) {
            return new QualityReport(
                    BuildSystemType.UNKNOWN,
                    BuildExecutionStatus.NOT_EXECUTED,
                    BuildExecutionStatus.NOT_EXECUTED,
                    false,
                    List.of(),
                    "No workspace available for quality assurance",
                    ""
            );
        }

        Path root = workspace.getRootPath();
        BuildSystemType systemType = technologyDetector.detectBuildSystem(root);

        List<StageExecutionResult> stages = new ArrayList<>();

        // 1. Build stage
        StageExecutionResult buildResult = executeBuild(workspace, taskId, timeout, cancellationSupplier, outputListener);
        stages.add(buildResult);

        if (buildResult.status() != BuildExecutionStatus.SUCCESSFUL && buildResult.status() != BuildExecutionStatus.NOT_EXECUTED) {
            String failureDetails = String.format("Build failed with status %s (exit code %d):\n%s\n%s",
                    buildResult.status(), buildResult.exitCode(), buildResult.stdout(), buildResult.stderr());

            return new QualityReport(
                    systemType,
                    buildResult.status(),
                    BuildExecutionStatus.NOT_EXECUTED,
                    false,
                    stages,
                    "Build stage failed; automated tests skipped.",
                    failureDetails
            );
        }

        // 2. Check cancellation before running tests
        if (cancellationSupplier != null && cancellationSupplier.getAsBoolean()) {
            StageExecutionResult cancelledTest = new StageExecutionResult(
                    "test", BuildExecutionStatus.CANCELLED, -1, List.of(), "", "Execution cancelled before running tests", 0L, "Cancelled by user"
            );
            stages.add(cancelledTest);
            return new QualityReport(
                    systemType,
                    buildResult.status(),
                    BuildExecutionStatus.CANCELLED,
                    false,
                    stages,
                    "Build succeeded; tests cancelled.",
                    "Cancelled by user"
            );
        }

        // 3. Test stage
        StageExecutionResult testResult = executeTests(workspace, taskId, timeout, cancellationSupplier, outputListener);
        stages.add(testResult);

        boolean testsExecuted = (testResult.status() != BuildExecutionStatus.NOT_EXECUTED);
        String summary;
        String failureDetails = "";

        if (testResult.status() == BuildExecutionStatus.SUCCESSFUL) {
            summary = "Build and test verification passed successfully (" + systemType + ").";
        } else if (testResult.status() == BuildExecutionStatus.NOT_EXECUTED) {
            summary = "Build succeeded (" + systemType + "). No test suite executed.";
        } else {
            summary = "Build succeeded (" + systemType + "), but automated tests failed (" + testResult.status() + ").";
            failureDetails = String.format("Tests failed with status %s (exit code %d):\n%s\n%s",
                    testResult.status(), testResult.exitCode(), testResult.stdout(), testResult.stderr());
        }

        return new QualityReport(
                systemType,
                buildResult.status(),
                testResult.status(),
                testsExecuted,
                stages,
                summary,
                failureDetails
        );
    }

    private StageExecutionResult executeProcessStage(
            String stageName,
            Path workingDirectory,
            String taskId,
            List<String> command,
            Duration timeout,
            BooleanSupplier cancellationSupplier,
            Consumer<String> outputListener
    ) {
        try {
            validateCommandSecurity(command);
        } catch (SecurityException se) {
            return new StageExecutionResult(
                    stageName,
                    BuildExecutionStatus.FAILED,
                    1,
                    command,
                    "",
                    "SecurityException: " + se.getMessage(),
                    0L,
                    se.getMessage()
            );
        }

        long startTime = System.currentTimeMillis();
        StringBuilder stdoutBuilder = new StringBuilder();
        StringBuilder stderrBuilder = new StringBuilder();

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDirectory.toFile());

        Map<String, String> env = pb.environment();
        configureSecureEnvironment(env, workingDirectory);

        Process process = null;
        ManagedProcessRecord processRecord = null;
        String recordId = null;

        try {
            process = pb.start();

            if (processExecutionManager != null) {
                processRecord = processExecutionManager.registerProcess(
                        taskId, process, "build-qa:" + stageName, workingDirectory.toString(), false
                );
                recordId = processRecord != null ? processRecord.getId() : null;
            }

            final Process procRef = process;
            Thread stdoutThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(procRef.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdoutBuilder.append(line).append("\n");
                        if (outputListener != null) {
                            outputListener.accept(line);
                        }
                    }
                } catch (Exception ignored) {}
            });

            Thread stderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(procRef.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderrBuilder.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });

            stdoutThread.start();
            stderrThread.start();

            long timeoutSeconds = (timeout != null && !timeout.isZero() && !timeout.isNegative())
                    ? timeout.toSeconds() : 300L;

            long elapsedSec = 0;
            boolean finished = false;
            boolean cancelled = false;

            while (elapsedSec < timeoutSeconds) {
                if (cancellationSupplier != null && cancellationSupplier.getAsBoolean()) {
                    cancelled = true;
                    break;
                }
                if (process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    finished = true;
                    break;
                }
                elapsedSec = (System.currentTimeMillis() - startTime) / 1000;
            }

            if (cancelled) {
                terminateProcessTree(process, recordId);
                long duration = System.currentTimeMillis() - startTime;
                return new StageExecutionResult(
                        stageName,
                        BuildExecutionStatus.CANCELLED,
                        -1,
                        command,
                        stdoutBuilder.toString(),
                        stderrBuilder.toString() + "\n[Execution cancelled by user]",
                        duration,
                        "Execution cancelled"
                );
            }

            if (!finished) {
                terminateProcessTree(process, recordId);
                long duration = System.currentTimeMillis() - startTime;
                return new StageExecutionResult(
                        stageName,
                        BuildExecutionStatus.TIMED_OUT,
                        -1,
                        command,
                        stdoutBuilder.toString(),
                        stderrBuilder.toString() + "\n[Execution timed out after " + timeoutSeconds + " seconds]",
                        duration,
                        "Execution timed out"
                );
            }

            try {
                stdoutThread.join(2000);
                stderrThread.join(2000);
            } catch (InterruptedException ignored) {}

            int exitCode = process.exitValue();
            long duration = System.currentTimeMillis() - startTime;

            if (processExecutionManager != null && recordId != null) {
                processExecutionManager.processCompleted(recordId, exitCode, duration);
            }

            BuildExecutionStatus status = (exitCode == 0)
                    ? BuildExecutionStatus.SUCCESSFUL
                    : BuildExecutionStatus.FAILED;

            return new StageExecutionResult(
                    stageName,
                    status,
                    exitCode,
                    command,
                    stdoutBuilder.toString(),
                    stderrBuilder.toString(),
                    duration,
                    exitCode == 0 ? "" : "Process exited with code " + exitCode
            );

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            if (process != null) {
                terminateProcessTree(process, recordId);
            }
            TrystLog.error(BuildAndTestService.class, "Execution of " + stageName + " failed: " + e.getMessage(), e);
            return new StageExecutionResult(
                    stageName,
                    BuildExecutionStatus.FAILED,
                    1,
                    command,
                    stdoutBuilder.toString(),
                    stderrBuilder.toString() + "\nException: " + e.getMessage(),
                    duration,
                    e.getMessage()
            );
        }
    }

    private void terminateProcessTree(Process process, String recordId) {
        if (processExecutionManager != null && recordId != null) {
            processExecutionManager.terminateProcess(recordId);
        } else if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void validateCommandSecurity(List<String> command) {
        if (command == null || command.isEmpty()) {
            throw new SecurityException("Command must not be empty");
        }
        String binary = command.get(0);
        Path binPath = Path.of(binary);
        String name = binPath.getFileName().toString();

        boolean allowed = ALLOWED_COMMANDS.contains(binary) || ALLOWED_COMMANDS.contains(name);
        if (!allowed) {
            throw new SecurityException("Command binary not permitted by security whitelist: " + binary);
        }
    }

    private void configureSecureEnvironment(Map<String, String> env, Path workingDirectory) {
        List<String> sensitiveKeys = env.keySet().stream()
                .filter(k -> {
                    String lower = k.toLowerCase();
                    return lower.contains("token") || lower.contains("secret") || lower.contains("password")
                            || lower.contains("auth") || lower.contains("key") || lower.contains("ghp_");
                })
                .toList();
        for (String k : sensitiveKeys) {
            env.remove(k);
        }

        env.put("CI", "true");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("WORKSPACE_DIR", workingDirectory.toString());
    }

    private Duration resolveTimeout(Path root, Duration requested, Duration defaultFallback) {
        if (requested != null && !requested.isZero() && !requested.isNegative()) {
            return requested;
        }
        BuildConfiguration config = technologyDetector.loadConfiguration(root);
        if (config != null && config.timeoutSeconds() != null && config.timeoutSeconds() > 0) {
            return Duration.ofSeconds(config.timeoutSeconds());
        }
        return defaultFallback;
    }

    public ExecutionResult executeMaven(
            Path workingDirectory,
            List<String> goals,
            int timeoutSeconds,
            Consumer<String> outputListener
    ) {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        Objects.requireNonNull(goals, "goals must not be null");

        if (!Files.exists(workingDirectory.resolve("pom.xml"))) {
            return new ExecutionResult(
                    "none",
                    1,
                    "",
                    "Cannot execute Maven build: pom.xml not found in " + workingDirectory,
                    0
            );
        }

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path wrapper = workingDirectory.resolve(isWindows ? "mvnw.cmd" : "mvnw");
        String mavenBin = Files.isRegularFile(wrapper) ? (isWindows ? wrapper.toString() : "./mvnw") : (isWindows ? "mvn.cmd" : "mvn");

        List<String> command = new ArrayList<>();
        command.add(mavenBin);
        command.add("-B");
        command.addAll(goals);

        StageExecutionResult stage = executeProcessStage(
                "maven",
                workingDirectory,
                "legacy-maven",
                command,
                Duration.ofSeconds(timeoutSeconds > 0 ? timeoutSeconds : 300),
                null,
                outputListener
        );

        return new ExecutionResult(
                "none",
                stage.status() == BuildExecutionStatus.SUCCESSFUL ? 0 : (stage.exitCode() != 0 ? stage.exitCode() : 1),
                stage.stdout(),
                stage.stderr(),
                stage.durationMs()
        );
    }

    public ExecutionResult runTests(Path workingDirectory, int timeoutSeconds, Consumer<String> outputListener) {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        BuildSystemType system = technologyDetector.detectBuildSystem(workingDirectory);
        if (system == BuildSystemType.MAVEN) {
            return executeMaven(workingDirectory, List.of("test"), timeoutSeconds, outputListener);
        }

        List<String> testCmd = technologyDetector.resolveTestCommand(workingDirectory);
        if (testCmd.isEmpty()) {
            return new ExecutionResult("none", 0, "No tests executed for " + system, "", 0L);
        }

        StageExecutionResult stage = executeProcessStage(
                "test",
                workingDirectory,
                "runTests",
                testCmd,
                Duration.ofSeconds(timeoutSeconds > 0 ? timeoutSeconds : 300),
                null,
                outputListener
        );

        return new ExecutionResult("none", stage.exitCode(), stage.stdout(), stage.stderr(), stage.durationMs());
    }

    public ExecutionResult runBuild(Path workingDirectory, int timeoutSeconds, Consumer<String> outputListener) {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        BuildSystemType system = technologyDetector.detectBuildSystem(workingDirectory);
        if (system == BuildSystemType.MAVEN) {
            return executeMaven(workingDirectory, List.of("compile"), timeoutSeconds, outputListener);
        }

        List<String> buildCmd = technologyDetector.resolveBuildCommand(workingDirectory);
        if (buildCmd.isEmpty()) {
            return new ExecutionResult("none", 0, "No build executed for " + system, "", 0L);
        }

        StageExecutionResult stage = executeProcessStage(
                "build",
                workingDirectory,
                "runBuild",
                buildCmd,
                Duration.ofSeconds(timeoutSeconds > 0 ? timeoutSeconds : 300),
                null,
                outputListener
        );

        return new ExecutionResult("none", stage.exitCode(), stage.stdout(), stage.stderr(), stage.durationMs());
    }
}
