package eu.kalafatic.tryst.ui.view;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.*;
import eu.kalafatic.tryst.domain.repository.*;
import eu.kalafatic.tryst.ui.TrystUiServices;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.part.ViewPart;

import java.util.List;
import java.util.Optional;

/**
 * Granular Session Detail Administration View.
 * Enables administrators to inspect an individual session's complete operational context:
 * session identity, bound project/workspace, tasks, AI model, code reviews, and process state.
 */
public class SessionDetailView extends ViewPart {

    public static final String ID = "eu.kalafatic.tryst.ui.view.sessionDetail";

    private ComboViewer sessionCombo;
    private Text txtSessionId;
    private Text txtProject;
    private Text txtStatus;
    private Text txtCreatedAt;
    private Text txtTasks;
    private Text txtDetails;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(2, false));

        Label lblSelect = new Label(parent, SWT.NONE);
        lblSelect.setText("Select Session:");

        sessionCombo = new ComboViewer(parent, SWT.DROP_DOWN | SWT.READ_ONLY);
        sessionCombo.getCombo().setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sessionCombo.setContentProvider(ArrayContentProvider.getInstance());
        sessionCombo.setLabelProvider(new LabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof Session s) {
                    return s.getName() + " (" + s.getId().substring(0, Math.min(8, s.getId().length())) + ") - " + s.getStatus();
                }
                return super.getText(element);
            }
        });

        sessionCombo.addSelectionChangedListener(event -> {
            StructuredSelection sel = (StructuredSelection) event.getSelection();
            if (!sel.isEmpty() && sel.getFirstElement() instanceof Session s) {
                displaySession(s);
            }
        });

        createLabel(parent, "Session ID:");
        txtSessionId = createReadOnlyText(parent);

        createLabel(parent, "Project ID / Path:");
        txtProject = createReadOnlyText(parent);

        createLabel(parent, "Status:");
        txtStatus = createReadOnlyText(parent);

        createLabel(parent, "Created At:");
        txtCreatedAt = createReadOnlyText(parent);

        createLabel(parent, "Related Tasks:");
        txtTasks = createReadOnlyText(parent);

        Label lblDetails = new Label(parent, SWT.NONE);
        lblDetails.setText("Operational Context & Diagnostics:");
        lblDetails.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));

        txtDetails = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.heightHint = 200;
        txtDetails.setLayoutData(gd);

        Button btnRefresh = new Button(parent, SWT.PUSH);
        btnRefresh.setText("Refresh Sessions");
        GridData gdBtn = new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1);
        btnRefresh.setLayoutData(gdBtn);
        btnRefresh.addListener(SWT.Selection, e -> refreshSessions());

        refreshSessions();
    }

    private void createLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
    }

    private Text createReadOnlyText(Composite parent) {
        Text t = new Text(parent, SWT.BORDER | SWT.READ_ONLY);
        t.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return t;
    }

    private void refreshSessions() {
        SessionRepository sRepo = TrystUiServices.getSessionRepository();
        if (sRepo != null) {
            List<Session> sessions = sRepo.findAll();
            sessionCombo.setInput(sessions);
            if (!sessions.isEmpty()) {
                sessionCombo.setSelection(new StructuredSelection(sessions.get(0)));
            }
        }
    }

    private void displaySession(Session s) {
        txtSessionId.setText(s.getId());
        txtProject.setText(s.getProjectId());
        txtStatus.setText(s.getStatus());
        txtCreatedAt.setText(s.getCreatedAt().toString());

        TaskRepository tRepo = TrystUiServices.getTaskRepository();
        StringBuilder sb = new StringBuilder();
        if (tRepo != null) {
            List<AgentTask> tasks = tRepo.findBySessionId(s.getId());
            txtTasks.setText(tasks.size() + " task(s) registered");

            sb.append("=== Session Operational Hierarchy ===\n");
            sb.append("Session ID: ").append(s.getId()).append("\n");
            sb.append("Name: ").append(s.getName()).append("\n");
            sb.append("Status: ").append(s.getStatus()).append("\n");
            sb.append("Created: ").append(s.getCreatedAt()).append("\n");
            sb.append("Updated: ").append(s.getUpdatedAt()).append("\n\n");

            sb.append("--- Associated Tasks ---\n");
            for (AgentTask t : tasks) {
                sb.append(" • Task: ").append(t.getTitle())
                  .append(" [Status: ").append(t.getTaskStatus())
                  .append(", Priority: ").append(t.getPriority())
                  .append("]\n");
            }
        } else {
            txtTasks.setText("0");
            sb.append("TaskRepository unavailable in OSGi registry.");
        }

        txtDetails.setText(sb.toString());
    }

    @Override
    public void setFocus() {
        if (sessionCombo != null && !sessionCombo.getControl().isDisposed()) {
            sessionCombo.getControl().setFocus();
        }
    }
}
