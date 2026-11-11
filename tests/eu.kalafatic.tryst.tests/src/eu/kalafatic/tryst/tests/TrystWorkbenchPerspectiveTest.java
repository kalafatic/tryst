package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.ui.context.IWorkbenchContextListener;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import eu.kalafatic.tryst.ui.context.WorkbenchContextManager;
import eu.kalafatic.tryst.ui.editor.StandardEditorPagesBootstrap;
import eu.kalafatic.tryst.ui.editor.TrystEditorPageDescriptor;
import eu.kalafatic.tryst.ui.editor.TrystEditorPageRegistry;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditorInput;
import eu.kalafatic.tryst.ui.navigator.NavigatorNode;
import eu.kalafatic.tryst.ui.navigator.TrystNavigatorView;
import eu.kalafatic.tryst.ui.perspective.TrystAdministrationPerspective;
import eu.kalafatic.tryst.ui.perspective.TrystMainPerspective;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive verification of TRYST Professional Workbench perspective refactoring:
 * - Shared Workbench Context and monotonic sequence validation
 * - Stale asynchronous update rejection
 * - MultiPage Editor registry, standard page bootstrap, and ordering
 * - Editor input identity and duplicate prevention semantics
 * - Navigator hierarchy and data binding
 * - Perspective layout constants and non-null IDs
 */
public class TrystWorkbenchPerspectiveTest {

    @BeforeEach
    void setUp() {
        WorkbenchContextManager.getInstance().reset();
        TrystEditorPageRegistry.getInstance().clear();
    }

    @AfterEach
    void tearDown() {
        WorkbenchContextManager.getInstance().reset();
        TrystEditorPageRegistry.getInstance().clear();
    }

    @Test
    void testPerspectiveDeclarations() {
        assertEquals("eu.kalafatic.tryst.ui.perspective.main", TrystMainPerspective.ID);
        assertEquals("eu.kalafatic.tryst.ui.perspective.administration", TrystAdministrationPerspective.ID);
        assertEquals("eu.kalafatic.tryst.ui.navigator.view", TrystNavigatorView.ID);
        assertEquals("eu.kalafatic.tryst.ui.editor.workbenchEditor", TrystWorkbenchEditor.ID);
    }

    @Test
    void testWorkbenchContextImmutabilityAndDefaults() {
        WorkbenchContext empty = WorkbenchContextManager.getInstance().getContext();
        assertNotNull(empty);
        assertFalse(empty.hasProject());
        assertFalse(empty.hasTask());
        assertFalse(empty.hasSession());
        assertNotNull(empty.properties());
        assertTrue(empty.properties().isEmpty());

        Project p = new Project("proj-1", "default-user", "TestProject", Path.of("/tmp/test"), true);
        long seq = WorkbenchContextManager.getInstance().setContext(
                empty.toBuilder().project(p).build()
        );
        assertTrue(seq > 0);

        WorkbenchContext active = WorkbenchContextManager.getInstance().getContext();
        assertTrue(active.hasProject());
        assertEquals("proj-1", active.projectId());
        assertEquals("TestProject", active.projectName());
        assertEquals("/tmp/test", active.repositoryPath());
        assertSame(p, active.getProject().orElse(null));
    }

    @Test
    void testStaleAsynchronousUpdateRejection() {
        WorkbenchContextManager mgr = WorkbenchContextManager.getInstance();

        long token1 = mgr.getCurrentSequence();
        Project p1 = new Project("p1", "default-user", "Project 1", Path.of("/tmp/p1"), true);
        WorkbenchContext ctx1 = WorkbenchContext.builder().project(p1).build();

        // User immediately switches to project 2
        Project p2 = new Project("p2", "default-user", "Project 2", Path.of("/tmp/p2"), true);
        WorkbenchContext ctx2 = WorkbenchContext.builder().project(p2).build();
        long token2 = mgr.setContext(ctx2);

        // An async background task started under token1 now attempts to commit its result
        boolean appliedStale = mgr.updateContextIfFresh(token1, ctx1);
        assertFalse(appliedStale, "Stale async update must be rejected when a newer context has been set");
        assertEquals("p2", mgr.getContext().projectId(), "Current context must remain Project 2");

        // Fresh update under token2 or newer succeeds
        Project p3 = new Project("p3", "default-user", "Project 3", Path.of("/tmp/p3"), true);
        WorkbenchContext ctx3 = WorkbenchContext.builder().project(p3).build();
        boolean appliedFresh = mgr.updateContextIfFresh(token2, ctx3);
        assertTrue(appliedFresh, "Fresh update must be accepted");
        assertEquals("p3", mgr.getContext().projectId());
    }

    @Test
    void testContextListenersNotification() {
        WorkbenchContextManager mgr = WorkbenchContextManager.getInstance();
        List<WorkbenchContext> received = new ArrayList<>();
        IWorkbenchContextListener listener = received::add;

        mgr.addContextListener(listener);

        Project p = new Project("proj-listener", "default-user", "ListenerProject", Path.of("/tmp"), true);
        mgr.setContext(WorkbenchContext.builder().project(p).build());

        assertEquals(1, received.size());
        assertEquals("proj-listener", received.get(0).projectId());

        mgr.removeContextListener(listener);
        mgr.setContext(WorkbenchContext.EMPTY);
        assertEquals(1, received.size(), "Removed listener must no longer be notified");
    }

    @Test
    void testConcurrentContextUpdatesThreadSafety() throws InterruptedException {
        WorkbenchContextManager mgr = WorkbenchContextManager.getInstance();
        int threadCount = 8;
        int iterations = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger listenerNotifiedCount = new AtomicInteger(0);

        mgr.addContextListener(ctx -> listenerNotifiedCount.incrementAndGet());

        for (int i = 0; i < threadCount; i++) {
            final int tId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < iterations; j++) {
                        Project p = new Project("p-" + tId + "-" + j, "default-user", "Proj" + j, Path.of("/tmp"), true);
                        mgr.setContext(WorkbenchContext.builder().project(p).build());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threadCount * iterations, mgr.getCurrentSequence());
        assertEquals(threadCount * iterations, listenerNotifiedCount.get());
        assertNotNull(mgr.getContext());
        assertTrue(mgr.getContext().hasProject());
    }

    @Test
    void testEditorPageRegistryAndBootstrap() {
        TrystEditorPageRegistry reg = TrystEditorPageRegistry.getInstance();
        assertTrue(reg.getDescriptors().isEmpty(), "Registry should start empty");

        StandardEditorPagesBootstrap.initializeStandardPages();
        List<TrystEditorPageDescriptor> descriptors = reg.getDescriptors();

        assertEquals(15, descriptors.size(), "Standard bootstrap must register exactly 15 pages");

        // Verify natural ordering
        for (int i = 0; i < descriptors.size() - 1; i++) {
            assertTrue(descriptors.get(i).order() <= descriptors.get(i + 1).order(),
                    "Descriptors must be ordered by descriptor order index");
        }

        // Verify expected IDs
        List<String> ids = descriptors.stream().map(TrystEditorPageDescriptor::id).toList();
        assertTrue(ids.contains("eu.kalafatic.tryst.ui.editor.page.dashboard"));
        assertTrue(ids.contains("eu.kalafatic.tryst.ui.editor.page.projectExplorer"));
        assertTrue(ids.contains("eu.kalafatic.tryst.ui.editor.page.taskManagement"));
        assertTrue(ids.contains("eu.kalafatic.tryst.ui.editor.page.taskExecution"));
        assertTrue(ids.contains("eu.kalafatic.tryst.ui.editor.page.codeReview"));
        assertTrue(ids.contains("eu.kalafatic.tryst.ui.editor.page.git"));
        assertTrue(ids.contains("eu.kalafatic.tryst.ui.editor.page.build"));
        assertTrue(ids.contains("eu.kalafatic.tryst.ui.editor.page.testQuality"));
        assertTrue(ids.contains("eu.kalafatic.tryst.ui.editor.page.aiIntegration"));
        assertTrue(ids.contains("eu.kalafatic.tryst.ui.editor.page.mcp"));
        assertTrue(ids.contains("eu.kalafatic.tryst.ui.editor.page.teip"));
        assertTrue(ids.contains("eu.kalafatic.tryst.ui.editor.page.serverMonitoring"));
        assertTrue(ids.contains("eu.kalafatic.tryst.ui.editor.page.configuration"));

        // Verify page titles
        assertEquals("Overview", descriptors.stream().filter(d -> d.id().equals("eu.kalafatic.tryst.ui.editor.page.dashboard")).findFirst().orElseThrow().title());
        assertEquals("Project Explorer", descriptors.stream().filter(d -> d.id().equals("eu.kalafatic.tryst.ui.editor.page.projectExplorer")).findFirst().orElseThrow().title());
        assertEquals("Task Management", descriptors.stream().filter(d -> d.id().equals("eu.kalafatic.tryst.ui.editor.page.taskManagement")).findFirst().orElseThrow().title());
        assertEquals("Task Execution", descriptors.stream().filter(d -> d.id().equals("eu.kalafatic.tryst.ui.editor.page.taskExecution")).findFirst().orElseThrow().title());
        assertEquals("Code Review", descriptors.stream().filter(d -> d.id().equals("eu.kalafatic.tryst.ui.editor.page.codeReview")).findFirst().orElseThrow().title());
        assertEquals("Git", descriptors.stream().filter(d -> d.id().equals("eu.kalafatic.tryst.ui.editor.page.git")).findFirst().orElseThrow().title());
        assertEquals("Build", descriptors.stream().filter(d -> d.id().equals("eu.kalafatic.tryst.ui.editor.page.build")).findFirst().orElseThrow().title());
        assertEquals("Tests & Quality", descriptors.stream().filter(d -> d.id().equals("eu.kalafatic.tryst.ui.editor.page.testQuality")).findFirst().orElseThrow().title());
        assertEquals("AI Integration", descriptors.stream().filter(d -> d.id().equals("eu.kalafatic.tryst.ui.editor.page.aiIntegration")).findFirst().orElseThrow().title());
        assertEquals("MCP", descriptors.stream().filter(d -> d.id().equals("eu.kalafatic.tryst.ui.editor.page.mcp")).findFirst().orElseThrow().title());
        assertEquals("TEIP Integration", descriptors.stream().filter(d -> d.id().equals("eu.kalafatic.tryst.ui.editor.page.teip")).findFirst().orElseThrow().title());
        assertEquals("Server Monitoring", descriptors.stream().filter(d -> d.id().equals("eu.kalafatic.tryst.ui.editor.page.serverMonitoring")).findFirst().orElseThrow().title());
        assertEquals("Configuration", descriptors.stream().filter(d -> d.id().equals("eu.kalafatic.tryst.ui.editor.page.configuration")).findFirst().orElseThrow().title());
    }

    @Test
    void testTrystWorkbenchEditorInputEqualityAndSingleton() {
        TrystWorkbenchEditorInput input1 = TrystWorkbenchEditorInput.getInstance();
        TrystWorkbenchEditorInput input2 = new TrystWorkbenchEditorInput();

        assertEquals(input1, input2);
        assertEquals(input1.hashCode(), input2.hashCode());
        assertEquals("TRYST Central Workspace", input1.getName());
        assertTrue(input1.exists());
        assertNotNull(input1.getToolTipText());
    }

    @Test
    void testNavigatorNodeHierarchy() {
        NavigatorNode root = new NavigatorNode("root", "Root Category",
                NavigatorNode.NodeType.ROOT_CATEGORY, null, null);
        assertFalse(root.hasChildren());

        Project p = new Project("p1", "default-user", "Demo Project", Path.of("/tmp/demo"), true);
        NavigatorNode child = new NavigatorNode("child-1", "Demo Project",
                NavigatorNode.NodeType.PROJECT, p, root);

        root.setChildren(List.of(child));

        assertTrue(root.hasChildren());
        assertEquals(1, root.getChildren().size());
        assertSame(root, child.getParent());
        assertSame(p, child.getDomainObject());
        assertEquals(NavigatorNode.NodeType.PROJECT, child.getType());
    }

    @Test
    void testTrystNavigatorViewTreeModelConstruction() {
        TrystNavigatorView navView = new TrystNavigatorView();
        List<NavigatorNode> treeRoots = navView.buildTreeModel();

        assertNotNull(treeRoots);
        assertTrue(treeRoots.size() >= 4, "Navigator must construct operational categories");

        List<String> names = treeRoots.stream().map(NavigatorNode::getName).toList();
        assertTrue(names.contains("Projects & Repositories"));
        assertTrue(names.contains("Servers & Protocols"));
        assertTrue(names.contains("TEIP & AI Integrations"));

        NavigatorNode integrationNode = treeRoots.stream()
                .filter(n -> n.getName().equals("TEIP & AI Integrations"))
                .findFirst()
                .orElse(null);
        assertNotNull(integrationNode);
        assertTrue(integrationNode.hasChildren());
        assertEquals(3, integrationNode.getChildren().size());
    }
}
