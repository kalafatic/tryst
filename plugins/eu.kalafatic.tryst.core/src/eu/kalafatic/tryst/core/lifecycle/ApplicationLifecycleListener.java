package eu.kalafatic.tryst.core.lifecycle;

/**
 * Listener interface for application lifecycle transitions.
 */
@FunctionalInterface
public interface ApplicationLifecycleListener {

    void onLifecycleEvent(ApplicationLifecycleEvent event);
}
