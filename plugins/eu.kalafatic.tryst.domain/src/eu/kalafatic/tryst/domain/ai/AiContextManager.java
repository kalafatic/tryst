package eu.kalafatic.tryst.domain.ai;

import eu.kalafatic.tryst.domain.model.ConversationMessage;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Foundation service assembling prioritized, token-bounded context for LLM prompts.
 * Respects workspace boundaries and limits file inclusions.
 */
public class AiContextManager {

    private final int maxTokens;

    public AiContextManager() {
        this(4096);
    }

    public AiContextManager(int maxTokens) {
        this.maxTokens = maxTokens > 0 ? maxTokens : 4096;
    }

    public String buildSystemInstructions(Workspace workspace, Project activeProject) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are TRYST, an autonomous software development agent operating within an Eclipse RCP application.\n");
        sb.append("You assist the user by planning, modifying files, running builds and tests, and diagnosing code.\n");
        sb.append("Strict rules:\n");
        sb.append("1. Keep changes bounded and correct.\n");
        sb.append("2. When tools are available, suggest concrete actions formatted as valid commands or tool calls.\n");
        if (workspace != null) {
            sb.append("Authorized Workspace: ").append(workspace.getRootPath()).append("\n");
        }
        if (activeProject != null) {
            sb.append("Active Project: ").append(activeProject.getName()).append(" (")
              .append(activeProject.getLocation()).append(")\n");
        }
        return sb.toString();
    }

    public List<AiInferenceService.ChatMessage> assembleConversationContext(
            String systemInstructions,
            List<ConversationMessage> history,
            String newUserPrompt,
            Project project
    ) {
        List<AiInferenceService.ChatMessage> messages = new ArrayList<>();
        if (systemInstructions != null && !systemInstructions.isBlank()) {
            messages.add(AiInferenceService.ChatMessage.system(systemInstructions));
        }

        // Project summary context if project is present
        if (project != null && Files.exists(project.getLocation())) {
            String projectOverview = summarizeProjectStructure(project.getLocation(), 30);
            if (!projectOverview.isBlank()) {
                messages.add(AiInferenceService.ChatMessage.system("Project Structure:\n" + projectOverview));
            }
        }

        if (history != null) {
            // Keep recent messages within budget (approx. 4 chars per token)
            int charBudget = maxTokens * 3;
            List<AiInferenceService.ChatMessage> historyMessages = new ArrayList<>();
            int accumulatedChars = 0;
            for (int i = history.size() - 1; i >= 0; i--) {
                ConversationMessage msg = history.get(i);
                if (accumulatedChars + msg.getContent().length() > charBudget) {
                    break;
                }
                accumulatedChars += msg.getContent().length();
                historyMessages.add(0, new AiInferenceService.ChatMessage(msg.getRole().toLowerCase(), msg.getContent()));
            }
            messages.addAll(historyMessages);
        }

        if (newUserPrompt != null && !newUserPrompt.isBlank()) {
            messages.add(AiInferenceService.ChatMessage.user(newUserPrompt));
        }

        return messages;
    }

    private String summarizeProjectStructure(Path root, int maxEntries) {
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
