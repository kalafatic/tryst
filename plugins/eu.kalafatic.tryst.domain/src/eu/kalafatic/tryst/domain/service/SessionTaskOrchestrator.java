package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.event.ApplicationEvents;
import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.domain.ai.AiContextManager;
import eu.kalafatic.tryst.domain.ai.AiInferenceService;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ConversationMessage;
import eu.kalafatic.tryst.domain.model.ExecutionResult;
import eu.kalafatic.tryst.domain.model.ExecutionStatus;
import eu.kalafatic.tryst.domain.model.Session;
import eu.kalafatic.tryst.domain.repository.ConversationMessageRepository;
import eu.kalafatic.tryst.domain.repository.ExecutionResultRepository;
import eu.kalafatic.tryst.domain.repository.SessionRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.tool.Tool;
import eu.kalafatic.tryst.domain.tool.ToolRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Service orchestrating Agent Sessions, Tasks, the Agent Execution Loop,
 * and persistent state updates across application lifecycles.
 */
public class SessionTaskOrchestrator {

    private final SessionRepository sessionRepository;
    private final TaskRepository taskRepository;
    private final ConversationMessageRepository messageRepository;
    private final ExecutionResultRepository executionResultRepository;
    private final ToolRegistry toolRegistry;
    private final PermissionManager permissionManager;
    private final EventBus eventBus;
    private final WorkspaceService workspaceService;
    private final AiContextManager contextManager;

    private volatile boolean cancelled = false;

    public SessionTaskOrchestrator(
            SessionRepository sessionRepository,
            TaskRepository taskRepository,
            ConversationMessageRepository messageRepository,
            ExecutionResultRepository executionResultRepository,
            ToolRegistry toolRegistry,
            PermissionManager permissionManager,
            EventBus eventBus,
            WorkspaceService workspaceService,
            AiContextManager contextManager
    ) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository must not be null");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository must not be null");
        this.executionResultRepository = Objects.requireNonNull(executionResultRepository, "executionResultRepository must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.permissionManager = Objects.requireNonNull(permissionManager, "permissionManager must not be null");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus must not be null");
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService must not be null");
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager must not be null");
    }

    public Session createSession(String projectId, String sessionName) {
        Session session = new Session(projectId, sessionName);
        sessionRepository.save(session);
        eventBus.publish(new ApplicationEvents.SessionEvent(session.getId(), "CREATED", "Session created: " + sessionName));
        return session;
    }

    public List<Session> listSessions() {
        return sessionRepository.findAll();
    }

    public Optional<Session> findSessionById(String id) {
        return sessionRepository.findById(id);
    }

    public void addMessage(String sessionId, String role, String content) {
        ConversationMessage msg = new ConversationMessage(sessionId, role, content);
        messageRepository.save(msg);
    }

    public List<ConversationMessage> getSessionMessages(String sessionId) {
        return messageRepository.findBySessionId(sessionId);
    }

    public void cancelCurrentExecution() {
        this.cancelled = true;
    }

    public CompletableFuture<ExecutionResult> executeTaskWithAgent(
            AgentTask task,
            AiInferenceService aiClient,
            String modelName,
            int maxIterations,
            Consumer<String> streamCallback
    ) {
        this.cancelled = false;
        long startTime = System.currentTimeMillis();

        task.start();
        taskRepository.save(task);
        eventBus.publish(new ApplicationEvents.TaskStateChangedEvent(task.getId(), "IDLE", "RUNNING", "Agent execution started"));

        return CompletableFuture.supplyAsync(() -> {
            StringBuilder executionLog = new StringBuilder();
            int iteration = 0;
            boolean completedSuccessfully = false;

            try {
                while (iteration < maxIterations && !cancelled && !completedSuccessfully) {
                    iteration++;
                    executionLog.append("\n--- Iteration ").append(iteration).append(" ---\n");

                    // 1. Gather messages & context
                    List<ConversationMessage> history = task.getSessionId() != null
                            ? messageRepository.findBySessionId(task.getSessionId())
                            : List.of();

                    String sysInstructions = contextManager.buildSystemInstructions(
                            workspaceService.getCurrentWorkspace(),
                            workspaceService.getCurrentWorkspace() != null ? workspaceService.getCurrentWorkspace().getActiveProject().orElse(null) : null
                    ) + "\n" + toolRegistry.generateToolHelp() +
                            "\nWhen executing a tool, output a command block: TOOL:<tool_name> PARAM:<key>=<value>";

                    List<AiInferenceService.ChatMessage> promptMessages = contextManager.assembleConversationContext(
                            sysInstructions,
                            history,
                            task.getTitle() + "\n" + task.getDescription() + "\nPrevious Execution Log:\n" + executionLog,
                            workspaceService.getCurrentWorkspace() != null ? workspaceService.getCurrentWorkspace().getActiveProject().orElse(null) : null
                    );

                    // 2. Inference
                    AiInferenceService.InferenceRequest req = new AiInferenceService.InferenceRequest(modelName, promptMessages);
                    AiInferenceService.InferenceResponse response;
                    try {
                        response = aiClient.generateResponse(req).join();
                    } catch (Exception e) {
                        executionLog.append("Model inference error: ").append(e.getMessage()).append("\n");
                        break;
                    }

                    String content = response.content();
                    executionLog.append(content).append("\n");
                    if (streamCallback != null) {
                        streamCallback.accept(content);
                    }

                    // 3. Tool detection & execution
                    boolean toolExecuted = false;
                    for (String line : content.split("\n")) {
                        if (line.trim().startsWith("TOOL:")) {
                            String toolCall = line.trim().substring("TOOL:".length()).trim();
                            toolExecuted = true;
                            executeToolCommand(toolCall, executionLog);
                        }
                    }

                    if (!toolExecuted || content.toLowerCase().contains("task complete") || content.toLowerCase().contains("completed")) {
                        completedSuccessfully = true;
                    }
                }

                if (cancelled) {
                    task.cancel();
                    executionLog.append("\n[Execution Cancelled by User]\n");
                } else if (completedSuccessfully) {
                    task.complete();
                } else {
                    task.fail();
                }

            } catch (Exception e) {
                if (task.getStatus() == ExecutionStatus.RUNNING) {
                    task.fail();
                }
                executionLog.append("\nExecution exception: ").append(e.getMessage()).append("\n");
            } finally {
                taskRepository.save(task);
                eventBus.publish(new ApplicationEvents.TaskStateChangedEvent(
                        task.getId(), "RUNNING", task.getStatus().name(), "Execution terminated with status " + task.getStatus()
                ));
            }

            long duration = System.currentTimeMillis() - startTime;
            int exitCode = task.getStatus() == ExecutionStatus.COMPLETED ? 0 : 1;
            ExecutionResult result = new ExecutionResult(task.getId(), exitCode, executionLog.toString(), "", duration);
            executionResultRepository.save(result);
            return result;
        });
    }

    private void executeToolCommand(String toolCommand, StringBuilder log) {
        int firstSpace = toolCommand.indexOf(' ');
        String toolName = firstSpace > 0 ? toolCommand.substring(0, firstSpace) : toolCommand;
        Optional<Tool> optTool = toolRegistry.getTool(toolName);
        if (optTool.isEmpty()) {
            log.append("[Tool Error]: Tool not found: ").append(toolName).append("\n");
            return;
        }

        Map<String, String> params = new HashMap<>();
        if (firstSpace > 0) {
            String remainder = toolCommand.substring(firstSpace + 1);
            String[] paramTokens = remainder.split("(?=PARAM:)");
            for (String token : paramTokens) {
                token = token.trim();
                if (token.startsWith("PARAM:")) {
                    String kv = token.substring("PARAM:".length());
                    int eq = kv.indexOf('=');
                    if (eq > 0) {
                        params.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                    }
                }
            }
        }

        Tool tool = optTool.get();
        Tool.ToolResult result = tool.execute(params, workspaceService.getCurrentWorkspace(), permissionManager);
        if (result.success()) {
            log.append("[Tool ").append(toolName).append(" Result]: ").append(result.output()).append("\n");
            eventBus.publish(new ApplicationEvents.ToolExecutionEvent(
                    null, toolName, params.toString(), true, result.output()
            ));
        } else {
            log.append("[Tool ").append(toolName).append(" Failed]: ").append(result.error()).append("\n");
            eventBus.publish(new ApplicationEvents.ToolExecutionEvent(
                    null, toolName, params.toString(), false, result.error()
            ));
        }
    }
}
