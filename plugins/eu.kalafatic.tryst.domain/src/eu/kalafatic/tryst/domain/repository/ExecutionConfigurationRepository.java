package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.ai.ExecutionConfiguration;

import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for scope-based execution configurations
 * (tables {@code execution_configurations} and {@code task_execution_snapshots}).
 */
public interface ExecutionConfigurationRepository {

    /**
     * A persisted scoped configuration (scope + scope key + configuration).
     */
    record ScopedConfiguration(String scope, String scopeKey,
            ExecutionConfiguration configuration) {}

    void save(String scope, String scopeKey, ExecutionConfiguration configuration);

    Optional<ExecutionConfiguration> find(String scope, String scopeKey);

    void delete(String scope, String scopeKey);

    /**
     * Lists every persisted configuration of the given scope ordered by scope key.
     */
    List<ScopedConfiguration> findByScope(String scope);

    void saveSnapshot(String taskId, ExecutionConfiguration resolved);

    Optional<ExecutionConfiguration> findSnapshot(String taskId);
}
