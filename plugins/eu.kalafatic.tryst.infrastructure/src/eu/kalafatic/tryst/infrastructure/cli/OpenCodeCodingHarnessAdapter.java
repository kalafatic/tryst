package eu.kalafatic.tryst.infrastructure.cli;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.harness.CodingHarnessAdapter;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.infrastructure.vcs.GitService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * OpenCode coding-harness adapter (Phase 3, WP-3.7). Executes real coding tasks with the
 * OpenCode CLI inside the TRYST-controlled workspace/sandbox, preserving repository isolation
 * and TRYST's authority over task state, build/test validation, review, approval and commit.
 *
 * <p>Execution model: the OpenCode process runs with its working directory set to the task
 * workspace, bounded by an explicit timeout and a cancellation supplier; stdout is streamed
 * to the log consumer; changed files are identified afterwards through GitService. No host
 * filesystem paths outside the workspace are exposed to the harness.</p>
 */
public class OpenCodeCodingHarnessAdapter implements CodingHarnessAdapter {

    public static final String APPLICATION_ID = "opencode";
    public static final String APPLICATION_NAME = "OpenCode CLI";
    public static final String APPLICATION_TYPE = "OPENCODE";

    private volatile String binary = "opencode";
    private volatile String agent = "build";
    private volatile String model = "";
    private volatile boolean autoApprove = false;

    @Override
    public String getApplicationId() {
        return APPLICATION_ID;
    }

    @Override
    public String getApplicationName() {
        return APPLICATION_NAME;
    }

    @Override
    public String getApplicationType() {
        return APPLICATION_TYPE;
    }

    @Override
    public List<String> getCapabilities() {
        return List.of("CODE_IMPLEMENTATION");
    }

    @Override
    public boolean isAvailable() {
        return testConnection().success();
    }

    @Override
    public ConnectionTestResult testConnection() {
        List<String> command = List.of(binary, "--version");
        ProcessBuilder pb = new ProcessBuilder(command);
        try {
            Process process = pb.start();
            String output = readAll(process);
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return ConnectionTestResult.error("OpenCode CLI did not respond within 10 seconds");
            }
            if (process.exitValue() == 0) {
                return ConnectionTestResult.ok("OpenCode CLI available: " + output.trim());
            }
            return ConnectionTestResult.error("OpenCode CLI exited with code " + process.exitValue() + ": " + output.trim());
        } catch (IOException e) {
            return ConnectionTestResult.error("OpenCode CLI not found (" + binary + "): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ConnectionTestResult.error("Connection test interrupted");
        }
    }

    @Override
    public void updateConnectionConfiguration(Map<String, String> configuration) {
        if (configuration == null) return;
        if (configuration.containsKey("binary") && !configuration.get("binary").isBlank()) {
            this.binary = configuration.get("binary").trim();
        }
        if (configuration.containsKey("agent") && !configuration.get("agent").isBlank()) {
            this.agent = configuration.get("agent").trim();
        }
        if (configuration.containsKey("model")) {
            this.model = configuration.get("model") == null ? "" : configuration.get("model").trim();
        }
        if (configuration.containsKey("autoApprove")) {
            this.autoApprove = Boolean.parseBoolean(configuration.get("autoApprove"));
        }
    }

    @Override
    public Map<String, String> getConnectionConfiguration() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("binary", binary);
        config.put("agent", agent);
        config.put("model", model);
        config.put("autoApprove", Boolean.toString(autoApprove));
        return config;
    }

    @Override
    public HarnessExecutionResult executeTask(
            Task task,
            Workspace workspace,
            String instructions,
            String prompt,
            Consumer<String> logConsumer,
            BooleanSupplier cancellationSupplier,
            Duration timeout
    ) {
        if (workspace == null || workspace.getRootPath() == null) {
            return HarnessExecutionResult.error("No workspace available for OpenCode execution");
        }
        Path workspaceDir = workspace.getRootPath();
        if (!java.nio.file.Files.isDirectory(workspaceDir)) {
            return HarnessExecutionResult.error("Workspace directory does not exist: " + workspaceDir);
        }

        String effectivePrompt = (prompt != null && !prompt.isBlank()) ? prompt
                : (instructions != null && !instructions.isBlank()) ? instructions
                : (task.getTitle() != null ? task.getTitle() : "Perform the assigned task");
        effectivePrompt = applyContainmentConstraints(effectivePrompt, workspaceDir.toAbsolutePath().normalize().toString());

        List<String> command = new ArrayList<>();
        command.add(binary);
        command.add("run");
        command.add(effectivePrompt);
        if (!agent.isBlank()) {
            command.add("--agent");
            command.add(agent);
        }
        if (!model.isBlank()) {
            command.add("--model");
            command.add(normalizeModel(model));
        }
        command.add("--print-logs");
        // Explicit target directory: keeps OpenCode's project resolution on the intended
        // workspace (verified: without --dir, project fallback can redirect writes outside
        // the requested directory).
        command.add("--dir");
        command.add(workspaceDir.toAbsolutePath().toString());
        if (autoApprove) {
            command.add("--auto");
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workspaceDir.toFile());
        // Deterministic non-interactive execution: `opencode run` blocks when it inherits
        // an open stdin pipe (verified against CLI v1.19.5); the harness never provides
        // interactive input, so stdin is explicitly detached.
        pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        pb.environment().put("TRYST_TASK_ID", task.getId() != null ? task.getId() : "");
        applyHermeticExecutionEnvironment(pb, "task-" + System.nanoTime());

        long effectiveTimeoutSeconds = (timeout != null && !timeout.isZero() && !timeout.isNegative())
                ? timeout.toSeconds() : 900L;

        long start = System.currentTimeMillis();
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Process process = null;
        try {
            process = pb.start();
            final Process procRef = process;

            Thread outThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(procRef.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                        if (logConsumer != null) {
                            logConsumer.accept(line);
                        }
                    }
                } catch (Exception ignored) {}
            }, "tryst-opencode-stdout");
            Thread errThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(procRef.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            }, "tryst-opencode-stderr");
            outThread.start();
            errThread.start();

            boolean finished = false;
            boolean cancelled = false;
            while (!finished && (System.currentTimeMillis() - start) / 1000 < effectiveTimeoutSeconds) {
                if (cancellationSupplier != null && cancellationSupplier.getAsBoolean()) {
                    cancelled = true;
                    break;
                }
                if (process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    finished = true;
                    break;
                }
            }

            if (cancelled) {
                destroy(process);
                return HarnessExecutionResult.cancelled("OpenCode execution cancelled");
            }
            if (!finished) {
                destroy(process);
                return HarnessExecutionResult.timedOut("OpenCode execution timed out after " + effectiveTimeoutSeconds + " seconds");
            }

            outThread.join(2000);
            errThread.join(2000);

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return HarnessExecutionResult.error("OpenCode exited with code " + exitCode
                        + (stderr.length() > 0 ? ": " + lastLines(stderr.toString(), 5) : ""));
            }

            List<String> modifiedFiles = collectModifiedFiles(workspaceDir);
            // Workspace containment: every reported change must resolve inside the
            // TRYST-controlled workspace. A violation means the harness escaped the sandbox.
            for (String modifiedFile : modifiedFiles) {
                Path resolved = workspaceDir.resolve(modifiedFile).toAbsolutePath().normalize();
                if (!resolved.startsWith(workspaceDir.toAbsolutePath().normalize())) {
                    return HarnessExecutionResult.error(
                            "OpenCode workspace containment violation: change outside workspace: " + modifiedFile);
                }
            }
            String summary = "OpenCode execution completed in workspace " + workspaceDir.getFileName();
            if (!modifiedFiles.isEmpty()) {
                summary += "; modified files: " + modifiedFiles.size();
            }
            return HarnessExecutionResult.ok(summary, modifiedFiles);
        } catch (IOException e) {
            return HarnessExecutionResult.error("Failed to start OpenCode (" + binary + "): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                destroy(process);
            }
            return HarnessExecutionResult.cancelled("OpenCode execution interrupted");
        } catch (Exception e) {
            TrystLog.error(OpenCodeCodingHarnessAdapter.class, "OpenCode execution error: " + e.getMessage(), e);
            if (process != null) {
                destroy(process);
            }
            return HarnessExecutionResult.error("OpenCode execution error: " + e.getMessage());
        }
    }

    /**
     * Appends mandatory containment constraints to the task prompt: the harness must
     * create and modify files exclusively inside the TRYST workspace (the model may
     * otherwise choose absolute paths outside the target).
     */
    private static String applyContainmentConstraints(String prompt, String workspacePath) {
        return prompt
                + "\n\n[EXECUTION CONSTRAINTS] You are working inside the directory: " + workspacePath
                + "\n- Create all new files inside this directory using paths relative to it."
                + "\n- Never create or modify files outside this directory."
                + "\n- Never use absolute paths that escape this directory.";
    }

    /**
     * Applies the hermetic, headless execution environment for the OpenCode process
     * (verified against CLI v1.19.5 on 2026-11-16):
     * <ul>
     * <li>CI=1: suppresses interactive behavior and the startup update check that performs
     *     an external network call which can hang indefinitely on a stalled network path.</li>
     * <li>OPENCODE_DISABLE_TELEMETRY=1: no telemetry network calls.</li>
     * <li>NO_COLOR=1: plain, parseable streamed output.</li>
     * <li>XDG_DATA_HOME / XDG_STATE_HOME / XDG_CACHE_HOME redirected to a product-owned
     *     harness directory: isolates harness state from the user's interactive OpenCode
     *     data directory (which may be corrupted or locked) and keeps the task workspace
     *     free of OpenCode session artifacts.</li>
     * <li>OPENCODE / OPENCODE_PID removed: prevents the CLI from attaching to a running
     *     interactive OpenCode session of the launching environment.</li>
     * </ul>
     * The user's XDG_CONFIG_HOME is inherited so the configured providers, models and
     * agents keep working.
     */
    private static void applyHermeticExecutionEnvironment(ProcessBuilder pb, String runId) {
        java.util.Map<String, String> env = pb.environment();
        env.put("CI", "1");
        env.put("OPENCODE_DISABLE_TELEMETRY", "1");
        env.put("NO_COLOR", "1");
        env.remove("OPENCODE");
        env.remove("OPENCODE_PID");
        // Per-execution state directories: a previous harness run that crashed or hung can
        // leave a poisoned OpenCode data directory behind (stale session state), which
        // blocks subsequent runs at session creation. A fresh directory per execution
        // makes every run deterministic and isolates harness state from the user's
        // interactive OpenCode data (which may be corrupted or locked).
        Path harnessHome = Path.of(System.getProperty("user.home", "."))
                .resolve(".tryst").resolve("opencode-harness").resolve(runId);
        env.put("XDG_DATA_HOME", harnessHome.resolve("data").toString());
        env.put("XDG_STATE_HOME", harnessHome.resolve("state").toString());
        env.put("XDG_CACHE_HOME", harnessHome.resolve("cache").toString());
    }

    /**
     * OpenCode requires the provider/model format (e.g. "ollama/qwen3-coder-30b-32k:latest").
     * A bare model id fails with "Model not found: <id>/" (exit code 1, verified against
     * CLI v1.19.5); bare ids are therefore normalized to the configured local Ollama
     * provider. Ids that already carry a provider prefix pass through unchanged.
     */
    private static String normalizeModel(String model) {
        if (model == null || model.isBlank()) return "";
        String trimmed = model.trim();
        if (!trimmed.contains("/")) {
            return "ollama/" + trimmed;
        }
        return trimmed;
    }

    private List<String> collectModifiedFiles(Path workspaceDir) {
        List<String> modifiedFiles = new ArrayList<>();
        try {
            GitService gitService = new GitService();
            if (gitService.isGitRepository(workspaceDir)) {
                GitService.GitCommandResult status = gitService.getStatus(workspaceDir);
                if (status != null && status.isSuccess()) {
                    for (String line : status.output().split("\n")) {
                        String trimmed = line.trim();
                        if (trimmed.isBlank()) continue;
                        // porcelain-ish: strip status prefix when present
                        int space = trimmed.indexOf(' ');
                        String path = space > 0 ? trimmed.substring(space + 1).trim() : trimmed;
                        if (!path.isBlank()) {
                            modifiedFiles.add(path);
                        }
                    }
                }
            }
        } catch (Exception e) {
            TrystLog.warn(OpenCodeCodingHarnessAdapter.class,
                    "Failed to collect modified files: " + e.getMessage());
        }
        return modifiedFiles;
    }

    private static String readAll(Process process) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private static String lastLines(String text, int count) {
        String[] lines = text.split("\n");
        int from = Math.max(0, lines.length - count);
        return String.join(" | ", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }

    private static void destroy(Process process) {
        if (process == null || !process.isAlive()) return;
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
