package eu.kalafatic.tryst.ui.controller;

import eu.kalafatic.tryst.domain.model.ConsoleMessage;
import eu.kalafatic.tryst.domain.service.WorkspaceChangeListener;
import eu.kalafatic.tryst.domain.service.WorkspaceService;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Controller coordinating console output, status line messages, and log updates.
 */
public class ConsoleOutputController implements WorkspaceChangeListener {

    private final WorkspaceService workspaceService;
    private Consumer<ConsoleMessage> messageUpdateConsumer;

    public ConsoleOutputController(WorkspaceService workspaceService) {
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService must not be null");
        this.workspaceService.addChangeListener(this);
    }

    public void setViewCallback(Consumer<ConsoleMessage> messageUpdateConsumer) {
        this.messageUpdateConsumer = messageUpdateConsumer;
    }

    public List<ConsoleMessage> getMessages() {
        return workspaceService.getConsoleMessages();
    }

    public void log(String level, String source, String message) {
        ConsoleMessage msg = switch (level.toUpperCase()) {
            case "WARN" -> ConsoleMessage.warn(source, message);
            case "ERROR" -> ConsoleMessage.error(source, message);
            default -> ConsoleMessage.info(source, message);
        };
        workspaceService.logMessage(msg);
    }

    @Override
    public void onMessageLogged(ConsoleMessage message) {
        if (messageUpdateConsumer != null) {
            messageUpdateConsumer.accept(message);
        }
    }

    public void dispose() {
        workspaceService.removeChangeListener(this);
        this.messageUpdateConsumer = null;
    }
}
