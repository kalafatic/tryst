package eu.kalafatic.tryst.core.port;

import eu.kalafatic.tryst.core.log.TrystLog;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Atomic and persistent storage for effective PortAllocationGroup allocations.
 * Ensures survived restarts without silent defaults and protects against concurrent writes.
 */
public class PortPersistence {

    public static final String PORT_CONFIG_FILE_NAME = "port-allocations.properties";

    private final Path storageDirectory;
    private final Path storageFile;

    public PortPersistence(Path storageDirectory) {
        this.storageDirectory = Objects.requireNonNull(storageDirectory, "storageDirectory must not be null");
        this.storageFile = storageDirectory.resolve(PORT_CONFIG_FILE_NAME);
    }

    public synchronized Optional<PortAllocationGroup> loadAllocations() {
        if (!Files.exists(storageFile)) {
            return Optional.empty();
        }
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(storageFile.toFile())) {
            props.load(fis);
            int baseOffset = Integer.parseInt(props.getProperty("allocation.baseOffset", String.valueOf(PortAllocationGroup.TRYST_BASE_OFFSET)));
            int groupOffset = Integer.parseInt(props.getProperty("allocation.groupOffset", "0"));
            String host = props.getProperty("allocation.bindingAddress", "127.0.0.1");
            int version = Integer.parseInt(props.getProperty("allocation.version", "1"));
            String tsStr = props.getProperty("allocation.timestamp");
            Instant timestamp = (tsStr != null && !tsStr.isBlank()) ? Instant.parse(tsStr) : Instant.now();

            Map<String, PortConfiguration> map = new LinkedHashMap<>();
            for (String key : props.stringPropertyNames()) {
                if (key.startsWith("service.") && key.endsWith(".assignedPort")) {
                    String serviceId = key.substring(8, key.length() - 13);
                    int defaultPort = Integer.parseInt(props.getProperty("service." + serviceId + ".defaultPort", "0"));
                    int assignedPort = Integer.parseInt(props.getProperty("service." + serviceId + ".assignedPort"));
                    int offset = Integer.parseInt(props.getProperty("service." + serviceId + ".offset", String.valueOf(groupOffset)));
                    PortProtocol protocol = PortProtocol.valueOf(props.getProperty("service." + serviceId + ".protocol", "HTTP"));
                    String bindHost = props.getProperty("service." + serviceId + ".bindingAddress", host);
                    boolean enabled = Boolean.parseBoolean(props.getProperty("service." + serviceId + ".enabled", "true"));
                    int sVer = Integer.parseInt(props.getProperty("service." + serviceId + ".version", String.valueOf(version)));
                    String sTs = props.getProperty("service." + serviceId + ".timestamp");
                    Instant sTime = (sTs != null) ? Instant.parse(sTs) : timestamp;

                    map.put(serviceId, new PortConfiguration(
                            serviceId, defaultPort, assignedPort, offset, protocol, bindHost, enabled, sVer, sTime
                    ));
                }
            }

            if (map.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(new PortAllocationGroup(baseOffset, groupOffset, host, version, timestamp, map));
        } catch (Exception e) {
            TrystLog.warn(PortPersistence.class, "Failed to load port allocations from " + storageFile + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    public synchronized void saveAllocations(PortAllocationGroup group) {
        Objects.requireNonNull(group, "group must not be null");
        try {
            Files.createDirectories(storageDirectory);
            Properties props = new Properties();
            props.setProperty("allocation.baseOffset", String.valueOf(group.baseOffset()));
            props.setProperty("allocation.groupOffset", String.valueOf(group.groupOffset()));
            props.setProperty("allocation.bindingAddress", group.bindingAddress());
            props.setProperty("allocation.version", String.valueOf(group.configurationVersion()));
            props.setProperty("allocation.timestamp", group.allocationTimestamp().toString());

            for (PortConfiguration cfg : group.configurations().values()) {
                String prefix = "service." + cfg.serviceId() + ".";
                props.setProperty(prefix + "defaultPort", String.valueOf(cfg.defaultPort()));
                props.setProperty(prefix + "assignedPort", String.valueOf(cfg.assignedPort()));
                props.setProperty(prefix + "offset", String.valueOf(cfg.offset()));
                props.setProperty(prefix + "protocol", cfg.protocol().name());
                props.setProperty(prefix + "bindingAddress", cfg.bindingAddress());
                props.setProperty(prefix + "enabled", String.valueOf(cfg.enabled()));
                props.setProperty(prefix + "version", String.valueOf(cfg.configurationVersion()));
                props.setProperty(prefix + "timestamp", cfg.allocationTimestamp().toString());
            }

            // Atomic file write using temporary file and atomic replace
            Path tempFile = Files.createTempFile(storageDirectory, "port-allocations", ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                props.store(fos, "TRYST Authoritative Persistent Port Allocations");
            }
            try {
                Files.move(tempFile, storageFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, storageFile, StandardCopyOption.REPLACE_EXISTING);
            }
            TrystLog.info(PortPersistence.class, "Persisted port allocations to " + storageFile + " (group offset: " + group.groupOffset() + ")");
        } catch (IOException e) {
            TrystLog.error(PortPersistence.class, "Failed to persist port allocations to " + storageFile, e);
        }
    }
}
