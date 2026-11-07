package eu.kalafatic.tryst.ui.editor;

import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

/**
 * Contract for individual pages registered within the TRYST MultiPage Editor.
 */
public interface ITrystEditorPage {

    String getId();

    String getTitle();

    Control createControl(Composite parent);

    Control getControl();

    void onActivated();

    void onDeactivated();

    void onContextChanged(WorkbenchContext context);

    void refresh();

    void setFocus();

    void dispose();
}
