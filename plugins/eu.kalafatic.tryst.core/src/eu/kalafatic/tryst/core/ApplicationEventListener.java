package eu.kalafatic.tryst.core;

/**
 * Listener contract for application-level state and lifecycle events.
 */
@FunctionalInterface
public interface ApplicationEventListener<T> {

    void onEvent(ApplicationEvent<T> event);
}
