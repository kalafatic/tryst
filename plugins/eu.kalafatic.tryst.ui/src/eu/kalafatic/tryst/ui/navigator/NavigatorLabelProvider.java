package eu.kalafatic.tryst.ui.navigator;

import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ExecutionStatus;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.graphics.Image;

/**
 * Label provider formatting elements in {@link TrystNavigatorView}.
 * Uses distinguishable status and category indicators for intuitive operational inspection.
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
                case SERVER -> "🖥️ ";
                case SESSION -> "📂 ";
                case TASK -> getTaskPrefix(node);
                case TASK_QUEUE -> "⏳ ";
                case TASK_RUNNING -> "⚙️ ";
                case TASK_COMPLETED -> "✅ ";
                case TASK_FAILED -> "❌ ";
                case TASK_CANCELLED -> "⏹️ ";
                case PROJECT -> "📦 ";
                case GIT_REPO -> "🌿 ";
                case GIT_BRANCH -> "🔀 ";
                case WORKSPACE -> "💼 ";
                case REVIEW_SESSION -> "🔍 ";
                case USER -> "👤 ";
                case BUILD_RESULT -> "🔨 ";
                case AI_EXECUTION -> "🧠 ";
                case MCP_RESOURCE -> "🔌 ";
                case TEIP_INTEGRATION -> "🌐 ";
                case DATABASE -> "🗄️ ";
                case TABLE -> "📊 ";
                case SQL_SCRIPT -> "📜 ";
                case SYSTEM_ITEM -> "⚙️ ";
            };
            return prefix + node.getName();
        }
        return super.getText(element);
    }

    private String getTaskPrefix(NavigatorNode node) {
        Object domain = node.getDomainObject();
        if (domain instanceof AgentTask t) {
            if (t.getStatus() == ExecutionStatus.COMPLETED) return "✅ ";
            if (t.getStatus() == ExecutionStatus.FAILED) return "❌ ";
            if (t.getStatus() == ExecutionStatus.RUNNING || t.getStatus() == ExecutionStatus.CLAIMED) return "⚙️ ";
            if (t.getStatus() == ExecutionStatus.CANCELLED) return "⏹️ ";
            return "⏳ ";
        } else if (domain instanceof Task t) {
            TaskStatus status = t.getStatus();
            if (status == TaskStatus.COMPLETED) return "✅ ";
            if (status == TaskStatus.FAILED) return "❌ ";
            if (status.isActive()) return "⚙️ ";
            if (status == TaskStatus.CANCELLED) return "⏹️ ";
            if (status == TaskStatus.WAITING_FOR_REVIEW || status == TaskStatus.WAITING_FOR_APPROVAL) return "🔍 ";
            return "⏳ ";
        }
        return "📋 ";
    }
}
