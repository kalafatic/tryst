package eu.kalafatic.tryst.core.lifecycle;

/**
 * Deterministic lifecycle stages of the TRYST application.
 */
public enum ApplicationLifecycleStage {
    APPLICATION_START,
    PLATFORM_INITIALIZATION,
    CONFIGURATION_INITIALIZATION,
    CORE_SERVICE_INITIALIZATION,
    APPLICATION_READY,
    WORKBENCH_RUNNING,
    APPLICATION_SHUTDOWN,
    SERVICE_DISPOSAL,
    APPLICATION_TERMINATED
}
