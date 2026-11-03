package eu.kalafatic.tryst.domain.ai;

import eu.kalafatic.tryst.domain.model.ConversationMessage;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Prompt Context Builder constructing token-bounded, sanitized prompts
 * strictly confined to authorized workspace resources.
 */
public class PromptContextBuilder {

    private final int maxTokens;

    public PromptContextBuilder() {
        this(4096);
    }

    public PromptContextBuilder(int maxTokens) {
        this.maxTokens = maxTokens > 0 ? maxTokens : 4096;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public String buildSystemInstructions(Workspace workspace, Project activeProject, String toolHelp) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are TRYST AI Developer, an autonomous software development agent operating within Eclipse RCP.\n");
        sb.append("Your duty is to systematically analyze requests, inspect repository structure, plan changes, ");
        sb.append("execute file modifications safely, compile, and run tests.\n");
        sb.append("Strict boundaries:\n");
        sb.append("1. Only access and modify files within the authorized workspace.\n");
        sb.append("2. Output structured tool calls when performing operations.\n");
        if (workspace != null) {
            sb.append("Authorized Workspace: ").append(workspace.getRootPath()).append("\n");
        }
        if (activeProject != null) {
            sb.append("Active Project: ").append(activeProject.getName()).append(" (")
              .append(activeProject.getLocation()).append(")\n");
        }
        if (toolHelp != null && !toolHelp.isBlank()) {
            sb.append("\n").append(toolHelp).append("\n");
        }
        return sb.toString();
    }

    public List<AIProvider.ChatMessage> assembleContext(
            String systemInstructions,
            List<ConversationMessage> history,
            String taskDescription,
            Workspace workspace,
            List<Path> relevantFiles
    ) {
        List<AIProvider.ChatMessage> messages = new ArrayList<>();
        if (systemInstructions != null && !systemInstructions.isBlank()) {
            messages.add(AIProvider.ChatMessage.system(systemInstructions));
        }

        if (workspace != null && Files.exists(workspace.getRootPath())) {
            String structure = summarizeRepositoryStructure(workspace.getRootPath(), 30);
            if (!structure.isBlank()) {
                messages.add(AIProvider.ChatMessage.system("Repository Structure:\n" + structure));
            }
        }

        if (workspace != null && relevantFiles != null && !relevantFiles.isEmpty()) {
            StringBuilder fileContext = new StringBuilder("Relevant Source Files:\n");
            for (Path relPath : relevantFiles) {
                Path fullPath = workspace.getRootPath().resolve(relPath).normalize();
                if (Files.exists(fullPath) && !Files.isDirectory(fullPath)) {
                    try {
                        String content = Files.readString(fullPath);
                        if (content.length() > 2000) {
                            content = content.substring(0, 2000) + "\n...[truncated]";
                        }
                        fileContext.append("--- File: ").append(relPath).append(" ---\n")
                                   .append(content).append("\n\n");
                    } catch (IOException ignored) {}
                }
            }
            messages.add(AIProvider.ChatMessage.system(fileContext.toString()));
        }

        if (history != null && !history.isEmpty()) {
            int charBudget = maxTokens * 3;
            List<AIProvider.ChatMessage> historyMessages = new ArrayList<>();
            int accumulatedChars = 0;
            for (int i = history.size() - 1; i >= 0; i--) {
                ConversationMessage msg = history.get(i);
                if (accumulatedChars + msg.getContent().length() > charBudget) {
                    break;
                }
                accumulatedChars += msg.getContent().length();
                historyMessages.add(0, new AIProvider.ChatMessage(msg.getRole().toLowerCase(), msg.getContent()));
            }
            messages.addAll(historyMessages);
        }

        if (taskDescription != null && !taskDescription.isBlank()) {
            messages.add(AIProvider.ChatMessage.user(taskDescription));
        }

        return messages;
    }

    public String summarizeRepositoryStructure(Path root, int maxEntries) {
        try (Stream<Path> stream = Files.walk(root, 3)) {
            List<String> entries = stream
                    .filter(p -> !p.equals(root))
                    .filter(p -> !p.toString().contains("/.") && !p.toString().contains("/target/"))
                    .limit(maxEntries)
                    .map(p -> root.relativize(p).toString())
                    .toList();
            return String.join("\n", entries);
        } catch (IOException e) {
            return "";
        }
    }
}
