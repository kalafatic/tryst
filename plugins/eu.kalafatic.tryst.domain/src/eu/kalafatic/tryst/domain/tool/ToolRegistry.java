package eu.kalafatic.tryst.domain.tool;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of available tools for agent discovery and execution.
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    public void register(Tool tool) {
        if (tool != null) {
            tools.put(tool.getName(), tool);
        }
    }

    public void unregister(String toolName) {
        if (toolName != null) {
            tools.remove(toolName);
        }
    }

    public Optional<Tool> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Collection<Tool> getAvailableTools() {
        return Collections.unmodifiableCollection(tools.values());
    }

    public String generateToolHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append("Available tools:\n");
        for (Tool tool : tools.values()) {
            sb.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
        }
        return sb.toString();
    }
}
