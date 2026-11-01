package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.tool.FileTools;
import eu.kalafatic.tryst.domain.tool.Tool;
import eu.kalafatic.tryst.domain.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolAndWorkspaceSecurityTest {

    @TempDir
    Path tempWorkspaceDir;

    @Test
    void testFileToolsWithinAuthorizedWorkspace() throws Exception {
        Workspace workspace = new Workspace(tempWorkspaceDir);
        PermissionManager permissionManager = new PermissionManager();
        ToolRegistry registry = new ToolRegistry();

        registry.register(new FileTools.WriteFileTool());
        registry.register(new FileTools.ReadFileTool());
        registry.register(new FileTools.ListDirectoryTool());

        // 1. Write file
        Tool writeTool = registry.getTool("write_file").orElseThrow();
        Tool.ToolResult writeResult = writeTool.execute(
                Map.of("path", "hello.txt", "content", "Hello TRYST Agent"),
                workspace,
                permissionManager
        );
        assertTrue(writeResult.success());
        assertTrue(Files.exists(tempWorkspaceDir.resolve("hello.txt")));

        // 2. Read file
        Tool readTool = registry.getTool("read_file").orElseThrow();
        Tool.ToolResult readResult = readTool.execute(
                Map.of("path", "hello.txt"),
                workspace,
                permissionManager
        );
        assertTrue(readResult.success());
        assertEquals("Hello TRYST Agent", readResult.output());

        // 3. List directory
        Tool listTool = registry.getTool("list_directory").orElseThrow();
        Tool.ToolResult listResult = listTool.execute(
                Map.of("path", "."),
                workspace,
                permissionManager
        );
        assertTrue(listResult.success());
        assertTrue(listResult.output().contains("hello.txt"));
    }

    @Test
    void testWorkspaceBoundaryEnforcementRejectsDirectoryTraversal() {
        Workspace workspace = new Workspace(tempWorkspaceDir);
        PermissionManager permissionManager = new PermissionManager();
        Tool readFileTool = new FileTools.ReadFileTool();

        // Attempting to read outside workspace root
        Tool.ToolResult traversalResult = readFileTool.execute(
                Map.of("path", "../../etc/passwd"),
                workspace,
                permissionManager
        );
        assertFalse(traversalResult.success());
        assertTrue(traversalResult.error().contains("outside authorized workspace root"));
    }
}
