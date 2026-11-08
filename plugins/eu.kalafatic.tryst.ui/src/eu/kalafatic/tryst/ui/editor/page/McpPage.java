package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.ui.editor.AbstractTrystEditorPage;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;

import java.util.List;

/**
 * Editor page for Model Context Protocol (MCP) resources, tools, and endpoints.
 */
public class McpPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.mcp";

    private TableViewer toolViewer;
    private Label lblEndpoint;

    public McpPage(TrystWorkbenchEditor editor) {
        super(ID, "MCP", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        Composite topBar = new Composite(parent, SWT.NONE);
        topBar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        topBar.setLayout(new GridLayout(2, false));

        Label lbl = new Label(topBar, SWT.NONE);
        lbl.setText("MCP Endpoint:");

        lblEndpoint = new Label(topBar, SWT.BORDER);
        lblEndpoint.setText(resolveMcpEndpoint());
        lblEndpoint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label listLbl = new Label(parent, SWT.NONE);
        listLbl.setText("Registered Scoped MCP Tools:");

        toolViewer = new TableViewer(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        Table table = toolViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumn("Tool Name", 200);
        createColumn("Description", 450);
        createColumn("Access Scope", 120);

        toolViewer.setContentProvider(ArrayContentProvider.getInstance());
        toolViewer.setLabelProvider(new McpToolLabelProvider());

        Button btnRefresh = new Button(parent, SWT.PUSH);
        btnRefresh.setText("Refresh Tools");
        btnRefresh.addListener(SWT.Selection, e -> refresh());

        refresh();
    }

    private void createColumn(String title, int width) {
        TableColumn col = new TableColumn(toolViewer.getTable(), SWT.LEFT);
        col.setText(title);
        col.setWidth(width);
    }

    @Override
    public void onActivated() {
        refresh();
    }

    private String resolveMcpEndpoint() {
        var config = eu.kalafatic.tryst.ui.TrystUiServices.getConfiguration();
        var http = eu.kalafatic.tryst.ui.TrystUiServices.getHttpService();
        if (config != null && http != null && http.isRunning()) {
            return config.resolveEffectiveExternalUrl(http.getPort()) + "/api/mcp (JSON-RPC 2.0)";
        }
        return "http://127.0.0.1:38080/tryst/api/mcp (JSON-RPC 2.0)";
    }

    @Override
    public void refresh() {
        if (lblEndpoint != null && !lblEndpoint.isDisposed()) {
            lblEndpoint.setText(resolveMcpEndpoint());
        }
        if (toolViewer == null || toolViewer.getControl().isDisposed()) return;
        List<McpToolEntry> defaultTools = List.of(
                new McpToolEntry("get_project_metadata", "Retrieves root project and git metadata", "READ_ONLY"),
                new McpToolEntry("list_workspace_files", "Lists workspace directory tree within sandbox boundaries", "SANDBOX_READ"),
                new McpToolEntry("read_workspace_file", "Reads file contents strictly confined to workspace sandbox", "SANDBOX_READ"),
                new McpToolEntry("search_workspace_code", "Performs bounded pattern search across workspace source files", "SANDBOX_READ"),
                new McpToolEntry("get_task_context", "Retrieves complete task instructions, criteria, and context", "TASK_READ"),
                new McpToolEntry("propose_patch", "Applies structured git diff patch to isolated task workspace", "SANDBOX_WRITE"),
                new McpToolEntry("run_build_and_test", "Triggers isolated build and test execution for current task", "BUILD_EXECUTE")
        );
        toolViewer.setInput(defaultTools);
    }

    public record McpToolEntry(String name, String description, String scope) {}

    private static class McpToolLabelProvider extends LabelProvider implements ITableLabelProvider {
        @Override
        public Image getColumnImage(Object element, int columnIndex) {
            return null;
        }

        @Override
        public String getColumnText(Object element, int columnIndex) {
            if (element instanceof McpToolEntry entry) {
                return switch (columnIndex) {
                    case 0 -> entry.name();
                    case 1 -> entry.description();
                    case 2 -> entry.scope();
                    default -> "";
                };
            }
            return "";
        }
    }
}
