package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.ai.ExternalAiDelegator;
import eu.kalafatic.tryst.domain.harness.DefaultConnectedApplicationRegistry;
import eu.kalafatic.tryst.domain.harness.HarnessAiDelegator;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.model.WorkspaceStatus;
import eu.kalafatic.tryst.infrastructure.cli.OpenCodeCodingHarnessAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostic harness-boundary execution with the REAL OpenCode CLI (WP-B evidence):
 * exercises exactly the delegation path the TaskWorker uses (HarnessAiDelegator over the
 * connected-application registry) against a real cloned repository workspace.
 */
class HarnessDelegationDiagnosticTest {

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void testRealHarnessDelegationModifiesWorkspace() throws Exception {
        OpenCodeCodingHarnessAdapter probe = new OpenCodeCodingHarnessAdapter();
        assertTrue(probe.testConnection().success(),
                "BLOCKED: OpenCode CLI not available on this host; delegation scenario cannot run");

        Path root = Files.createTempDirectory("hdiag");
        Path repoSource = root.resolve("repo-src");
        try (Stream<Path> walk = Files.walk(Path.of("/home/petr/git/DCoder-121126"))) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path dest = repoSource.resolve(Path.of("/home/petr/git/DCoder-121126").relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else { Files.createDirectories(dest.getParent()); Files.copy(p, dest); }
            }
        }

        Path workspaceDir = root.resolve("ws");
        Files.createDirectories(workspaceDir);

        eu.kalafatic.tryst.infrastructure.vcs.GitService git = new eu.kalafatic.tryst.infrastructure.vcs.GitService();
        eu.kalafatic.tryst.infrastructure.vcs.GitService.GitCommandResult cloneRes =
                git.cloneOrFetch(workspaceDir, repoSource.toUri().toString(), "master", null);
        assertTrue(cloneRes.isSuccess(), "Repo clone failed: " + cloneRes.error());

        OpenCodeCodingHarnessAdapter adapter = new OpenCodeCodingHarnessAdapter();
        adapter.updateConnectionConfiguration(Map.of(
                "agent", "build",
                "model", "qwen3-coder-30b-32k:latest",
                "autoApprove", "true"
        ));

        DefaultConnectedApplicationRegistry registry = new DefaultConnectedApplicationRegistry(null);
        registry.registerAdapter(adapter);
        registry.selectApplication("opencode");
        HarnessAiDelegator delegator = new HarnessAiDelegator(registry);

        Task task = new Task("t-hdiag", "p-hdiag", "s-hdiag", "Harness delegation diagnostic",
                "Append the line 'tryst-e2e-marker-7429' to the end of README.md and create a new file named e2e-marker.txt containing exactly the text: tryst-e2e-ok",
                TaskStatus.CREATED, TaskPriority.NORMAL, null, null, null, null, 0, null,
                Instant.now(), Instant.now());
        Workspace workspace = new Workspace("ws-hdiag", workspaceDir, "hdiag-owner", task.getId(),
                repoSource.toUri().toString(), "master", WorkspaceStatus.READY, Instant.now(), Instant.now(),
                Map.of());

        StringBuilder log = new StringBuilder();
        ExternalAiDelegator.DelegationResult result = delegator.delegateImplementation(
                task, workspace,
                "Append the line 'tryst-e2e-marker-7429' to the end of README.md and create a new file named e2e-marker.txt containing exactly the text: tryst-e2e-ok",
                "Append the line 'tryst-e2e-marker-7429' to the end of README.md and create a new file named e2e-marker.txt containing exactly the text: tryst-e2e-ok",
                log::append
        );

        System.out.println("DELEGATION_RESULT handled=" + result.handled() + " success=" + result.success()
                + " summary=" + result.summary() + " error=" + result.errorMessage());
        System.out.println("DELEGATION_LOG_BEGIN");
        System.out.println(log);
        System.out.println("DELEGATION_LOG_END");

        assertTrue(result.handled(), "Delegation must be handled by the selected OpenCode harness");
        assertTrue(result.success(), "Real OpenCode delegation must succeed: " + result.errorMessage());

        Path marker = workspaceDir.resolve("e2e-marker.txt");
        assertTrue(Files.exists(marker), "OpenCode must have created e2e-marker.txt in the workspace");
        assertEquals("tryst-e2e-ok", Files.readString(marker).trim());
        assertTrue(Files.readString(workspaceDir.resolve("README.md")).contains("tryst-e2e-marker-7429"),
                "OpenCode must have modified README.md in the workspace");
    }
}
