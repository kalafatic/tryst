package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.event.ApplicationEvents;
import eu.kalafatic.tryst.core.event.EventBus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EventBusTest {

    @Test
    void testEventSubscriptionAndDelivery() {
        EventBus bus = new EventBus();
        AtomicReference<ApplicationEvents.TaskStateChangedEvent> received = new AtomicReference<>();

        bus.subscribe(ApplicationEvents.TaskStateChangedEvent.class, received::set);

        ApplicationEvents.TaskStateChangedEvent event = new ApplicationEvents.TaskStateChangedEvent(
                "task-123", "IDLE", "RUNNING", "Started"
        );
        bus.publish(event);

        assertNotNull(received.get());
        assertEquals("task-123", received.get().taskId());
        assertEquals("IDLE", received.get().previousState());
        assertEquals("RUNNING", received.get().newState());
    }

    @Test
    void testExceptionIsolationInListeners() {
        EventBus bus = new EventBus();
        AtomicInteger counter = new AtomicInteger(0);

        bus.subscribe(ApplicationEvents.SessionEvent.class, evt -> {
            throw new RuntimeException("Simulated listener failure");
        });
        bus.subscribe(ApplicationEvents.SessionEvent.class, evt -> {
            counter.incrementAndGet();
        });

        assertDoesNotThrow(() -> bus.publish(new ApplicationEvents.SessionEvent("s1", "OPEN", "opened")));
        assertEquals(1, counter.get());
    }
}
