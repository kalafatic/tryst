package eu.kalafatic.tryst.core.event;

import eu.kalafatic.tryst.core.log.TrystLog;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Lightweight, thread-safe, synchronous/asynchronous typed event bus.
 * Provides listener isolation, typed dispatch, and clean disposal.
 */
public class EventBus {

    private final Map<Class<?>, List<Consumer<?>>> listeners = new ConcurrentHashMap<>();

    public <E extends TrystTypedEvent> void subscribe(Class<E> eventType, Consumer<E> listener) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public <E extends TrystTypedEvent> void unsubscribe(Class<E> eventType, Consumer<E> listener) {
        if (eventType == null || listener == null) return;
        List<Consumer<?>> list = listeners.get(eventType);
        if (list != null) {
            list.remove(listener);
        }
    }

    @SuppressWarnings("unchecked")
    public <E extends TrystTypedEvent> void publish(E event) {
        if (event == null) return;
        Class<?> clazz = event.getClass();
        for (Map.Entry<Class<?>, List<Consumer<?>>> entry : listeners.entrySet()) {
            if (entry.getKey().isAssignableFrom(clazz)) {
                for (Consumer<?> rawListener : entry.getValue()) {
                    try {
                        ((Consumer<E>) rawListener).accept(event);
                    } catch (Throwable t) {
                        TrystLog.error(EventBus.class, "Exception during event dispatch: " + clazz.getSimpleName(), t);
                    }
                }
            }
        }
    }

    public void clear() {
        listeners.clear();
    }
}
