package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.model.WorkspaceStatus;
import eu.kalafatic.tryst.domain.repository.ProjectRepository;
import eu.kalafatic.tryst.domain.repository.TaskInstructionRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.domain.repository.WorkspaceRepository;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.evo.integration.mcp.McpServer;
import eu.kalafatic.tryst.evo.integration.mcp.ScopedMcpTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class McpServerSecurityTest {

    private McpServer mcpServer;
    private Workspace testWorkspace;
    private Path sandboxDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        sandboxDir = tempDir.resolve("sandbox");
        Files.createDirectories(sandboxDir);
        Files.writeString(sandboxDir.resolve("Main.java"), "public class Main { public static void main(String[] args){} }");

        testWorkspace = new Workspace("ws-1", sandboxDir, "user-1", "task-1", WorkspaceStatus.READY, Instant.now(), Instant.now());

        PermissionManager permissionManager = new PermissionManager();
        mcpServer = new McpServer(permissionManager);

        WorkspaceRepository wsRepo = new MockWorkspaceRepository(testWorkspace);
        mcpServer.registerTool(new ScopedMcpTools.ListWorkspaceFilesTool(wsRepo, permissionManager));
        mcpServer.registerTool(new ScopedMcpTools.ReadWorkspaceFileTool(wsRepo, permissionManager));
        mcpServer.registerTool(new ScopedMcpTools.SearchWorkspaceCodeTool(wsRepo, permissionManager));
    }

    @Test
    void testToolsListDiscovery() {
        String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}";
        String resp = mcpServer.handleJsonRpcRequest(req, "caller", "proj-1", "task-1", Set.of("read:workspace"));
        assertNotNull(resp);
        assertTrue(resp.contains("list_workspace_files"));
        assertTrue(resp.contains("read_workspace_file"));
        assertTrue(resp.contains("search_workspace_code"));
    }

    @Test
    void testAuthorizedReadFile() {
        String req = """
        {
            "jsonrpc": "2.0",
            "id": 2,
            "method": "tools/call",
            "params": {
                "name": "read_workspace_file",
                "arguments": {
                    "taskId": "task-1",
                    "path": "Main.java"
                }
            }
        }
        """;
        String resp = mcpServer.handleJsonRpcRequest(req, "caller", "proj-1", "task-1", Set.of("read:workspace"));
        assertNotNull(resp);
        assertTrue(resp.contains("public class Main"));
        assertTrue(resp.contains("\"isError\":false"));
    }

    @Test
    void testPathTraversalForbidden() {
        String req = """
        {
            "jsonrpc": "2.0",
            "id": 3,
            "method": "tools/call",
            "params": {
                "name": "read_workspace_file",
                "arguments": {
                    "taskId": "task-1",
                    "path": "../../../etc/passwd"
                }
            }
        }
        """;
        String resp = mcpServer.handleJsonRpcRequest(req, "caller", "proj-1", "task-1", Set.of("read:workspace"));
        assertNotNull(resp);
        assertTrue(resp.contains("\"isError\":true"));
        assertTrue(resp.contains("denied") || resp.contains("escape") || resp.contains("outside"));
    }

    @Test
    void testMissingScopeRejected() {
        String req = """
        {
            "jsonrpc": "2.0",
            "id": 4,
            "method": "tools/call",
            "params": {
                "name": "read_workspace_file",
                "arguments": {
                    "taskId": "task-1",
                    "path": "Main.java"
                }
            }
        }
        """;
        // Caller provides empty scopes
        String resp = mcpServer.handleJsonRpcRequest(req, "caller", "proj-1", "task-1", Set.of());
        assertNotNull(resp);
        assertTrue(resp.contains("error"));
        assertTrue(resp.contains("Permission denied") || resp.contains("required scope"));
    }

    private static class MockWorkspaceRepository implements WorkspaceRepository {
        private final Workspace ws;

        MockWorkspaceRepository(Workspace ws) {
            this.ws = ws;
        }

        @Override public void save(Workspace workspace) {}
        @Override public Optional<Workspace> findById(String id) { return Optional.ofNullable(ws); }
        @Override public Optional<Workspace> findActive() { return Optional.ofNullable(ws); }
        @Override public Optional<Workspace> findByTaskId(String taskId) {
            return taskId.equals(ws.getTaskId()) ? Optional.of(ws) : Optional.empty();
        }
        @Override public List<Workspace> findByOwnerId(String ownerId) { return List.of(ws); }
        @Override public List<Workspace> findByStatus(WorkspaceStatus status) { return List.of(); }
        @Override public List<Workspace> findAll() { return List.of(ws); }
        @Override public boolean delete(String id) { return true; }
    }
}
