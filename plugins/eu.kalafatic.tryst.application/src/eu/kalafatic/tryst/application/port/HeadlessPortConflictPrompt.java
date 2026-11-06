package eu.kalafatic.tryst.application.port;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.core.port.PortConflictAction;
import eu.kalafatic.tryst.core.port.PortConflictPrompt;
import eu.kalafatic.tryst.core.port.PortConflictReport;
import eu.kalafatic.tryst.core.port.ServicePortIdentifier;

/**
 * Headless conflict prompt implementation for TRYST server mode.
 * Operates non-interactively without displaying GUI dialogs, logs actionable conflict
 * details, and selects deterministic alternative group allocation when available.
 */
public class HeadlessPortConflictPrompt implements PortConflictPrompt {

    private final boolean autoAllocateAlternative;

    public HeadlessPortConflictPrompt() {
        this(true);
    }

    public HeadlessPortConflictPrompt(boolean autoAllocateAlternative) {
        this.autoAllocateAlternative = autoAllocateAlternative;
    }

    @Override
    public PortConflictAction promptResolution(PortConflictReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=======================================================\n");
        sb.append("   TRYST HEADLESS PORT CONFLICT DETECTED\n");
        sb.append("=======================================================\n");
        for (PortConflictReport.PortConflictItem item : report.conflicts()) {
            sb.append(String.format("  - Service '%s' assigned port %d is occupied.\n", item.serviceId(), item.port()));
            if (item.processInfo() != null && item.processInfo().pid() > 0) {
                sb.append(String.format("    Occupying process: PID %d (%s) command: %s\n",
                        item.processInfo().pid(), item.processInfo().name(), item.processInfo().commandLine()));
            } else {
                sb.append("    Occupying process could not be identified.\n");
            }
        }

        if (report.alternativeGroup() != null) {
            int altHttp = report.alternativeGroup().getPort(ServicePortIdentifier.HTTP_SERVICE);
            int altTcp = report.alternativeGroup().getPort(ServicePortIdentifier.TCP_SERVICE);
            sb.append(String.format("  Deterministic coherent alternative group available (offset +%d):\n", report.alternativeGroup().groupOffset()));
            sb.append(String.format("    HTTP: %d, TCP: %d\n", altHttp, altTcp));
        } else {
            sb.append("  No alternative port group found within configured search range.\n");
        }
        sb.append("=======================================================\n");

        TrystLog.warn(HeadlessPortConflictPrompt.class, sb.toString());

        if (autoAllocateAlternative && report.alternativeGroup() != null) {
            TrystLog.info(HeadlessPortConflictPrompt.class, "Headless mode: Automatically adopting coherent alternative port group (offset +"
                    + report.alternativeGroup().groupOffset() + ").");
            return PortConflictAction.ALLOCATE_ALTERNATIVE;
        }

        return PortConflictAction.ABORT;
    }
}
