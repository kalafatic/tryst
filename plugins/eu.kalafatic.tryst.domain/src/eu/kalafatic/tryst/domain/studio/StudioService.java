package eu.kalafatic.tryst.domain.studio;

import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskEvent;
import eu.kalafatic.tryst.domain.model.TaskInstruction;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.review.ReviewComment;

import java.util.List;
import java.util.Map;

public interface StudioService {

    record StudioTaskSnapshot(
            Task task,
            Workspace workspace,
            String repositoryName,
            String currentBranch,
            boolean isRunning,
            List<TaskEvent> eventHistory,
            List<TaskInstruction> instructions,
            List<ReviewComment> comments,
            List<StudioPlanStep> planSteps,
            List<StudioAlternative> alternatives,
            List<String> modifiedFiles,
            String fullDiff,
            long lastSeq
    ) {}

    StudioTaskSnapshot getTaskSnapshot(String taskId);
    StudioAlternative proposeAlternative(StudioAlternative alternative);
    List<StudioAlternative> getAlternatives(String taskId);
    boolean selectAlternative(String alternativeId, String taskId, String userId);
    void updatePlan(String taskId, List<StudioPlanStep> steps);
    List<StudioPlanStep> getPlanSteps(String taskId);
    StudioFileRevision recordFileModification(String taskId, String filePath, String diffHunk, int additions, int deletions);
    List<StudioFileRevision> getFileRevisions(String taskId, String filePath);
    TaskInstruction submitUserInstruction(String taskId, String userId, String content);
    ReviewComment addComment(ReviewComment comment);
    List<ReviewComment> getComments(String taskId);
    Map<String, String> getFileContent(String taskId, String filePath);
}
