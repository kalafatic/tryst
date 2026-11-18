package eu.kalafatic.tryst.domain.ai;

import java.util.List;
import java.util.Optional;

/**
 * Scope-based execution-configuration resolution for TRYST: GLOBAL defaults → PROJECT
 * overrides → TASK overrides → immutable per-task snapshot. The resolved configuration
 * is validated and snapshotted before any harness executes a task, so later
 * configuration changes never modify a running or recorded execution.
 *
 * <p>In addition, named execution-environment SESSIONs allow saving and restoring a
 * complete harness/provider/model/agent selection.</p>
 */
public interface ExecutionConfigurationService {

    /**
     * A named execution-environment session.
     */
    record EnvironmentSession(String name, ExecutionConfiguration configuration) {}

    void saveGlobal(ExecutionConfiguration configuration);

    void saveProjectOverride(String projectId, ExecutionConfiguration configuration);

    void saveTaskOverride(String taskId, ExecutionConfiguration configuration);

    void removeTaskOverride(String taskId);

    Optional<ExecutionConfiguration> getGlobal();

    Optional<ExecutionConfiguration> getProjectOverride(String projectId);

    Optional<ExecutionConfiguration> getTaskOverride(String taskId);

    /** GLOBAL → PROJECT → TASK resolution for the given project and task. */
    ExecutionConfiguration resolve(String projectId, String taskId);

    /** Persists the immutable resolved configuration snapshot for an accepted task. */
    void saveSnapshot(String taskId, ExecutionConfiguration resolved);

    Optional<ExecutionConfiguration> getSnapshot(String taskId);

    /**
     * Saves (or overwrites) a named execution-environment session.
     */
    void saveSession(String name, ExecutionConfiguration configuration);

    /**
     * Loads a named execution-environment session.
     */
    Optional<ExecutionConfiguration> loadSession(String name);

    /**
     * Removes a named execution-environment session.
     */
    void removeSession(String name);

    /**
     * Lists every saved execution-environment session ordered by name.
     */
    List<EnvironmentSession> listSessions();
}
