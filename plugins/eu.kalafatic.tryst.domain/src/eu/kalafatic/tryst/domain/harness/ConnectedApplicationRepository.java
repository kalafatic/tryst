package eu.kalafatic.tryst.domain.harness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for connected-application descriptors and the selected-application id.
 * Implemented in the infrastructure bundle (SQLite). May be absent; the registry then operates
 * in-memory only.
 */
public interface ConnectedApplicationRepository {

    void saveDescriptor(ConnectedApplicationDescriptor descriptor);

    void deleteDescriptor(String applicationId);

    Optional<ConnectedApplicationDescriptor> findDescriptor(String applicationId);

    List<ConnectedApplicationDescriptor> findAllDescriptors();

    Optional<String> findSelectedApplicationId();

    void saveSelectedApplicationId(String applicationId);

    void clearSelectedApplicationId();

    /**
     * Loads descriptors whose updated_at is after the given instant (for restart hydration).
     */
    default List<ConnectedApplicationDescriptor> findAllDescriptorsSince(Instant since) {
        return findAllDescriptors();
    }
}
