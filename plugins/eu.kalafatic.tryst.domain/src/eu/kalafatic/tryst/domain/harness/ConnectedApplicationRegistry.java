package eu.kalafatic.tryst.domain.harness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Product-local registry of connected coding applications (Phase 3, A-14).
 *
 * <p>The registry manages application descriptors, adapter registration, connection
 * configuration, connection testing, connection status and application selection. It is a
 * TRYST-local registry — not a cross-product shared service. Adapters are registered by the
 * application lifecycle; descriptors and selection are persisted through
 * {@link ConnectedApplicationRepository} when one is provided.</p>
 */
public interface ConnectedApplicationRegistry {

    /**
     * Registers (or replaces) the adapter for an application. The adapter's descriptor is
     * persisted when a repository is available.
     */
    void registerAdapter(CodingHarnessAdapter adapter);

    /**
     * Unregisters the adapter and removes the persisted descriptor.
     */
    void unregisterApplication(String applicationId);

    /**
     * Lists all registered connected applications with their current runtime status.
     */
    List<ConnectedApplicationDescriptor> listApplications();

    /**
     * Finds an application descriptor by id.
     */
    Optional<ConnectedApplicationDescriptor> findApplication(String applicationId);

    /**
     * Finds the adapter by application id.
     */
    Optional<CodingHarnessAdapter> findAdapter(String applicationId);

    /**
     * Selects the application used for delegated coding execution. A blank id clears the
     * selection (built-in local executor only).
     */
    void selectApplication(String applicationId);

    /**
     * Returns the currently selected application id, or empty when no connected application
     * is selected.
     */
    Optional<String> getSelectedApplicationId();

    /**
     * Returns the selected adapter when it is enabled and available.
     */
    Optional<CodingHarnessAdapter> findSelectedAdapter();

    /**
     * Runs the live connection test for an application and stores the resulting status.
     */
    CodingHarnessAdapter.ConnectionTestResult testConnection(String applicationId);

    /**
     * Updates an application's connection configuration.
     */
    void updateConfiguration(String applicationId, Map<String, String> configuration);

    /**
     * Enables or disables an application.
     */
    void setApplicationEnabled(String applicationId, boolean enabled);

    /**
     * Removes all applications and clears the selection (test/lifecycle support).
     */
    void clearAll();
}
