package eu.kalafatic.tryst.ui.editor;

/**
 * Functional factory for creating an instance of {@link ITrystEditorPage}.
 */
@FunctionalInterface
public interface ITrystEditorPageFactory {

    ITrystEditorPage createPage(TrystWorkbenchEditor editor);
}
