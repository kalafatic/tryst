package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.model.WorkspaceStateTransitionValidator;
import eu.kalafatic.tryst.domain.model.WorkspaceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceLifecycleTest {

    @TempDir
    Path tempDir;

    @Test
    void testWorkspaceStandardLifecycleTransitions() {
        Path root = tempDir.resolve("ws_lifecycle");
        Workspace ws = new Workspace("ws-1", root, "user-alice", "task-100", "https://github.com/org/repo.git",
                "feature/auth", WorkspaceStatus.ALLOCATED, java.time.Instant.now(), java.time.Instant.now(), java.util.Map.of());

        assertEquals(WorkspaceStatus.ALLOCATED, ws.getStatus());
        assertEquals("user-alice", ws.getOwnerId());
        assertEquals("task-100", ws.getTaskId());
        assertEquals("feature/auth", ws.getBranch());
        assertEquals("https://github.com/org/repo.git", ws.getRepositoryUrl());

        // ALLOCATED -> INITIALIZING
        ws.initialize();
        assertEquals(WorkspaceStatus.INITIALIZING, ws.getStatus());
        assertTrue(ws.getStatus().isActive());

        // INITIALIZING -> READY
        ws.markReady();
        assertEquals(WorkspaceStatus.READY, ws.getStatus());

        // READY -> ACTIVE
        ws.activate();
        assertEquals(WorkspaceStatus.ACTIVE, ws.getStatus());

        // ACTIVE -> WAITING_FOR_REVIEW
        ws.markWaitingForReview();
        assertEquals(WorkspaceStatus.WAITING_FOR_REVIEW, ws.getStatus());
        assertFalse(ws.getStatus().isSafeToCleanup()); // active review must not be cleaned up

        // WAITING_FOR_REVIEW -> REWORKING
        ws.rework();
        assertEquals(WorkspaceStatus.REWORKING, ws.getStatus());
        assertTrue(ws.getStatus().isActive());

        // REWORKING -> ACTIVE -> COMPLETED
        ws.activate();
        ws.complete();
        assertEquals(WorkspaceStatus.COMPLETED, ws.getStatus());
        assertTrue(ws.getStatus().isSafeToCleanup());

        // COMPLETED -> CLEANUP_PENDING
        ws.markCleanupPending();
        assertEquals(WorkspaceStatus.CLEANUP_PENDING, ws.getStatus());

        // CLEANUP_PENDING -> ARCHIVED
        ws.archive();
        assertEquals(WorkspaceStatus.ARCHIVED, ws.getStatus());
        assertTrue(ws.getStatus().isTerminal());
    }

    @Test
    void testInvalidTransitionsThrowDomainException() {
        Path root = tempDir.resolve("ws_invalid");
        Workspace ws = new Workspace("ws-2", root, WorkspaceStatus.ALLOCATED, java.time.Instant.now(), java.time.Instant.now());

        // Cannot jump directly from ALLOCATED to COMPLETED or WAITING_FOR_REVIEW
        assertThrows(TrystDomainException.class, ws::complete);
        assertThrows(TrystDomainException.class, ws::markWaitingForReview);
        assertThrows(TrystDomainException.class, ws::rework);

        // Cannot clean up while in active state
        ws.initialize();
        ws.markReady();
        ws.activate();
        assertThrows(TrystDomainException.class, ws::markCleanupPending);

        // In WAITING_FOR_REVIEW, cannot transition directly to INITIALIZING
        ws.markWaitingForReview();
        assertThrows(TrystDomainException.class, ws::initialize);
    }

    @Test
    void testValidatorMatrix() {
        assertTrue(WorkspaceStateTransitionValidator.canTransition(WorkspaceStatus.ALLOCATED, WorkspaceStatus.INITIALIZING));
        assertTrue(WorkspaceStateTransitionValidator.canTransition(WorkspaceStatus.ACTIVE, WorkspaceStatus.WAITING_FOR_REVIEW));
        assertTrue(WorkspaceStateTransitionValidator.canTransition(WorkspaceStatus.WAITING_FOR_REVIEW, WorkspaceStatus.REWORKING));
        assertTrue(WorkspaceStateTransitionValidator.canTransition(WorkspaceStatus.COMPLETED, WorkspaceStatus.ARCHIVED));

        assertFalse(WorkspaceStateTransitionValidator.canTransition(WorkspaceStatus.ALLOCATED, WorkspaceStatus.ACTIVE));
        assertFalse(WorkspaceStateTransitionValidator.canTransition(WorkspaceStatus.CLEANUP_PENDING, WorkspaceStatus.ACTIVE));
    }
}
