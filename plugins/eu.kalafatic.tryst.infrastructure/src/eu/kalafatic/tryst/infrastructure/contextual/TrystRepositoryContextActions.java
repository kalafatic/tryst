package eu.kalafatic.tryst.infrastructure.contextual;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import eu.kalafatic.tryst.domain.contextual.ActionFeedback;
import eu.kalafatic.tryst.domain.contextual.ContextualAction;
import eu.kalafatic.tryst.domain.contextual.ContextualActionDescriptor;
import eu.kalafatic.tryst.domain.contextual.ContextualActionDescriptor.Confirmation;
import eu.kalafatic.tryst.domain.contextual.ContextualActionHandler;
import eu.kalafatic.tryst.domain.contextual.ContextualActionIds;
import eu.kalafatic.tryst.domain.contextual.ContextualActionProvider;
import eu.kalafatic.tryst.domain.contextual.ContextualActionRegistry;
import eu.kalafatic.tryst.domain.contextual.ContextualSelection;
import eu.kalafatic.tryst.domain.model.Permission;
import eu.kalafatic.tryst.infrastructure.vcs.GitService;

/**
 * TRYST repository contextual actions (Darwin Family Contextual Action Contract v1.0):
 * git status/pull/push/commit and diagnostics through the existing {@link GitService}.
 * Mutating git actions require the corresponding permission (checked through the
 * selection's permission set, populated by the surface).
 */
public final class TrystRepositoryContextActions {

    private TrystRepositoryContextActions() {
    }

    public static void install(ContextualActionRegistry registry, GitService gitService) {
        registry.registerProvider(new RepositoryContextProvider());
        registry.registerHandler(handler(ContextualActionIds.PROJECT_GIT_STATUS,
                (sel, fb) -> fb.info(gitService.getStatus(repoPath(sel)).output())));
        registry.registerHandler(handler(ContextualActionIds.PROJECT_PUSH, (sel, fb) -> {
            var result = gitService.push(repoPath(sel), null, null, null, false);
            fb.info(result.isSuccess() ? "Push succeeded" : "Push failed: "
                    + result.error());
        }));
        registry.registerHandler(handler(ContextualActionIds.PROJECT_COMMIT, (sel, fb) -> {
            String message = sel.state("message");
            if (message == null || message.isBlank()) {
                fb.error("Commit message is required", null);
                return;
            }
            var result = gitService.commit(repoPath(sel), message);
            fb.info(result.isSuccess() ? "Committed: " + message : "Commit failed: "
                    + result.error());
        }));
        registry.registerHandler(handler(ContextualActionIds.PROJECT_DIAGNOSTICS,
                (sel, fb) -> {
                    GitService.GitCommandResult status = gitService.getStatus(repoPath(sel));
                    GitService.GitCommandResult branches = gitService
                            .listBranches(repoPath(sel));
                    fb.info("Status:\n" + status.output() + "\nBranches:\n"
                            + branches.output());
                }));
    }

    private static Path repoPath(ContextualSelection selection) {
        String path = selection.state("path");
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Repository path is missing");
        }
        return Path.of(path);
    }

    private static ContextualActionHandler handler(String actionId, Body body) {
        return new ContextualActionHandler() {
            @Override
            public String actionId() {
                return actionId;
            }

            @Override
            public void execute(ContextualSelection selection, ActionFeedback feedback)
                    throws Exception {
                body.run(selection, feedback);
            }
        };
    }

    private interface Body {
        void run(ContextualSelection selection, ActionFeedback feedback) throws Exception;
    }

    private static final class RepositoryContextProvider
            implements ContextualActionProvider {

        @Override
        public List<String> contextTypes() {
            return List.of("repository");
        }

        @Override
        public List<ContextualAction> actionsFor(ContextualSelection selection) {
            List<ContextualAction> actions = new ArrayList<>();
            boolean single = !selection.multiSelect();
            Set<String> permissions = selection.permissions();
            boolean canCommit = permissions.contains(Permission.COMMIT_APPROVED_CHANGES.name());
            boolean canPush = permissions.contains(Permission.MANAGE_GITHUB_CREDENTIALS.name());

            ContextualActionDescriptor status = descriptor(
                    ContextualActionIds.PROJECT_GIT_STATUS, "Git Status",
                    ContextualActionDescriptor.GROUP_INSPECTION, "repository");
            actions.add(single ? ContextualAction.enabled(status)
                    : ContextualAction.disabled(status, "Not available for multiple "
                            + "selection"));

            ContextualActionDescriptor diagnostics = descriptor(
                    ContextualActionIds.PROJECT_DIAGNOSTICS, "Repository Diagnostics",
                    ContextualActionDescriptor.GROUP_INSPECTION, "repository");
            actions.add(single ? ContextualAction.enabled(diagnostics)
                    : ContextualAction.disabled(diagnostics, "Not available for multiple "
                            + "selection"));

            ContextualActionDescriptor commit = descriptor(
                    ContextualActionIds.PROJECT_COMMIT, "Commit",
                    ContextualActionDescriptor.GROUP_LIFECYCLE, "repository")
                    .withConfirmation(Confirmation.CONFIRM);
            actions.add(single && canCommit ? ContextualAction.enabled(commit)
                    : ContextualAction.disabled(commit, single
                            ? "You are not permitted to commit changes"
                            : "Not available for multiple selection"));

            ContextualActionDescriptor push = descriptor(ContextualActionIds.PROJECT_PUSH,
                    "Push", ContextualActionDescriptor.GROUP_LIFECYCLE, "repository")
                    .withConfirmation(Confirmation.CONFIRM);
            actions.add(single && canPush ? ContextualAction.enabled(push)
                    : ContextualAction.disabled(push, single
                            ? "You are not permitted to push to the remote"
                            : "Not available for multiple selection"));
            return actions;
        }
    }

    private static ContextualActionDescriptor descriptor(String actionId, String label,
            String group, String... contextTypes) {
        return ContextualActionDescriptor.of(actionId, label, group, contextTypes);
    }
}
