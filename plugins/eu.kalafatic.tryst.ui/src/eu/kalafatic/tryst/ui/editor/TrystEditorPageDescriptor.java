package eu.kalafatic.tryst.ui.editor;

import java.util.Objects;

/**
 * Descriptor metadata for an editor page registered with {@link TrystEditorPageRegistry}.
 */
public record TrystEditorPageDescriptor(
        String id,
        String title,
        int order,
        ITrystEditorPageFactory factory
) implements Comparable<TrystEditorPageDescriptor> {

    public TrystEditorPageDescriptor {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(factory, "factory must not be null");
    }

    @Override
    public int compareTo(TrystEditorPageDescriptor other) {
        return Integer.compare(this.order, other.order);
    }
}
