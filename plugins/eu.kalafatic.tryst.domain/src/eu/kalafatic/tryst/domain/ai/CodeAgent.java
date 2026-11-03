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
            return new AgentExecutionResult(true, "AI provider not configured; skipping step", AiFailureClassification.NONE, "");
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
                if (attempts > maxRetries) {
                    return new AgentExecutionResult(false, "", AiFailureClassification.UNCLASSIFIED_ERROR, e.getMessage());
                }
            }
        }

        return new AgentExecutionResult(false, "", AiFailureClassification.UNCLASSIFIED_ERROR,
                lastException != null ? lastException.getMessage() : "Execution failed");
    }

    private void processToolCommands(String response, Workspace workspace) {
        if (codeChangeExecutor == null || workspace == null || response == null) {
            return;
        }

        for (String line : response.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("TOOL:")) {
                String toolCmd = trimmed.substring("TOOL:".length()).trim();
                int space = toolCmd.indexOf(' ');
                String toolName = space > 0 ? toolCmd.substring(0, space) : toolCmd;

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
                                params.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                            }
                        }
                    }
                }

                codeChangeExecutor.executeTool(workspace, toolName, params);
            }
        }
    }
}
