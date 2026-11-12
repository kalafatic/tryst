package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.core.config.RuntimeEndpointRegistry;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.context.IWorkbenchContextListener;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import eu.kalafatic.tryst.ui.context.WorkbenchContextManager;
import eu.kalafatic.tryst.ui.editor.AbstractTrystEditorPage;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

import java.util.List;

/**
 * TRYST Studio Development Editor page in the central Multipage Editor.
 * Provides real-time interactive development, file diff tracking, and AI collaboration.
 */
public class StudioEditorPage extends AbstractTrystEditorPage implements IWorkbenchContextListener {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.studio";

    private ComboViewer taskSelectorCombo;
    private Browser browser;
    private Label lblStatusInfo;
    private Task activeTask;

    public StudioEditorPage(TrystWorkbenchEditor editor) {
        super(ID, "TRYST Studio", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        Composite controlBar = new Composite(parent, SWT.NONE);
        controlBar.setLayout(new GridLayout(6, false));
        controlBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label lblTarget = new Label(controlBar, SWT.NONE);
        lblTarget.setText("Active Task:");

        taskSelectorCombo = new ComboViewer(controlBar, SWT.READ_ONLY);
        taskSelectorCombo.setContentProvider(ArrayContentProvider.getInstance());
        taskSelectorCombo.setLabelProvider(new LabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof Task t) {
                    return "[" + t.getStatus() + "] " + t.getTitle();
                } else if (element instanceof AgentTask at) {
                    return "[" + at.getStatus() + "] " + at.getTitle();
                }
                return super.getText(element);
            }
        });
        taskSelectorCombo.getCombo().setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        taskSelectorCombo.addSelectionChangedListener(event -> {
            IStructuredSelection sel = (IStructuredSelection) event.getSelection();
            if (!sel.isEmpty()) {
                Object obj = sel.getFirstElement();
                if (obj instanceof Task t) {
                    selectTask(t);
                }
            }
        });

        Button btnRefresh = new Button(controlBar, SWT.PUSH);
        btnRefresh.setText("🔄 Refresh");
        btnRefresh.addListener(SWT.Selection, e -> refresh());

        Button btnOpenBrowser = new Button(controlBar, SWT.PUSH);
        btnOpenBrowser.setText("🌐 Open in External Browser");
        btnOpenBrowser.addListener(SWT.Selection, e -> {
            if (activeTask != null) {
                org.eclipse.swt.program.Program.launch(resolveStudioUrl(activeTask.getId()));
            }
        });

        lblStatusInfo = new Label(controlBar, SWT.NONE);
        lblStatusInfo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        lblStatusInfo.setText("Studio Ready");

        // Center Browser container
        Composite browserContainer = new Composite(parent, SWT.BORDER);
        browserContainer.setLayout(new GridLayout(1, false));
        browserContainer.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        try {
            browser = new Browser(browserContainer, SWT.NONE);
            browser.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        } catch (Throwable t) {
            Label fallback = new Label(browserContainer, SWT.WRAP);
            fallback.setText("Embedded browser unavailable. Use 'Open in External Browser' above to interact with TRYST Studio.\n" + t.getMessage());
            fallback.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        }

        WorkbenchContextManager.getInstance().addContextListener(this);
        refresh();
    }

    public void selectTask(Task task) {
        this.activeTask = task;
        if (task != null) {
            lblStatusInfo.setText("Task: " + task.getId().substring(0, 8) + " (" + task.getStatus() + ")");
            if (browser != null && !browser.isDisposed()) {
                browser.setUrl(resolveStudioUrl(task.getId()));
            }
        }
    }

    private String resolveStudioUrl(String taskId) {
        String base = "http://127.0.0.1:38080/tryst";
        RuntimeEndpointRegistry reg = TrystUiServices.getService(RuntimeEndpointRegistry.class);
        if (reg != null && reg.getCurrentEndpoint() != null) {
            var ep = reg.getCurrentEndpoint();
            if (ep.externalUrl() != null && !ep.externalUrl().isBlank()) base = ep.externalUrl();
            else if (ep.internalUrl() != null && !ep.internalUrl().isBlank()) base = ep.internalUrl();
        }
        return base + "?studio=" + taskId;
    }

    @Override
    public void refresh() {
        var taskRepo = TrystUiServices.getTaskRepository();
        if (taskRepo != null && taskSelectorCombo != null && !taskSelectorCombo.getCombo().isDisposed()) {
            List<Task> tasks = taskRepo.findAllTasks();
            taskSelectorCombo.setInput(tasks);
            if (activeTask != null) {
                for (Task t : tasks) {
                    if (t.getId().equals(activeTask.getId())) {
                        taskSelectorCombo.setSelection(new org.eclipse.jface.viewers.StructuredSelection(t));
                        selectTask(t);
                        return;
                    }
                }
            } else if (!tasks.isEmpty()) {
                taskSelectorCombo.setSelection(new org.eclipse.jface.viewers.StructuredSelection(tasks.get(0)));
                selectTask(tasks.get(0));
            }
        }
    }

    @Override
    public void onContextChanged(WorkbenchContext context) {
        if (context.task() != null && taskSelectorCombo != null && !taskSelectorCombo.getCombo().isDisposed()) {
            String tid = context.task().getId();
            var taskRepo = TrystUiServices.getTaskRepository();
            if (taskRepo != null) {
                taskRepo.findTaskById(tid).ifPresent(t -> {
                    taskSelectorCombo.getCombo().getDisplay().asyncExec(() -> selectTask(t));
                });
            }
        }
    }

    @Override
    public void dispose() {
        WorkbenchContextManager.getInstance().removeContextListener(this);
        super.dispose();
    }
}
