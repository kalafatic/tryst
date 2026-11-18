package eu.kalafatic.tryst.domain.ai;

import java.util.Optional;

/**
 * Default scope-based execution-configuration resolution:
 * GLOBAL → PROJECT → TASK → EXECUTION snapshot.
 */
public class DefaultExecutionConfigurationService implements ExecutionConfigurationService {

    public static final String SCOPE_GLOBAL = "GLOBAL";
    public static final String SCOPE_PROJECT = "PROJECT";
    public static final String SCOPE_TASK = "TASK";
    public static final String SCOPE_SESSION = "SESSION";
    public static final String KEY_GLOBAL = "default";

    private final eu.kalafatic.tryst.domain.repository.ExecutionConfigurationRepository repository;

    public DefaultExecutionConfigurationService(
            eu.kalafatic.tryst.domain.repository.ExecutionConfigurationRepository repository) {
        this.repository = repository;
    }

    @Override
    public void saveGlobal(ExecutionConfiguration configuration) {
        repository.save(SCOPE_GLOBAL, KEY_GLOBAL,
                withSource(configuration, ExecutionConfiguration.SOURCE_GLOBAL));
    }

    @Override
    public void saveProjectOverride(String projectId, ExecutionConfiguration configuration) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId is required");
        }
        repository.save(SCOPE_PROJECT, projectId,
                withSource(configuration, ExecutionConfiguration.SOURCE_PROJECT));
    }

    @Override
    public void saveTaskOverride(String taskId, ExecutionConfiguration configuration) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        repository.save(SCOPE_TASK, taskId,
                withSource(configuration, ExecutionConfiguration.SOURCE_TASK));
    }

    @Override
    public void removeTaskOverride(String taskId) {
        repository.delete(SCOPE_TASK, taskId);
    }

    private static ExecutionConfiguration withSource(ExecutionConfiguration configuration,
            String source) {
        if (configuration == null) {
            return ExecutionConfiguration.empty(source);
        }
        return new ExecutionConfiguration(configuration.harnessId(),
                configuration.providerId(), configuration.modelId(),
                configuration.agentProfileId(), configuration.executionParameters(),
                configuration.toolPermissions(), configuration.resourceLimits(),
                configuration.harnessSettings(), source);
    }

    @Override
    public Optional<ExecutionConfiguration> getGlobal() {
        return repository.find(SCOPE_GLOBAL, KEY_GLOBAL);
    }

    @Override
    public Optional<ExecutionConfiguration> getProjectOverride(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        return repository.find(SCOPE_PROJECT, projectId);
    }

    @Override
    public Optional<ExecutionConfiguration> getTaskOverride(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return repository.find(SCOPE_TASK, taskId);
    }

    @Override
    public ExecutionConfiguration resolve(String projectId, String taskId) {
        ExecutionConfiguration resolved = getGlobal()
                .orElseGet(() -> ExecutionConfiguration.empty(ExecutionConfiguration.SOURCE_GLOBAL));
        Optional<ExecutionConfiguration> project = getProjectOverride(projectId);
        if (project.isPresent()) {
            resolved = resolved.overrideWith(project.get());
        }
        Optional<ExecutionConfiguration> task = getTaskOverride(taskId);
        if (task.isPresent()) {
            resolved = resolved.overrideWith(task.get());
        }
        return resolved;
    }

    @Override
    public void saveSnapshot(String taskId, ExecutionConfiguration resolved) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        repository.saveSnapshot(taskId, resolved == null
                ? ExecutionConfiguration.empty(ExecutionConfiguration.SOURCE_EXECUTION)
                : new ExecutionConfiguration(resolved.harnessId(), resolved.providerId(),
                        resolved.modelId(), resolved.agentProfileId(),
                        resolved.executionParameters(), resolved.toolPermissions(),
                        resolved.resourceLimits(), resolved.harnessSettings(),
                        ExecutionConfiguration.SOURCE_EXECUTION));
    }

    @Override
    public Optional<ExecutionConfiguration> getSnapshot(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return repository.findSnapshot(taskId);
    }

    @Override
    public void saveSession(String name, ExecutionConfiguration configuration) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("session name is required");
        }
        repository.save(SCOPE_SESSION, name.trim(),
                withSource(configuration, ExecutionConfiguration.SOURCE_SESSION));
    }

    @Override
    public Optional<ExecutionConfiguration> loadSession(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return repository.find(SCOPE_SESSION, name.trim());
    }

    @Override
    public void removeSession(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        repository.delete(SCOPE_SESSION, name.trim());
    }

    @Override
    public java.util.List<EnvironmentSession> listSessions() {
        return repository.findByScope(SCOPE_SESSION).stream()
                .map(s -> new EnvironmentSession(s.scopeKey(), s.configuration()))
                .toList();
    }
}
