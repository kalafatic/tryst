package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.model.WorkspaceStatus;
import eu.kalafatic.tryst.domain.repository.WorkspaceRepository;

import java.util.List;
import java.util.Objects;

/**
 * Service responsible for reconciling workspace states following application restarts.
 * Active or initializing workspaces whose execution was violently interrupted are marked FAILED
 * or set to recovery states, while WAITING_FOR_REVIEW, REWORKING, READY, COMPLETED, and ARCHIVED
 * workspaces are strictly preserved so no user work is lost.
 */
public class WorkspaceRecoveryService {

    private final WorkspaceRepository workspaceRepository;

    public WorkspaceRecoveryService(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository must not be null");
    }

    /**
     * Executes crash-restart recovery across all registered workspaces.
     */
    public synchronized int recoverWorkspaces() {
        TrystLog.info(WorkspaceRecoveryService.class, "Initiating workspace crash-restart recovery check...");
        List<Workspace> allWorkspaces = workspaceRepository.findAll();
        int recoveredCount = 0;

        for (Workspace ws : allWorkspaces) {
            WorkspaceStatus status = ws.getStatus();
            // Workspaces interrupted mid-operation (ALLOCATED, INITIALIZING, ACTIVE) need recovery
            if (status == WorkspaceStatus.INITIALIZING || status == WorkspaceStatus.ALLOCATED) {
                TrystLog.warn(WorkspaceRecoveryService.class,
                        "Recovering uninitialized/allocated workspace " + ws.getId() + " (was " + status + ") -> FAILED");
                ws.fail();
                ws.setMetadata("recovery_reason", "Interrupted during allocation or initialization by restart");
                workspaceRepository.save(ws);
                recoveredCount++;
            } else if (status == WorkspaceStatus.ACTIVE) {
                // If a task workspace was actively running when system stopped, transition to FAILED / ready for rework
                TrystLog.warn(WorkspaceRecoveryService.class,
                        "Recovering interrupted active workspace " + ws.getId() + " -> FAILED");
                ws.fail();
                ws.setMetadata("recovery_reason", "Interrupted during active execution by restart");
                workspaceRepository.save(ws);
                recoveredCount++;
            } else if (status == WorkspaceStatus.WAITING_FOR_REVIEW) {
                TrystLog.info(WorkspaceRecoveryService.class,
                        "Preserving review-pending workspace " + ws.getId() + " for task " + ws.getTaskId());
            } else if (status == WorkspaceStatus.REWORKING) {
                TrystLog.info(WorkspaceRecoveryService.class,
                        "Preserving rework-pending workspace " + ws.getId() + " for task " + ws.getTaskId());
            }
        }

        TrystLog.info(WorkspaceRecoveryService.class, "Workspace recovery completed. Recovered " + recoveredCount + " workspaces.");
        return recoveredCount;
    }
}
