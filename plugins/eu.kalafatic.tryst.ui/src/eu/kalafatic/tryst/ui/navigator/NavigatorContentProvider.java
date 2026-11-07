package eu.kalafatic.tryst.ui.navigator;

import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.Viewer;

import java.util.List;

/**
 * Content provider for {@link TrystNavigatorView}.
 */
public class NavigatorContentProvider implements ITreeContentProvider {

    @Override
    public Object[] getElements(Object inputElement) {
        if (inputElement instanceof List<?> list) {
            return list.toArray();
        }
        if (inputElement instanceof Object[] arr) {
            return arr;
        }
        return new Object[0];
    }

    @Override
    public Object[] getChildren(Object parentElement) {
        if (parentElement instanceof NavigatorNode node) {
            return node.getChildren().toArray();
        }
        return new Object[0];
    }

    @Override
    public Object getParent(Object element) {
        if (element instanceof NavigatorNode node) {
            return node.getParent();
        }
        return null;
    }

    @Override
    public boolean hasChildren(Object element) {
        if (element instanceof NavigatorNode node) {
            return node.hasChildren();
        }
        return false;
    }

    @Override
    public void dispose() {
    }

    @Override
    public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
    }
}
