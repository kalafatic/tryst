package eu.kalafatic.tryst.domain.harness;

import eu.kalafatic.tryst.core.log.TrystLog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-process implementation of the connected-application registry. Persists
 * descriptors and selection through an optional {@link ConnectedApplicationRepository}.
 */
public class DefaultConnectedApplicationRegistry implements ConnectedApplicationRegistry {

    private final Map<String, CodingHarnessAdapter> adapters = new ConcurrentHashMap<>();
    private final Map<String, ConnectedApplicationDescriptor> descriptors = new ConcurrentHashMap<>();
    private final ConnectedApplicationRepository repository;
    private volatile String selectedApplicationId;

    public DefaultConnectedApplicationRegistry(ConnectedApplicationRepository repository) {
        this.repository = repository;
        hydrateFromPersistence();
    }

    private void hydrateFromPersistence() {
        if (repository == null) return;
        try {
            for (ConnectedApplicationDescriptor descriptor : repository.findAllDescriptors()) {
                descriptors.put(descriptor.getId(), descriptor);
            }
            this.selectedApplicationId = repository.findSelectedApplicationId().orElse(null);
        } catch (Exception e) {
            TrystLog.warn(DefaultConnectedApplicationRegistry.class,
                    "Failed to hydrate connected-application registry: " + e.getMessage());
        }
    }

    @Override
    public void registerAdapter(CodingHarnessAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter must not be null");
        adapters.put(adapter.getApplicationId(), adapter);

        ConnectedApplicationDescriptor descriptor = descriptors.computeIfAbsent(
                adapter.getApplicationId(),
                id -> new ConnectedApplicationDescriptor(
                        id,
                        adapter.getApplicationName(),
                        adapter.getApplicationType(),
                        adapter.getCapabilities(),
                        true,
                        ConnectedApplicationDescriptor.ApplicationStatus.UNKNOWN,
                        "",
                        Instant.now(),
                        Instant.now()
                )
        );
        descriptor.setName(adapter.getApplicationName());
        descriptor.setApplicationType(adapter.getApplicationType());
        descriptor.setCapabilities(adapter.getCapabilities());
        if (repository != null) {
            try {
                repository.saveDescriptor(descriptor);
            } catch (Exception e) {
                TrystLog.warn(DefaultConnectedApplicationRegistry.class,
                        "Failed to persist descriptor for " + adapter.getApplicationId() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void unregisterApplication(String applicationId) {
        adapters.remove(applicationId);
        descriptors.remove(applicationId);
        if (applicationId != null && applicationId.equals(selectedApplicationId)) {
            selectedApplicationId = null;
            if (repository != null) {
                try {
                    repository.clearSelectedApplicationId();
                } catch (Exception ignored) {}
            }
        }
        if (repository != null) {
            try {
                repository.deleteDescriptor(applicationId);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public List<ConnectedApplicationDescriptor> listApplications() {
        List<ConnectedApplicationDescriptor> list = new ArrayList<>(descriptors.values());
        list.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return list;
    }

    @Override
    public Optional<ConnectedApplicationDescriptor> findApplication(String applicationId) {
        return Optional.ofNullable(descriptors.get(applicationId));
    }

    @Override
    public Optional<CodingHarnessAdapter> findAdapter(String applicationId) {
        return Optional.ofNullable(adapters.get(applicationId));
    }

    @Override
    public void selectApplication(String applicationId) {
        if (applicationId == null || applicationId.isBlank()) {
            this.selectedApplicationId = null;
        } else {
            this.selectedApplicationId = applicationId;
        }
        if (repository != null) {
            try {
                if (this.selectedApplicationId == null) {
                    repository.clearSelectedApplicationId();
                } else {
                    repository.saveSelectedApplicationId(this.selectedApplicationId);
                }
            } catch (Exception e) {
                TrystLog.warn(DefaultConnectedApplicationRegistry.class,
                        "Failed to persist application selection: " + e.getMessage());
            }
        }
    }

    @Override
    public Optional<String> getSelectedApplicationId() {
        return Optional.ofNullable(selectedApplicationId);
    }

    @Override
    public Optional<CodingHarnessAdapter> findSelectedAdapter() {
        String selected = selectedApplicationId;
        if (selected == null) return Optional.empty();
        CodingHarnessAdapter adapter = adapters.get(selected);
        if (adapter == null) return Optional.empty();
        ConnectedApplicationDescriptor descriptor = descriptors.get(selected);
        if (descriptor != null && !descriptor.isEnabled()) return Optional.empty();
        if (!adapter.isAvailable()) return Optional.empty();
        return Optional.of(adapter);
    }

    @Override
    public CodingHarnessAdapter.ConnectionTestResult testConnection(String applicationId) {
        CodingHarnessAdapter adapter = adapters.get(applicationId);
        if (adapter == null) {
            return CodingHarnessAdapter.ConnectionTestResult.error("Unknown application: " + applicationId);
        }
        CodingHarnessAdapter.ConnectionTestResult result = adapter.testConnection();
        ConnectedApplicationDescriptor descriptor = descriptors.get(applicationId);
        if (descriptor != null) {
            descriptor.setStatus(result.success()
                    ? ConnectedApplicationDescriptor.ApplicationStatus.CONNECTED
                    : ConnectedApplicationDescriptor.ApplicationStatus.DISCONNECTED);
            descriptor.setStatusMessage(result.message());
            if (repository != null) {
                try {
                    repository.saveDescriptor(descriptor);
                } catch (Exception ignored) {}
            }
        }
        return result;
    }

    @Override
    public void updateConfiguration(String applicationId, Map<String, String> configuration) {
        CodingHarnessAdapter adapter = adapters.get(applicationId);
        if (adapter == null) {
            throw new IllegalArgumentException("Unknown application: " + applicationId);
        }
        adapter.updateConnectionConfiguration(configuration);
    }

    @Override
    public void setApplicationEnabled(String applicationId, boolean enabled) {
        ConnectedApplicationDescriptor descriptor = descriptors.get(applicationId);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown application: " + applicationId);
        }
        descriptor.setEnabled(enabled);
        if (repository != null) {
            try {
                repository.saveDescriptor(descriptor);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void clearAll() {
        adapters.clear();
        descriptors.clear();
        selectedApplicationId = null;
        if (repository != null) {
            try {
                for (ConnectedApplicationDescriptor d : repository.findAllDescriptors()) {
                    repository.deleteDescriptor(d.getId());
                }
                repository.clearSelectedApplicationId();
            } catch (Exception ignored) {}
        }
    }
}
