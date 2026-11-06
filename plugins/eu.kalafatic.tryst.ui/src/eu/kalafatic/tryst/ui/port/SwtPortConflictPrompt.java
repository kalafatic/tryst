package eu.kalafatic.tryst.ui.port;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.core.port.PortConflictAction;
import eu.kalafatic.tryst.core.port.PortConflictPrompt;
import eu.kalafatic.tryst.core.port.PortConflictReport;
import eu.kalafatic.tryst.core.port.ServicePortIdentifier;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Interactive RCP UI prompt for port collision resolution.
 * Displays details of conflicting services, occupied ports, occupying process PID and command,
 * available coherent alternative port group, and requests explicit user authorization
 * before terminating any process.
 */
public class SwtPortConflictPrompt implements PortConflictPrompt {

    @Override
    public PortConflictAction promptResolution(PortConflictReport report) {
        AtomicReference<PortConflictAction> result = new AtomicReference<>(PortConflictAction.ABORT);

        Display curDisplay = Display.getCurrent();
        if (curDisplay == null) {
            curDisplay = Display.getDefault();
        }

        if (curDisplay == null || curDisplay.isDisposed()) {
            TrystLog.warn(SwtPortConflictPrompt.class, "SWT Display unavailable; falling back to alternative group allocation.");
            return (report.alternativeGroup() != null) ? PortConflictAction.ALLOCATE_ALTERNATIVE : PortConflictAction.ABORT;
        }

        final Display display = curDisplay;
        display.syncExec(() -> {
            Shell shell = display.getActiveShell();
            if (shell == null) {
                Shell[] shells = display.getShells();
                if (shells.length > 0) {
                    shell = shells[0];
                }
            }

            StringBuilder msg = new StringBuilder();
            msg.append("TRYST has detected port collisions while initializing network listeners:\n\n");
            for (PortConflictReport.PortConflictItem item : report.conflicts()) {
                msg.append(String.format("• Service '%s' on port %d is occupied.\n", item.serviceId(), item.port()));
                if (item.processInfo() != null && item.processInfo().pid() > 0) {
                    msg.append(String.format("   Process: PID %d (%s)\n   Command: %s\n",
                            item.processInfo().pid(), item.processInfo().name(), item.processInfo().commandLine()));
                } else {
                    msg.append("   Occupying process: Unknown / system socket\n");
                }
            }
            msg.append("\n");

            if (report.alternativeGroup() != null) {
                int altHttp = report.alternativeGroup().getPort(ServicePortIdentifier.HTTP_SERVICE);
                int altTcp = report.alternativeGroup().getPort(ServicePortIdentifier.TCP_SERVICE);
                msg.append(String.format("An alternative coherent port group is available (+%d offset):\n", report.alternativeGroup().groupOffset()));
                msg.append(String.format("   HTTP: %d, TCP: %d\n\n", altHttp, altTcp));
            } else {
                msg.append("No alternative port group is available within the search range.\n\n");
            }

            msg.append("Choose an action:\n");
            msg.append("• 'Allocate Alternative' to shift the coherent port group.\n");
            msg.append("• 'Free Port' to terminate the occupying process (requires confirmation).\n");
            msg.append("• 'Abort' to cancel startup.");

            String[] buttons = new String[] {
                    "Allocate Alternative",
                    "Free Port",
                    "Abort"
            };

            MessageDialog dialog = new MessageDialog(
                    shell,
                    "TRYST – Port Conflict Detected",
                    null,
                    msg.toString(),
                    MessageDialog.QUESTION,
                    buttons,
                    0
            );

            int choice = dialog.open();
            switch (choice) {
                case 0 -> result.set(PortConflictAction.ALLOCATE_ALTERNATIVE);
                case 1 -> {
                    // Explicit confirmation for terminating process
                    boolean confirmKill = MessageDialog.openConfirm(
                            shell,
                            "Confirm Process Termination",
                            "Are you sure you want to terminate the conflicting process(es)?\n\n" +
                            "This action will send a termination signal to the external process(es)."
                    );
                    if (confirmKill) {
                        result.set(PortConflictAction.FREE_PORT);
                    } else {
                        result.set(PortConflictAction.ABORT);
                    }
                }
                default -> result.set(PortConflictAction.ABORT);
            }
        });

        return result.get();
    }
}
