package eu.kalafatic.tryst.ui.view;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.User;
import eu.kalafatic.tryst.domain.model.UserSession;
import eu.kalafatic.tryst.domain.repository.UserRepository;
import eu.kalafatic.tryst.domain.repository.UserSessionRepository;
import eu.kalafatic.tryst.ui.TrystUiServices;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.part.ViewPart;

import java.util.Collections;

/**
 * Native SWT/JFace View for User and Session Administration.
 * Resolves repositories directly via authoritative OSGi service infrastructure.
 */
public class UsersAndSessionsView extends ViewPart {

    public static final String ID = "eu.kalafatic.tryst.ui.view.usersAndSessions";

    private TableViewer userViewer;
    private TableViewer sessionViewer;
    private Label statusLabel;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        statusLabel = new Label(parent, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        statusLabel.setText("");

        Label lblUsers = new Label(parent, SWT.NONE);
        lblUsers.setText("Active Users & Roles (RBAC):");

        userViewer = new TableViewer(parent, SWT.BORDER | SWT.FULL_SELECTION);
        Table userTable = userViewer.getTable();
        userTable.setHeaderVisible(true);
        userTable.setLinesVisible(true);
        userTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumn(userViewer, "Username", 120);
        createColumn(userViewer, "Role", 100);
        createColumn(userViewer, "Active", 70);
        createColumn(userViewer, "Locked", 70);

        userViewer.setContentProvider(ArrayContentProvider.getInstance());
        userViewer.setLabelProvider(new ITableLabelProvider() {
            @Override public org.eclipse.swt.graphics.Image getColumnImage(Object element, int columnIndex) { return null; }
            @Override public String getColumnText(Object element, int columnIndex) {
                if (element instanceof User u) {
                    return switch (columnIndex) {
                        case 0 -> u.getUsername();
                        case 1 -> u.getRole().name();
                        case 2 -> String.valueOf(u.isActive());
                        case 3 -> String.valueOf(u.isLocked());
                        default -> "";
                    };
                }
                return "";
            }
            @Override public void addListener(ILabelProviderListener listener) {}
            @Override public void dispose() {}
            @Override public boolean isLabelProperty(Object element, String property) { return false; }
            @Override public void removeListener(ILabelProviderListener listener) {}
        });

        Label lblSessions = new Label(parent, SWT.NONE);
        lblSessions.setText("Active Sessions:");

        sessionViewer = new TableViewer(parent, SWT.BORDER | SWT.FULL_SELECTION);
        Table sessionTable = sessionViewer.getTable();
        sessionTable.setHeaderVisible(true);
        sessionTable.setLinesVisible(true);
        sessionTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumn(sessionViewer, "User ID", 120);
        createColumn(sessionViewer, "Client IP", 120);
        createColumn(sessionViewer, "Expires At", 160);
        createColumn(sessionViewer, "Revoked", 80);

        sessionViewer.setContentProvider(ArrayContentProvider.getInstance());
        sessionViewer.setLabelProvider(new ITableLabelProvider() {
            @Override public org.eclipse.swt.graphics.Image getColumnImage(Object element, int columnIndex) { return null; }
            @Override public String getColumnText(Object element, int columnIndex) {
                if (element instanceof UserSession s) {
                    return switch (columnIndex) {
                        case 0 -> s.getUserId().substring(0, Math.min(8, s.getUserId().length()));
                        case 1 -> s.getClientIp();
                        case 2 -> s.getExpiresAt().toString();
                        case 3 -> String.valueOf(s.isRevoked());
                        default -> "";
                    };
                }
                return "";
            }
            @Override public void addListener(ILabelProviderListener listener) {}
            @Override public void dispose() {}
            @Override public boolean isLabelProperty(Object element, String property) { return false; }
            @Override public void removeListener(ILabelProviderListener listener) {}
        });

        Composite btnRow = new Composite(parent, SWT.NONE);
        btnRow.setLayout(new GridLayout(2, false));
        btnRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button btnViewDetail = new Button(btnRow, SWT.PUSH);
        btnViewDetail.setText("Inspect Selected User Detail");
        btnViewDetail.addListener(SWT.Selection, e -> openSelectedUserDetail());

        Button btnRefresh = new Button(btnRow, SWT.PUSH);
        btnRefresh.setText("Refresh Users & Sessions");
        btnRefresh.addListener(SWT.Selection, e -> refreshData());

        userViewer.addDoubleClickListener(event -> openSelectedUserDetail());

        refreshData();
    }

    private void createColumn(TableViewer viewer, String title, int width) {
        TableViewerColumn col = new TableViewerColumn(viewer, SWT.NONE);
        col.getColumn().setText(title);
        col.getColumn().setWidth(width);
    }

    private void refreshData() {
        UserRepository uRepo = TrystUiServices.getUserRepository();
        UserSessionRepository sRepo = TrystUiServices.getUserSessionRepository();

        if (uRepo == null || sRepo == null) {
            String msg = "Security repositories are not currently available in OSGi registry.";
            statusLabel.setText(msg);
            TrystLog.warn(UsersAndSessionsView.class, msg);
            if (uRepo == null) userViewer.setInput(Collections.emptyList());
            if (sRepo == null) sessionViewer.setInput(Collections.emptyList());
            return;
        }

        statusLabel.setText("");
        userViewer.setInput(uRepo.findAll());
        sessionViewer.setInput(sRepo.findAllActive());
    }

    private void openSelectedUserDetail() {
        IStructuredSelection selection = (IStructuredSelection) userViewer.getSelection();
        if (selection.isEmpty() || !(selection.getFirstElement() instanceof User selectedUser)) {
            statusLabel.setText("Please select a user from the table to inspect details.");
            return;
        }

        try {
            org.eclipse.ui.IWorkbenchPage page = getSite().getPage();
            if (page != null) {
                org.eclipse.ui.IViewPart view = page.showView(UserDetailView.ID);
                if (view instanceof UserDetailView userDetailView) {
                    userDetailView.displayUser(selectedUser);
                }
            }
        } catch (Exception e) {
            TrystLog.error(UsersAndSessionsView.class, "Failed to open UserDetailView: " + e.getMessage(), e);
            statusLabel.setText("Failed to open User Detail view: " + e.getMessage());
        }
    }

    @Override
    public void setFocus() {
        if (userViewer != null && !userViewer.getControl().isDisposed()) {
            userViewer.getControl().setFocus();
        }
    }

    @Override
    public void dispose() {
        super.dispose();
    }
}
