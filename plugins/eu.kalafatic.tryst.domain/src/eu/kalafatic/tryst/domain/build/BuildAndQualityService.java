package eu.kalafatic.tryst.domain.build;

import eu.kalafatic.tryst.domain.model.Workspace;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Robust quality assurance and build subsystem interface.
 * Validates AI-generated code changes prior to review publishing.
 */
public interface BuildAndQualityService {

    /**
     * Detects the repository technology and build system.
     */
    BuildSystemType detectTechnology(Path repositoryRoot);

    /**
     * Resolves the technology detector in use.
     */
    BuildTechnologyDetector getTechnologyDetector();

    /**
     * Executes the project build in the assigned workspace sandbox.
     */
    StageExecutionResult executeBuild(
            Workspace workspace,
            String taskId,
            Duration timeout,
            BooleanSupplier cancellationSupplier,
            Consumer<String> outputListener
    );

    /**
     * Executes the relevant automated tests in the assigned workspace sandbox.
     */
    StageExecutionResult executeTests(
            Workspace workspace,
            String taskId,
            Duration timeout,
            BooleanSupplier cancellationSupplier,
            Consumer<String> outputListener
    );

    /**
     * Runs both build and automated tests, producing a structured QualityReport.
     */
    QualityReport runQualityAssurance(
            Workspace workspace,
            String taskId,
            Duration timeout,
            BooleanSupplier cancellationSupplier,
            Consumer<String> outputListener
    );
}
