package eu.kalafatic.tryst.ui.context;

import eu.kalafatic.tryst.core.log.TrystLog;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe coordinator for the shared {@link WorkbenchContext}.
 */
public class WorkbenchContextManager {

    private static final WorkbenchContextManager INSTANCE = new WorkbenchContextManager();

    private final AtomicReference<WorkbenchContext> currentContext = new AtomicReference<>(WorkbenchContext.EMPTY);
    private final AtomicLong sequenceNumber = new AtomicLong(0);
    private final List<IWorkbenchContextListener> listeners = new CopyOnWriteArrayList<>();

    public WorkbenchContextManager() {
    }

    public static WorkbenchContextManager getInstance() {
        return INSTANCE;
    }

    public WorkbenchContext getContext() {
        return currentContext.get();
    }

    public long setContext(WorkbenchContext newContext) {
        Objects.requireNonNull(newContext, "newContext must not be null");
        long seq = sequenceNumber.incrementAndGet();
        currentContext.set(newContext);
        notifyListeners(newContext);
        return seq;
    }

    public boolean updateContextIfFresh(long expectedSequence, WorkbenchContext newContext) {
        Objects.requireNonNull(newContext, "newContext must not be null");
        long current = sequenceNumber.get();
        if (expectedSequence < current) {
            TrystLog.debug(WorkbenchContextManager.class,
                    "Discarded stale context update (token=" + expectedSequence + ", current=" + current + ")");
            return false;
        }

        currentContext.set(newContext);
        notifyListeners(newContext);
        return true;
    }

    public void addContextListener(IWorkbenchContextListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeContextListener(IWorkbenchContextListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public long getCurrentSequence() {
        return sequenceNumber.get();
    }

    public void reset() {
        currentContext.set(WorkbenchContext.EMPTY);
        sequenceNumber.incrementAndGet();
        listeners.clear();
    }

    private void notifyListeners(WorkbenchContext context) {
        for (IWorkbenchContextListener listener : listeners) {
            try {
                listener.onContextChanged(context);
            } catch (Exception e) {
                TrystLog.error(WorkbenchContextManager.class, "Error notifying context listener: " + e.getMessage(), e);
            }
        }
    }
}
