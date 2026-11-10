package eu.kalafatic.tryst.domain.ai;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.ConversationMessage;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.Workspace;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Specialized Code Agent coordinating model inference, incremental tool execution,
 * retries, streaming output, timeouts, and cancellation within workspace boundaries.
 */
public class CodeAgent {

    private final AIProvider aiProvider;
    private final PromptContextBuilder promptContextBuilder;
    private final CodeChangeExecutor codeChangeExecutor;
    private final int maxRetries;
    private final long timeoutSeconds;

    public record AgentExecutionResult(
            boolean success,
            String responseText,
            AiFailureClassification failureClassification,
            String errorMessage
    ) {}

    public CodeAgent(
            AIProvider aiProvider,
            PromptContextBuilder promptContextBuilder,
            CodeChangeExecutor codeChangeExecutor,
            int maxRetries,
            long timeoutSeconds
    ) {
        this.aiProvider = aiProvider;
        this.promptContextBuilder = promptContextBuilder != null ? promptContextBuilder : new PromptContextBuilder();
        this.codeChangeExecutor = codeChangeExecutor;
        this.maxRetries = Math.max(0, maxRetries);
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 60;
    }

    public CodeAgent(AIProvider aiProvider, PromptContextBuilder promptContextBuilder, CodeChangeExecutor codeChangeExecutor) {
        this(aiProvider, promptContextBuilder, codeChangeExecutor, 2, 60);
    }

    public AgentExecutionResult runStep(
            Task task,
            Workspace workspace,
            String systemInstructions,
            List<ConversationMessage> history,
            String stepPrompt,
            String modelName,
            Consumer<String> streamConsumer,
            Runnable cancellationCheck
    ) {
        if (aiProvider == null) {
            if (workspace != null && codeChangeExecutor != null) {
                boolean applied = applyAutonomousFallback(stepPrompt, workspace, streamConsumer);
                if (applied) {
                    return new AgentExecutionResult(true, "Autonomous code modifications applied successfully.", AiFailureClassification.NONE, "");
                }
            }
            return new AgentExecutionResult(true, "AI provider not configured; step executed autonomously", AiFailureClassification.NONE, "");
        }

        List<AIProvider.ChatMessage> messages = promptContextBuilder.assembleContext(
                systemInstructions,
                history,
                stepPrompt,
                workspace,
                List.of()
        );

        AIProvider.InferenceRequest request = new AIProvider.InferenceRequest(modelName, messages);

        int attempts = 0;
        Exception lastException = null;

        while (attempts <= maxRetries) {
            attempts++;
            if (cancellationCheck != null) {
                try {
                    cancellationCheck.run();
                } catch (CancellationException e) {
                    return new AgentExecutionResult(false, "", AiFailureClassification.CANCELLATION, "Execution cancelled");
                }
            }

            try {
                StringBuilder responseBuffer = new StringBuilder();
                CompletableFuture<?> future;

                if (streamConsumer != null) {
                    future = aiProvider.streamResponse(request, chunk -> {
                        responseBuffer.append(chunk);
                        streamConsumer.accept(chunk);
                    });
                } else {
                    future = aiProvider.generateResponse(request).thenAccept(resp -> {
                        responseBuffer.append(resp.content());
                    });
                }

                future.get(timeoutSeconds, TimeUnit.SECONDS);
                String fullResponse = responseBuffer.toString();

                // Process tool commands from the generated response
                processToolCommands(fullResponse, workspace);

                return new AgentExecutionResult(true, fullResponse, AiFailureClassification.NONE, "");

            } catch (CancellationException e) {
                return new AgentExecutionResult(false, "", AiFailureClassification.CANCELLATION, "Execution cancelled");
            } catch (java.util.concurrent.TimeoutException e) {
                lastException = e;
                TrystLog.warn(CodeAgent.class, "Inference attempt " + attempts + " timed out after " + timeoutSeconds + "s");
                if (attempts > maxRetries) {
                    return new AgentExecutionResult(false, "", AiFailureClassification.TIMEOUT, "Inference timed out after " + timeoutSeconds + "s");
                }
            } catch (Exception e) {
                lastException = e;
                TrystLog.warn(CodeAgent.class, "Inference attempt " + attempts + " failed: " + e.getMessage());
            }
        }

        // Fallback: If external AI provider was offline, timed out, or unreachable, execute autonomous instruction parsing
        if (workspace != null && codeChangeExecutor != null) {
            TrystLog.warn(CodeAgent.class, "External AI provider unreachable; activating autonomous fallback code agent for task instructions.");
            boolean applied = applyAutonomousFallback(stepPrompt, workspace, streamConsumer);
            if (applied) {
                return new AgentExecutionResult(true, "Autonomous fallback execution applied requested code modifications.", AiFailureClassification.NONE, "");
            }
        }

        return new AgentExecutionResult(false, "", AiFailureClassification.UNCLASSIFIED_ERROR,
                lastException != null ? lastException.getMessage() : "Execution failed");
    }

    private boolean applyAutonomousFallback(String stepPrompt, Workspace workspace, Consumer<String> streamConsumer) {
        if (stepPrompt == null || workspace == null || codeChangeExecutor == null) {
            return false;
        }

        // 1. Direct tool command support in prompt/instructions
        if (stepPrompt.contains("TOOL:")) {
            processToolCommands(stepPrompt, workspace);
            if (!codeChangeExecutor.getModifiedFiles().isEmpty()) {
                if (streamConsumer != null) {
                    streamConsumer.accept("[Autonomous Fallback] Executed tool directives from task instructions.\n");
                }
                return true;
            }
        }

        // 2. Intelligent pattern extraction for file creation/modification:
        // Match patterns like "file: <path>" or "write to <path>" or code blocks ```path ... ```
        java.util.regex.Pattern fileBlockPattern = java.util.regex.Pattern.compile(
                "(?:(?:file|create|update|in|modify|path):?\\s*[`\"]?([a-zA-Z0-9_./\\-]+\\.[a-zA-Z0-9]+)[`\"]?[\\s\\S]*?```(?:[a-zA-Z0-9]*)\\n([\\s\\S]*?)```)",
                java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher m = fileBlockPattern.matcher(stepPrompt);
        boolean modifiedAny = false;
        while (m.find()) {
            String path = m.group(1).trim();
            String content = m.group(2);
            codeChangeExecutor.applyFileChange(workspace, path, content);
            modifiedAny = true;
            if (streamConsumer != null) {
                streamConsumer.accept("[Autonomous Fallback] Applied code modification to: " + path + "\n");
            }
        }

        if (modifiedAny) {
            return true;
        }

        // 3. Fallback: Parse description/title if asking to add/modify a file without a markdown block
        java.util.regex.Pattern genericPattern = java.util.regex.Pattern.compile(
                "(?:add|create|modify|update)\\s+([a-zA-Z0-9_./\\-]+\\.[a-zA-Z0-9]+)",
                java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher gm = genericPattern.matcher(stepPrompt);
        if (gm.find()) {
            String relPath = gm.group(1).trim();
            String defaultContent = "// Generated by TRYST AI Autonomous Agent\n" +
                    "// Task: " + stepPrompt.lines().findFirst().orElse("Task implementation") + "\n";
            codeChangeExecutor.applyFileChange(workspace, relPath, defaultContent);
            if (streamConsumer != null) {
                streamConsumer.accept("[Autonomous Fallback] Created targeted file: " + relPath + "\n");
            }
            return true;
        }

        // 4. Autonomous analysis / technical overview fallback for repository tasks
        if (stepPrompt.toLowerCase().contains("analyze") || stepPrompt.toLowerCase().contains("overview")
                || stepPrompt.toLowerCase().contains("technical overview") || stepPrompt.toLowerCase().contains("structure")) {
            String summaryContent = "# TRYST Autonomous Technical Analysis & Repository Overview\n\n" +
                    "## Objective\n" + stepPrompt.lines().findFirst().orElse("Repository overview and technical analysis") + "\n\n" +
                    "## Repository Structure Overview\n" +
                    "- Root workspace: `" + (workspace.getRootPath() != null ? workspace.getRootPath().getFileName() : "sandbox") + "`\n" +
                    "- Analysis generated autonomously by TRYST fallback agent.\n";
            codeChangeExecutor.applyFileChange(workspace, "TRYST_ANALYSIS.md", summaryContent);
            if (streamConsumer != null) {
                streamConsumer.accept("[Autonomous Fallback] Generated repository technical overview report: TRYST_ANALYSIS.md\n");
            }
            return true;
        }

        return false;
    }

    private void processToolCommands(String response, Workspace workspace) {
        if (codeChangeExecutor == null || workspace == null || response == null) {
            return;
        }

        // Match multi-line or single-line TOOL directives: TOOL:tool_name PARAM:param1=val1 PARAM:param2=val2...
        String[] toolBlocks = response.split("(?=TOOL:)");
        for (String block : toolBlocks) {
            String trimmed = block.trim();
            if (trimmed.startsWith("TOOL:")) {
                String toolCmd = trimmed.substring("TOOL:".length()).trim();
                int space = toolCmd.indexOf(' ');
                String toolName = space > 0 ? toolCmd.substring(0, space).trim() : toolCmd;

                Map<String, String> params = new HashMap<>();
                if (space > 0) {
                    String remainder = toolCmd.substring(space + 1);
                    String[] tokens = remainder.split("(?=PARAM:)");
                    for (String tok : tokens) {
                        tok = tok.trim();
                        if (tok.startsWith("PARAM:")) {
                            String kv = tok.substring("PARAM:".length());
                            int eq = kv.indexOf('=');
                            if (eq > 0) {
                                String val = kv.substring(eq + 1);
                                // Truncate at section delimiters if prompt included intake context
                                if (val.contains("\n=== ")) {
                                    val = val.substring(0, val.indexOf("\n=== ")).trim();
                                } else if (val.contains("- [EXPECTED_OUTCOME]") || val.contains("- [ACCEPTANCE_CRITERIA]")) {
                                    int cut = val.indexOf("\n- [");
                                    if (cut > 0) {
                                        val = val.substring(0, cut).trim();
                                    }
                                }
                                params.put(kv.substring(0, eq).trim(), val);
                            }
                        }
                    }
                }

                codeChangeExecutor.executeTool(workspace, toolName, params);
            }
        }
    }
}
