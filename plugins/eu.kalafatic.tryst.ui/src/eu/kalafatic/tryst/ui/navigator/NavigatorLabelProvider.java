package eu.kalafatic.tryst.ui.navigator;

import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.graphics.Image;

/**
 * Label provider formatting elements in {@link TrystNavigatorView}.
 */
public class NavigatorLabelProvider extends LabelProvider {

    @Override
    public Image getImage(Object element) {
        return null;
    }

    @Override
    public String getText(Object element) {
        if (element instanceof NavigatorNode node) {
            String prefix = switch (node.getType()) {
                case ROOT_CATEGORY -> "📁 ";
                case PROJECT -> "📦 ";
                case GIT_REPO -> "🌿 ";
                case GIT_BRANCH -> "🔀 ";
                case TASK -> "📋 ";
                case WORKSPACE -> "💼 ";
                case REVIEW_SESSION -> "🔍 ";
                case BUILD_RESULT -> "⚙️ ";
                case AI_EXECUTION -> "🧠 ";
                case MCP_RESOURCE -> "🔌 ";
                case TEIP_INTEGRATION -> "🌐 ";
            };
            return prefix + node.getName();
        }
        return super.getText(element);
    }
}
