package eu.kalafatic.tryst.ui.controller;

import eu.kalafatic.tryst.core.log.LogEntry;
import eu.kalafatic.tryst.core.log.LogLevel;
import eu.kalafatic.tryst.core.log.LogListener;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.ConsoleMessage;
import eu.kalafatic.tryst.domain.service.WorkspaceChangeListener;
import eu.kalafatic.tryst.domain.service.WorkspaceService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Controller coordinating centralized console output, severity filtering,
 * real-time search, pause/resume streaming, and view updates.
 */
public class ConsoleOutputController implements WorkspaceChangeListener, LogListener {

    private final WorkspaceService workspaceService;
    private Consumer<LogEntry> logUpdateConsumer;
    private Consumer<ConsoleMessage> legacyUpdateConsumer;

    private final List<LogEntry> capturedEntries = new CopyOnWriteArrayList<>();
    private final AtomicBoolean paused = new AtomicBoolean(false);

    private volatile LogLevel filterLevel = null; // null means ALL
    private volatile String filterCategory = null;
    private volatile String searchText = null;

    public ConsoleOutputController(WorkspaceService workspaceService) {
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService must not be null");
        this.workspaceService.addChangeListener(this);

        // Preload recent entries from TrystLog circular buffer
        List<LogEntry> existing = TrystLog.getRecentEntries();
        this.capturedEntries.addAll(existing);

        // Register as live log listener
        TrystLog.addListener(this);
    }

    public void setViewCallback(Consumer<ConsoleMessage> legacyUpdateConsumer) {
        this.legacyUpdateConsumer = legacyUpdateConsumer;
    }

    public void setLogEntryCallback(Consumer<LogEntry> logUpdateConsumer) {
        this.logUpdateConsumer = logUpdateConsumer;
    }

    @Override
    public void onLogEntry(LogEntry entry) {
        if (entry == null) return;
        capturedEntries.add(entry);

        if (paused.get()) {
            return;
        }

        if (matchesFilter(entry) && logUpdateConsumer != null) {
            logUpdateConsumer.accept(entry);
        }
    }

    @Override
    public void onMessageLogged(ConsoleMessage message) {
        if (legacyUpdateConsumer != null) {
            legacyUpdateConsumer.accept(message);
        }
    }

    public boolean matchesFilter(LogEntry entry) {
        if (entry == null) return false;

        // Severity filter
        if (filterLevel != null && entry.level().getLevel() < filterLevel.getLevel()) {
            return false;
        }

        // Category/Module filter
        if (filterCategory != null && !filterCategory.isBlank()) {
            String cat = filterCategory.trim().toLowerCase();
            boolean matchCat = (entry.category() != null && entry.category().toLowerCase().contains(cat)) ||
                               (entry.bundleId() != null && entry.bundleId().toLowerCase().contains(cat));
            if (!matchCat) return false;
        }

        // Substring search
        if (searchText != null && !searchText.isBlank()) {
            String q = searchText.trim().toLowerCase();
            boolean matchMsg = entry.message() != null && entry.message().toLowerCase().contains(q);
            boolean matchCorr = entry.correlationId() != null && entry.correlationId().toLowerCase().contains(q);
            boolean matchTask = entry.taskId() != null && entry.taskId().toLowerCase().contains(q);
            boolean matchTrace = entry.formattedStackTrace() != null && entry.formattedStackTrace().toLowerCase().contains(q);
            if (!matchMsg && !matchCorr && !matchTask && !matchTrace) {
                return false;
            }
        }

        return true;
    }

    public List<LogEntry> getFilteredEntries() {
        List<LogEntry> result = new ArrayList<>();
        for (LogEntry entry : capturedEntries) {
            if (matchesFilter(entry)) {
                result.add(entry);
            }
        }
        return result;
    }

    public List<LogEntry> getAllEntries() {
        return new ArrayList<>(capturedEntries);
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

        // Also emit into centralized log facility
        LogLevel lvl = LogLevel.fromString(level);
        TrystLog.log(lvl, ConsoleOutputController.class, "[" + source + "] " + message, null);
    }

    public void setFilterLevel(LogLevel level) {
        this.filterLevel = level;
    }

    public LogLevel getFilterLevel() {
        return filterLevel;
    }

    public void setFilterCategory(String category) {
        this.filterCategory = category;
    }

    public String getFilterCategory() {
        return filterCategory;
    }

    public void setSearchText(String search) {
        this.searchText = search;
    }

    public String getSearchText() {
        return searchText;
    }

    public boolean isPaused() {
        return paused.get();
    }

    public void setPaused(boolean pause) {
        this.paused.set(pause);
    }

    public void togglePause() {
        this.paused.set(!this.paused.get());
    }

    public void clearVisible() {
        capturedEntries.clear();
    }

    public int getTotalCapturedCount() {
        return capturedEntries.size();
    }

    public void dispose() {
        TrystLog.removeListener(this);
        workspaceService.removeChangeListener(this);
        this.logUpdateConsumer = null;
        this.legacyUpdateConsumer = null;
    }
}
