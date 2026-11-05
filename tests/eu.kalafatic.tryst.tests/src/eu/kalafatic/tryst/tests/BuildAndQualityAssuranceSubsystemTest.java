package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.build.*;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.service.ProcessExecutionManager;
import eu.kalafatic.tryst.infrastructure.build.BuildAndTestService;
import eu.kalafatic.tryst.infrastructure.build.DefaultBuildTechnologyDetector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration and automated test suite for the Build and Quality Assurance subsystem.
 * Verifies technology detection, command resolution, process isolation, security constraints,
 * timeout/cancellation handling, failure detection, and distinction between NOT_EXECUTED,
 * SUCCESSFUL, FAILED, CANCELLED, and TIMED_OUT.
 */
public class BuildAndQualityAssuranceSubsystemTest {

    @TempDir
    Path tempDir;

    private DefaultBuildTechnologyDetector detector;
    private PermissionManager permissionManager;
    private ProcessExecutionManager processExecutionManager;
    private BuildAndTestService buildService;

    @BeforeEach
    void setUp() {
        detector = new DefaultBuildTechnologyDetector();
        permissionManager = new PermissionManager();
        processExecutionManager = new ProcessExecutionManager();
        buildService = new BuildAndTestService(detector, permissionManager, processExecutionManager);
    }

    @AfterEach
    void tearDown() {
        if (processExecutionManager != null) {
            processExecutionManager.shutdown();
        }
    }

    @Test
    void testDetectMavenProjectAndCommands() throws IOException {
        Path mavenRoot = tempDir.resolve("maven-proj");
        Files.createDirectories(mavenRoot);
        Files.writeString(mavenRoot.resolve("pom.xml"), "<project></project>");

        BuildSystemType system = detector.detectBuildSystem(mavenRoot);
        assertEquals(BuildSystemType.MAVEN, system);

        List<String> buildCmd = detector.resolveBuildCommand(mavenRoot);
        assertFalse(buildCmd.isEmpty());
        assertTrue(buildCmd.get(0).contains("mvn"));
        assertTrue(buildCmd.contains("compile"));

        List<String> testCmd = detector.resolveTestCommand(mavenRoot);
        assertFalse(testCmd.isEmpty());
        assertTrue(testCmd.get(0).contains("mvn"));
        assertTrue(testCmd.contains("test"));
    }

    @Test
    void testDetectGradleProjectWithWrapper() throws IOException {
        Path gradleRoot = tempDir.resolve("gradle-proj");
        Files.createDirectories(gradleRoot);
        Files.writeString(gradleRoot.resolve("build.gradle"), "// gradle");
        Path wrapper = gradleRoot.resolve("gradlew");
        Files.writeString(wrapper, "#!/bin/sh\nexit 0\n");

        BuildSystemType system = detector.detectBuildSystem(gradleRoot);
        assertEquals(BuildSystemType.GRADLE, system);

        List<String> buildCmd = detector.resolveBuildCommand(gradleRoot);
        assertFalse(buildCmd.isEmpty());
        assertTrue(buildCmd.get(0).contains("gradlew"));
        assertTrue(buildCmd.contains("classes"));

        List<String> testCmd = detector.resolveTestCommand(gradleRoot);
        assertFalse(testCmd.isEmpty());
        assertTrue(testCmd.get(0).contains("gradlew"));
        assertTrue(testCmd.contains("test"));
    }

    @Test
    void testDetectNpmProjectAndCommands() throws IOException {
        Path npmRoot = tempDir.resolve("npm-proj");
        Files.createDirectories(npmRoot);
        Files.writeString(npmRoot.resolve("package.json"), "{\"name\":\"test-app\"}");

        BuildSystemType system = detector.detectBuildSystem(npmRoot);
        assertEquals(BuildSystemType.NPM, system);

        List<String> buildCmd = detector.resolveBuildCommand(npmRoot);
        assertFalse(buildCmd.isEmpty());
        assertTrue(buildCmd.get(0).contains("npm"));
        assertTrue(buildCmd.contains("build"));

        List<String> testCmd = detector.resolveTestCommand(npmRoot);
        assertFalse(testCmd.isEmpty());
        assertTrue(testCmd.get(0).contains("npm"));
        assertTrue(testCmd.contains("test"));
    }

    @Test
    void testDetectCargoProjectAndCommands() throws IOException {
        Path cargoRoot = tempDir.resolve("cargo-proj");
        Files.createDirectories(cargoRoot);
        Files.writeString(cargoRoot.resolve("Cargo.toml"), "[package]\nname = \"test\"");

        BuildSystemType system = detector.detectBuildSystem(cargoRoot);
        assertEquals(BuildSystemType.CARGO, system);

        List<String> buildCmd = detector.resolveBuildCommand(cargoRoot);
        assertEquals(List.of("cargo", "check"), buildCmd);

        List<String> testCmd = detector.resolveTestCommand(cargoRoot);
        assertEquals(List.of("cargo", "test"), testCmd);
    }

    @Test
    void testDetectPythonProjectAndCommands() throws IOException {
        Path pyRoot = tempDir.resolve("python-proj");
        Files.createDirectories(pyRoot);
        Files.writeString(pyRoot.resolve("pyproject.toml"), "[build-system]");

        BuildSystemType system = detector.detectBuildSystem(pyRoot);
        assertEquals(BuildSystemType.PYTHON, system);

        List<String> buildCmd = detector.resolveBuildCommand(pyRoot);
        assertFalse(buildCmd.isEmpty());
        assertTrue(buildCmd.contains("compileall"));

        List<String> testCmd = detector.resolveTestCommand(pyRoot);
        assertFalse(testCmd.isEmpty());
        assertTrue(testCmd.contains("unittest"));
    }

    @Test
    void testCustomProjectConfigurationOverrides() throws IOException {
        Path customRoot = tempDir.resolve("custom-proj");
        Files.createDirectories(customRoot);

        // Even if pom.xml exists, .tryst-build.json overrides
        Files.writeString(customRoot.resolve("pom.xml"), "<project></project>");
        String configJson = """
                {
                    "buildSystem": "CUSTOM",
                    "buildCommand": ["sh", "-c", "echo custom_build"],
                    "testCommand": ["sh", "-c", "echo custom_test"],
                    "timeoutSeconds": 42
                }
                """;
        Files.writeString(customRoot.resolve(DefaultBuildTechnologyDetector.CONFIG_FILE_NAME), configJson);

        BuildSystemType system = detector.detectBuildSystem(customRoot);
        assertEquals(BuildSystemType.CUSTOM, system);

        List<String> buildCmd = detector.resolveBuildCommand(customRoot);
        assertEquals(List.of("sh", "-c", "echo custom_build"), buildCmd);

        List<String> testCmd = detector.resolveTestCommand(customRoot);
        assertEquals(List.of("sh", "-c", "echo custom_test"), testCmd);

        BuildConfiguration config = detector.loadConfiguration(customRoot);
        assertEquals(42, config.timeoutSeconds());
    }

    @Test
    void testSecurityConfinementOutsideWorkspace() {
        Workspace ws = new Workspace("ws-confined", tempDir.resolve("non-existent-repo"));
        StageExecutionResult res = buildService.executeBuild(ws, "task-1", Duration.ofSeconds(10), () -> false, null);
        assertEquals(BuildExecutionStatus.NOT_EXECUTED, res.status());
    }

    @Test
    void testSecurityCommandWhitelistRejection() throws IOException {
        Path projRoot = tempDir.resolve("malicious-proj");
        Files.createDirectories(projRoot);
        String configJson = """
                {
                    "buildCommand": ["rm", "-rf", "/"]
                }
                """;
        Files.writeString(projRoot.resolve(DefaultBuildTechnologyDetector.CONFIG_FILE_NAME), configJson);

        Workspace ws = new Workspace("ws-sec", projRoot);
        StageExecutionResult res = buildService.executeBuild(ws, "task-sec", Duration.ofSeconds(10), () -> false, null);
        assertEquals(BuildExecutionStatus.FAILED, res.status());
        assertTrue(res.stderr().contains("SecurityException") || res.failureReason().contains("SecurityException"));
    }

    @Test
    void testBuildAndTestExecutionWithProcessTracking() throws IOException {
        Path projRoot = tempDir.resolve("exec-proj");
        Files.createDirectories(projRoot);
        String configJson = """
                {
                    "buildCommand": ["sh", "-c", "echo Building artifacts; exit 0"],
                    "testCommand": ["sh", "-c", "echo Running unit tests; exit 0"]
                }
                """;
        Files.writeString(projRoot.resolve(DefaultBuildTechnologyDetector.CONFIG_FILE_NAME), configJson);

        Workspace ws = new Workspace("ws-exec", projRoot);
        StringBuilder outputLogs = new StringBuilder();

        QualityReport report = buildService.runQualityAssurance(
                ws, "task-tracking", Duration.ofSeconds(30), () -> false, line -> outputLogs.append(line).append("\n")
        );

        assertEquals(BuildExecutionStatus.SUCCESSFUL, report.buildStatus());
        assertEquals(BuildExecutionStatus.SUCCESSFUL, report.testStatus());
        assertTrue(report.testsExecuted());
        assertTrue(report.isOverallSuccess());
        assertTrue(outputLogs.toString().contains("Building artifacts"));
        assertTrue(outputLogs.toString().contains("Running unit tests"));

        assertFalse(processExecutionManager.getManagedProcesses().isEmpty());
    }

    @Test
    void testCompilationFailureSkipsAutomatedTests() throws IOException {
        Path projRoot = tempDir.resolve("fail-compile-proj");
        Files.createDirectories(projRoot);
        String configJson = """
                {
                    "buildCommand": ["sh", "-c", "echo Syntax error at line 42; exit 1"],
                    "testCommand": ["sh", "-c", "echo Tests ran; exit 0"]
                }
                """;
        Files.writeString(projRoot.resolve(DefaultBuildTechnologyDetector.CONFIG_FILE_NAME), configJson);

        Workspace ws = new Workspace("ws-fail-build", projRoot);
        QualityReport report = buildService.runQualityAssurance(
                ws, "task-fail-build", Duration.ofSeconds(30), () -> false, null
        );

        assertEquals(BuildExecutionStatus.FAILED, report.buildStatus());
        assertEquals(BuildExecutionStatus.NOT_EXECUTED, report.testStatus());
        assertFalse(report.testsExecuted());
        assertFalse(report.isOverallSuccess());
        assertTrue(report.summary().contains("skipped"));
        assertTrue(report.failureDetails().contains("Syntax error"));
    }

    @Test
    void testBuildSuccessWithoutTestsMarksTestsNotExecuted() throws IOException {
        Path projRoot = tempDir.resolve("no-tests-proj");
        Files.createDirectories(projRoot);
        String configJson = """
                {
                    "buildCommand": ["sh", "-c", "echo Compiling pure library; exit 0"],
                    "testCommand": []
                }
                """;
        Files.writeString(projRoot.resolve(DefaultBuildTechnologyDetector.CONFIG_FILE_NAME), configJson);

        Workspace ws = new Workspace("ws-no-tests", projRoot);
        QualityReport report = buildService.runQualityAssurance(
                ws, "task-no-tests", Duration.ofSeconds(30), () -> false, null
        );

        assertEquals(BuildExecutionStatus.SUCCESSFUL, report.buildStatus());
        assertEquals(BuildExecutionStatus.NOT_EXECUTED, report.testStatus());
        assertFalse(report.testsExecuted());
        assertTrue(report.isOverallSuccess());
        assertTrue(report.summary().contains("No test suite executed"));
    }

    @Test
    void testTestFailureDistinguishedFromBuildFailure() throws IOException {
        Path projRoot = tempDir.resolve("test-fail-proj");
        Files.createDirectories(projRoot);
        String configJson = """
                {
                    "buildCommand": ["sh", "-c", "echo Compilation successful; exit 0"],
                    "testCommand": ["sh", "-c", "echo AssertionFailedError in TestService; exit 2"]
                }
                """;
        Files.writeString(projRoot.resolve(DefaultBuildTechnologyDetector.CONFIG_FILE_NAME), configJson);

        Workspace ws = new Workspace("ws-test-fail", projRoot);
        QualityReport report = buildService.runQualityAssurance(
                ws, "task-test-fail", Duration.ofSeconds(30), () -> false, null
        );

        assertEquals(BuildExecutionStatus.SUCCESSFUL, report.buildStatus());
        assertEquals(BuildExecutionStatus.FAILED, report.testStatus());
        assertTrue(report.testsExecuted());
        assertFalse(report.isOverallSuccess());
        assertTrue(report.failureDetails().contains("AssertionFailedError"));
    }

    @Test
    void testExecutionTimeoutHandling() throws IOException {
        Path projRoot = tempDir.resolve("timeout-proj");
        Files.createDirectories(projRoot);
        String configJson = """
                {
                    "buildCommand": ["sh", "-c", "sleep 10; exit 0"]
                }
                """;
        Files.writeString(projRoot.resolve(DefaultBuildTechnologyDetector.CONFIG_FILE_NAME), configJson);

        Workspace ws = new Workspace("ws-timeout", projRoot);
        StageExecutionResult res = buildService.executeBuild(
                ws, "task-timeout", Duration.ofSeconds(1), () -> false, null
        );

        assertEquals(BuildExecutionStatus.TIMED_OUT, res.status());
        assertTrue(res.failureReason().contains("timed out"));
    }

    @Test
    void testExecutionCancellationHandling() throws IOException {
        Path projRoot = tempDir.resolve("cancel-proj");
        Files.createDirectories(projRoot);
        String configJson = """
                {
                    "buildCommand": ["sh", "-c", "sleep 10; exit 0"]
                }
                """;
        Files.writeString(projRoot.resolve(DefaultBuildTechnologyDetector.CONFIG_FILE_NAME), configJson);

        Workspace ws = new Workspace("ws-cancel", projRoot);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        new Thread(() -> {
            try {
                Thread.sleep(100);
                cancelled.set(true);
            } catch (InterruptedException ignored) {}
        }).start();

        StageExecutionResult res = buildService.executeBuild(
                ws, "task-cancel", Duration.ofSeconds(10), cancelled::get, null
        );

        assertEquals(BuildExecutionStatus.CANCELLED, res.status());
        assertTrue(res.failureReason().contains("cancelled"));
    }
}
