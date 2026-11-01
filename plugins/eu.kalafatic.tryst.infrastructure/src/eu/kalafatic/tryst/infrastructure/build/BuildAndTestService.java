package eu.kalafatic.tryst.infrastructure.build;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.ExecutionResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Build and automated test execution engine invoking Maven in isolated processes.
 */
public class BuildAndTestService {

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

        long startTime = System.currentTimeMillis();
        List<String> command = new ArrayList<>();
        command.add("mvn");
        command.add("-B"); // Batch mode
        command.addAll(goals);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDirectory.toFile());

        StringBuilder fullOutput = new StringBuilder();
        StringBuilder fullError = new StringBuilder();

        try {
            Process process = pb.start();

            Thread outThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        fullOutput.append(line).append("\n");
                        if (outputListener != null) {
                            outputListener.accept(line);
                        }
                    }
                } catch (Exception ignored) {}
            });

            Thread errThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        fullError.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });

            outThread.start();
            errThread.start();

            boolean finished = process.waitFor(timeoutSeconds > 0 ? timeoutSeconds : 300, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                fullError.append("\n[Maven build timed out]\n");
            }

            outThread.join();
            errThread.join();

            long duration = System.currentTimeMillis() - startTime;
            int exitCode = finished ? process.exitValue() : -1;
            return new ExecutionResult("none", exitCode, fullOutput.toString(), fullError.toString(), duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            TrystLog.error(BuildAndTestService.class, "Maven execution failed", e);
            return new ExecutionResult("none", 1, fullOutput.toString(), e.getMessage(), duration);
        }
    }

    public ExecutionResult runTests(Path workingDirectory, int timeoutSeconds, Consumer<String> outputListener) {
        return executeMaven(workingDirectory, List.of("test"), timeoutSeconds, outputListener);
    }

    public ExecutionResult runBuild(Path workingDirectory, int timeoutSeconds, Consumer<String> outputListener) {
        return executeMaven(workingDirectory, List.of("compile"), timeoutSeconds, outputListener);
    }
}
