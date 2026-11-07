package eu.kalafatic.tryst.ui.context;

/**
 * Listener interface notified when the shared {@link WorkbenchContext} changes.
 */
@FunctionalInterface
public interface IWorkbenchContextListener {

    void onContextChanged(WorkbenchContext context);
}
