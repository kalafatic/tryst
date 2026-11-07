package eu.kalafatic.tryst.ui.editor;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IPersistableElement;

import java.util.Objects;

/**
 * Singleton-like editor input representing the active TRYST workspace workbench.
 */
public class TrystWorkbenchEditorInput implements IEditorInput {

    public static final String DEFAULT_NAME = "TRYST Central Workspace";
    private static final TrystWorkbenchEditorInput INSTANCE = new TrystWorkbenchEditorInput();

    private final String name;

    public TrystWorkbenchEditorInput() {
        this(DEFAULT_NAME);
    }

    public TrystWorkbenchEditorInput(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    public static TrystWorkbenchEditorInput getInstance() {
        return INSTANCE;
    }

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public ImageDescriptor getImageDescriptor() {
        return null;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public IPersistableElement getPersistable() {
        return null;
    }

    @Override
    public String getToolTipText() {
        return "TRYST Integrated Development & Execution Workspace";
    }

    @Override
    public <T> T getAdapter(Class<T> adapter) {
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrystWorkbenchEditorInput that = (TrystWorkbenchEditorInput) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
