package eu.kalafatic.tryst.ui.view;

import eu.kalafatic.tryst.domain.model.ConsoleMessage;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.controller.ConsoleOutputController;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.part.ViewPart;

import java.time.format.DateTimeFormatter;

/**
 * Bottom view presenting console messages, output and status logs.
 * Delegates log management and updates to ConsoleOutputController.
 */
public class ConsoleOutputView extends ViewPart {

    public static final String ID = "eu.kalafatic.tryst.ui.view.consoleOutput";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_LOCAL_TIME;

    private ConsoleOutputController controller;
    private StyledText textWidget;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        textWidget = new StyledText(parent, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        textWidget.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        this.controller = new ConsoleOutputController(TrystUiServices.getWorkspaceService());
        this.controller.setViewCallback(this::appendMessage);

        for (ConsoleMessage msg : controller.getMessages()) {
            appendMessage(msg);
        }

        createActions();
    }

    private void appendMessage(ConsoleMessage msg) {
        if (textWidget == null || textWidget.isDisposed()) {
            return;
        }
        Display display = textWidget.getDisplay();
        if (!display.isDisposed()) {
            display.asyncExec(() -> {
                if (!textWidget.isDisposed()) {
                    String timeStr = msg.timestamp().toString();
                    String line = String.format("[%s] [%s] [%s] %s%n", timeStr, msg.level(), msg.source(), msg.message());
                    textWidget.append(line);
                    textWidget.setTopIndex(textWidget.getLineCount() - 1);
                }
            });
        }
    }

    private void createActions() {
        Action clearAction = new Action("Clear Console") {
            @Override
            public void run() {
                if (!textWidget.isDisposed()) {
                    textWidget.setText("");
                }
            }
        };

        Action refreshAction = new Action("Refresh Console Messages") {
            @Override
            public void run() {
                if (!textWidget.isDisposed()) {
                    textWidget.setText("");
                    for (ConsoleMessage msg : controller.getMessages()) {
                        appendMessage(msg);
                    }
                }
            }
        };

        IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();
        toolbar.add(refreshAction);
        toolbar.add(clearAction);

        org.eclipse.jface.action.IMenuManager menu = getViewSite().getActionBars().getMenuManager();
        menu.add(refreshAction);
        menu.add(clearAction);

        org.eclipse.jface.action.MenuManager menuMgr = new org.eclipse.jface.action.MenuManager("#ConsolePopupMenu");
        menuMgr.setRemoveAllWhenShown(true);
        menuMgr.addMenuListener(m -> {
            m.add(refreshAction);
            m.add(clearAction);
            m.add(new org.eclipse.jface.action.Separator(org.eclipse.ui.IWorkbenchActionConstants.MB_ADDITIONS));
        });
        textWidget.setMenu(menuMgr.createContextMenu(textWidget));
    }

    @Override
    public void setFocus() {
        if (textWidget != null && !textWidget.isDisposed()) {
            textWidget.setFocus();
        }
    }

    @Override
    public void dispose() {
        if (controller != null) {
            controller.dispose();
        }
        super.dispose();
    }
}
