package eu.kalafatic.tryst.application;

import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.application.launcher.TerminalLauncher;
import eu.kalafatic.tryst.core.config.ApplicationConfiguration;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import eu.kalafatic.tryst.core.log.TrystLog;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Headless Equinox execution entry point for TRYST server operations.
 * Starts the application lifecycle coordinator and embedded communication services (TCP, HTTP)
 * without requiring SWT or a graphical display, preserving the existing RCP/OSGi architecture.
 *
 * <p>Command dispatch is driven by a single command table (name, syntax, short help, topic
 * help, handler) shared by one-shot argument dispatch, {@code help [topic]} and the
 * interactive REPL. Slash forms ({@code /status}, {@code /help}, ...) are accepted.
 *
 * <p>Modes:
 * <ul>
 *   <li>one-shot command argument — executes the command and exits (0 on success,
 *       unknown command exits 1 with suggestions).</li>
 *   <li>(no argument, interactive TTY) — REPL: starts the server, prints a banner and a
 *       {@code tryst>} prompt; {@code /exit}, {@code /stop} or EOF shut the server down.</li>
 *   <li>(no argument, non-TTY) — daemon: starts the server and keeps serving until shutdown
 *       (unchanged).</li>
 * </ul>
 */
public class TrystServerApplication implements IApplication {

    /** Single command-table entry driving one-shot dispatch, help output and the REPL. */
    private interface ServerCommand {
        /** Canonical command name without slash. */
        String name();

        /** Usage syntax, e.g. {@code "logs [show]"}. */
        String syntax();

        /** One-line summary for the command list. */
        String summary();

        /** Detailed help printed by {@code help <name>}. */
        String topicHelp();

        /** Executes the command; returns the process exit code (one-shot mode only). */
        int execute(String[] args);
    }

    private final CountDownLatch stopLatch = new CountDownLatch(1);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ApplicationLifecycleCoordinator coordinator;

    private final List<ServerCommand> commandTable = List.of(
            new ServerCommand() {
                @Override public String name() { return "help"; }
                @Override public String syntax() { return "help [topic]"; }
                @Override public String summary() { return "Print general help or help for a single command."; }
                @Override public String topicHelp() {
                    return """
                            help [topic]

                              Prints the available TRYST server commands. With a topic argument,
                              prints the detailed help for that command.

                            Examples:
                              tryst -application eu.kalafatic.tryst.application.server help
                              tryst -application eu.kalafatic.tryst.application.server help sessions
                              tryst -application eu.kalafatic.tryst.application.server /help config
                            """;
                }
                @Override public int execute(String[] args) {
                    printHelp(args);
                    return 0;
                }
            },
            new ServerCommand() {
                @Override public String name() { return "status"; }
                @Override public String syntax() { return "status"; }
                @Override public String summary() { return "Start the server, print the live host/port status and exit."; }
                @Override public String topicHelp() {
                    return """
                            status

                              Brings the server up through the regular lifecycle stages and prints
                              the live host/port status from the authoritative runtime endpoint
                              registry (bound HTTP listener and port registry as fallbacks).
                              Exits 0 when the server reached the READY stage.

                              Alias: /status
                            """;
                }
                @Override public int execute(String[] args) {
                    return runStatusCommand();
                }
            },
            new ServerCommand() {
                @Override public String name() { return "web"; }
                @Override public String syntax() { return "web"; }
                @Override public String summary() { return "Print the live TRYST web interface URL."; }
                @Override public String topicHelp() {
                    return """
                            web

                              Starts the server when necessary and prints the live web interface
                              URL resolved from the runtime endpoint registry (external URL when
                              advertised, otherwise the bound local URL).

                              Alias: /web
                            """;
                }
                @Override public int execute(String[] args) {
                    ApplicationLifecycleCoordinator coord = ensureServerUp();
                    System.out.println("TRYST web interface: " + resolveBaseUrl(coord));
                    return 0;
                }
            },
            new ServerCommand() {
                @Override public String name() { return "terminal"; }
                @Override public String syntax() { return "terminal [directory]"; }
                @Override public String summary() { return "Open a terminal emulator in the workspace (or the given directory)."; }
                @Override public String topicHelp() {
                    return """
                            terminal [directory]

                              Opens a terminal emulator (x-terminal-emulator, gnome-terminal,
                              konsole or xterm - whichever is found on PATH) in the current
                              TRYST workspace root. When a directory argument is given and
                              exists, that directory is used instead. Falls back to the user
                              home directory when no live workspace is loaded.

                              This is guarded: when no supported emulator exists, a message is
                              printed and the command still exits 0 (no terminal is spawned).

                              Alias: /terminal
                            """;
                }
                @Override public int execute(String[] args) {
                    Path directory = null;
                    if (args.length > 0 && args[0] != null && !args[0].isBlank()) {
                        directory = Path.of(args[0]);
                    } else {
                        ApplicationLifecycleCoordinator coord = ensureServerUp();
                        directory = resolveWorkspaceRoot(coord);
                    }
                    String emulator = TerminalLauncher.probeEmulatorNames();
                    if (emulator != null) {
                        System.out.println("Terminal emulator available on PATH: " + emulator);
                    }
                    TerminalLauncher.LaunchResult result = TerminalLauncher.launch(directory);
                    switch (result.status()) {
                        case LAUNCHED -> System.out.println("Terminal opened ("
                                + result.command() + ") in " + result.detail());
                        case NO_EMULATOR -> System.out.println(
                                "No supported terminal emulator found on PATH (tried x-terminal-emulator, "
                                        + "gnome-terminal, konsole, xterm); nothing was launched.");
                        default -> {
                            System.out.println("Failed to open terminal: " + result.detail());
                            return 1;
                        }
                    }
                    return 0;
                }
            },
            new ServerCommand() {
                @Override public String name() { return "models"; }
                @Override public String syntax() { return "models"; }
                @Override public String summary() { return "List published AI models (GET /api/models)."; }
                @Override public String topicHelp() {
                    return """
                            models

                              Starts the server when necessary and lists the AI models published
                              by the model registry through the real HTTP API (GET /api/models).

                              Alias: /models
                            """;
                }
                @Override public int execute(String[] args) {
                    ApplicationLifecycleCoordinator coord = ensureServerUp();
                    String json = httpGet(coord, "/api/models");
                    if (json == null) {
                        return 1;
                    }
                    List<Map<String, String>> models = extractJsonObjects(json, "models");
                    String defaultModel = jsonStringField(json, "defaultModel");
                    if (models.isEmpty()) {
                        System.out.println("No models published by the AI model registry."
                                + (defaultModel != null ? " Default model: " + defaultModel : ""));
                        return 0;
                    }
                    System.out.println("Published AI models (" + models.size() + ")"
                            + (defaultModel != null ? ", default: " + defaultModel : "") + ":");
                    for (Map<String, String> m : models) {
                        System.out.printf("  %-28s provider=%-10s available=%-5s context=%-8s%n",
                                m.getOrDefault("model", m.getOrDefault("id", "?")),
                                m.getOrDefault("providerId", "?"),
                                m.getOrDefault("available", "?"),
                                m.getOrDefault("contextLimit", "?"));
                    }
                    return 0;
                }
            },
            new ServerCommand() {
                @Override public String name() { return "sessions"; }
                @Override public String syntax() { return "sessions"; }
                @Override public String summary() { return "List tasks (TRYST has no public sessions endpoint; shows GET /api/tasks)."; }
                @Override public String topicHelp() {
                    return """
                            sessions

                              TRYST does not expose a public /api/sessions endpoint (session
                              administration lives under /api/admin/sessions). This command
                              therefore lists the tasks of the live workspace through
                              GET /api/tasks and prints their status.

                              Alias: /sessions
                            """;
                }
                @Override public int execute(String[] args) {
                    ApplicationLifecycleCoordinator coord = ensureServerUp();
                    String json = httpGet(coord, "/api/tasks");
                    if (json == null) {
                        return 1;
                    }
                    List<Map<String, String>> tasks = extractJsonObjects(json, "tasks");
                    System.out.println("Sessions note: no public /api/sessions endpoint exists; showing tasks (GET /api/tasks):");
                    if (tasks.isEmpty()) {
                        System.out.println("  (no tasks)");
                        return 0;
                    }
                    for (Map<String, String> t : tasks) {
                        String id = t.getOrDefault("id", "?");
                        System.out.printf("  [%-10s] %-24s %s%n",
                                t.getOrDefault("status", "?"),
                                id.length() > 24 ? id.substring(0, 21) + "..." : id,
                                t.getOrDefault("title", ""));
                    }
                    return 0;
                }
            },
            new ServerCommand() {
                @Override public String name() { return "providers"; }
                @Override public String syntax() { return "providers"; }
                @Override public String summary() { return "List AI providers published by the model registry (GET /api/models)."; }
                @Override public String topicHelp() {
                    return """
                            providers

                              Starts the server when necessary and prints the distinct AI
                              providers referenced by the published models of the model registry
                              (GET /api/models providerId information).

                              Alias: /providers
                            """;
                }
                @Override public int execute(String[] args) {
                    ApplicationLifecycleCoordinator coord = ensureServerUp();
                    String json = httpGet(coord, "/api/models");
                    if (json == null) {
                        return 1;
                    }
                    List<Map<String, String>> models = extractJsonObjects(json, "models");
                    Set<String> providers = new LinkedHashSet<>();
                    for (Map<String, String> m : models) {
                        String provider = m.getOrDefault("providerId", "");
                        if (!provider.isBlank()) {
                            providers.add(provider);
                        }
                    }
                    if (providers.isEmpty()) {
                        System.out.println("No providers published by the AI model registry.");
                    } else {
                        System.out.println("AI providers published by the model registry:");
                        providers.forEach(p -> System.out.println("  " + p));
                    }
                    return 0;
                }
            },
            new ServerCommand() {
                @Override public String name() { return "agents"; }
                @Override public String syntax() { return "agents"; }
                @Override public String summary() { return "List connected coding agents (GET /api/integration/applications)."; }
                @Override public String topicHelp() {
                    return """
                            agents

                              Starts the server when necessary and lists the connected coding
                              agents — the family coding harnesses (DCode, DStudio, EVO,
                              OpenCode) of the connected-application registry — with type,
                              enablement, live status and the current selection
                              (GET /api/integration/applications). Connection configuration
                              is never printed.

                              Alias: /agents
                            """;
                }
                @Override public int execute(String[] args) {
                    ApplicationLifecycleCoordinator coord = ensureServerUp();
                    String json = httpGet(coord, "/api/integration/applications");
                    if (json == null) {
                        return 1;
                    }
                    List<Map<String, String>> agents = extractJsonArray(json);
                    if (agents.isEmpty()) {
                        System.out.println("No connected coding agents.");
                        return 0;
                    }
                    System.out.println("Connected coding agents (" + agents.size() + "):");
                    for (Map<String, String> a : agents) {
                        System.out.printf("  %-24s type=%-8s enabled=%-5s status=%-12s%s%n",
                                a.getOrDefault("name", a.getOrDefault("id", "?")),
                                a.getOrDefault("type", "?"),
                                a.getOrDefault("enabled", "?"),
                                a.getOrDefault("status", "?"),
                                "true".equals(a.getOrDefault("selected", "false"))
                                        ? "  [selected]" : "");
                    }
                    return 0;
                }
            },
            new ServerCommand() {
                @Override public String name() { return "config"; }
                @Override public String syntax() { return "config [show]"; }
                @Override public String summary() { return "Print the effective configuration (no secrets)."; }
                @Override public String topicHelp() {
                    return """
                            config [show]

                              Starts the server when necessary (to load the effective
                              configuration) and prints the applied ApplicationConfiguration.
                              No secrets or credentials are ever printed.

                              Alias: /config
                            """;
                }
                @Override public int execute(String[] args) {
                    ApplicationLifecycleCoordinator coord = ensureServerUp();
                    ApplicationConfiguration cfg = coord.getConfiguration();
                    if (cfg == null) {
                        System.out.println("Effective configuration unavailable.");
                        return 1;
                    }
                    System.out.println("Effective TRYST configuration:");
                    System.out.println("  application           : " + cfg.applicationName() + " " + cfg.version());
                    System.out.println("  user.home             : " + cfg.userHomeDirectory());
                    System.out.println("  configuration.dir     : " + cfg.configurationDirectory());
                    System.out.println("  workspace.dir         : " + cfg.defaultWorkspaceDirectory());
                    System.out.println("  server.host           : " + cfg.serverHost());
                    System.out.println("  server.http.port      : " + cfg.httpPort());
                    System.out.println("  server.tcp.port       : " + cfg.tcpPort());
                    System.out.println("  dynamic.port.fallback : " + cfg.dynamicPortFallbackEnabled()
                            + " (range " + cfg.portRangeStart() + "-" + cfg.portRangeEnd() + ")");
                    System.out.println("  network.mode          : " + cfg.networkMode());
                    System.out.println("  bind.address          : " + (cfg.bindAddress() != null ? cfg.bindAddress() : "(derived)"));
                    System.out.println("  reverse.proxy         : " + cfg.reverseProxyEnabled());
                    System.out.println("  context.path          : " + cfg.contextPath());
                    System.out.println("  public.url            : " + (cfg.publicServerUrl() != null && !cfg.publicServerUrl().isBlank()
                            ? cfg.publicServerUrl() : "(none)"));
                    System.out.println("  canonical.url         : " + cfg.canonicalUrl());
                    System.out.println("  ollama.endpoint       : " + cfg.ollamaEndpoint());
                    System.out.println("  default.model         : " + cfg.defaultModel());
                    System.out.println("  execution.timeout     : " + cfg.requestTimeoutSeconds() + "s");
                    System.out.println("  execution.iterations  : " + cfg.maxIterations());
                    System.out.println("  execution.retries     : " + cfg.maxRetryAttempts());
                    System.out.println("  ai.context.tokens     : " + cfg.maxContextTokens());
                    System.out.println("  ai.output.tokens      : " + cfg.maxOutputTokens());
                    System.out.println("  sandbox.enabled       : " + cfg.secureSandboxEnabled());
                    return 0;
                }
            },
            new ServerCommand() {
                @Override public String name() { return "logs"; }
                @Override public String syntax() { return "logs [show]"; }
                @Override public String summary() { return "Print the TRYST log file location and its last lines."; }
                @Override public String topicHelp() {
                    return """
                            logs [show]

                              Prints the location of the TRYST log file
                              (~/.tryst/logs/tryst.log, overridable via -Dtryst.log.dir=...)
                              followed by its last 25 lines when the file exists.

                              Alias: /logs
                            """;
                }
                @Override public int execute(String[] args) {
                    Path logFile = resolveLogFile();
                    System.out.println("TRYST log file: " + logFile.toAbsolutePath());
                    if (!Files.isRegularFile(logFile)) {
                        System.out.println("  (log file does not exist yet)");
                        return 0;
                    }
                    try {
                        List<String> lines = Files.readAllLines(logFile);
                        int from = Math.max(0, lines.size() - 25);
                        if (lines.isEmpty()) {
                            System.out.println("  (log file is empty)");
                        } else {
                            System.out.println("  last " + (lines.size() - from) + " of " + lines.size() + " lines:");
                            for (int i = from; i < lines.size(); i++) {
                                System.out.println("  " + lines.get(i));
                            }
                        }
                    } catch (IOException e) {
                        System.out.println("  Failed to read log file: " + e.getMessage());
                        return 1;
                    }
                    return 0;
                }
            },
            new ServerCommand() {
                @Override public String name() { return "stop"; }
                @Override public String syntax() { return "stop"; }
                @Override public String summary() { return "Graceful shutdown of the server running in this process session."; }
                @Override public String topicHelp() {
                    return """
                            stop

                              In the interactive REPL (and in daemon mode via its lifecycle)
                              this performs a graceful server shutdown and exits. When invoked
                              as a one-shot command there is no daemon running in this process
                              session, so nothing is stopped.

                              Alias: /stop
                            """;
                }
                @Override public int execute(String[] args) {
                    System.out.println("No TRYST server is running in this process session "
                            + "(stop applies to the interactive REPL/daemon). Start a server with no arguments.");
                    return 0;
                }
            },
            new ServerCommand() {
                @Override public String name() { return "restart"; }
                @Override public String syntax() { return "restart"; }
                @Override public String summary() { return "Explain how to restart the TRYST server."; }
                @Override public String topicHelp() {
                    return """
                            restart

                              TRYST cannot re-initialize its full application lifecycle
                              (OSGi services, SQLite, HTTP/TCP listeners, logging) safely
                              inside a running process. To restart: exit the current process
                              (/exit, /stop or Ctrl+C) and launch it again with the same
                              command line. Nothing is faked or half-applied here.

                              Alias: /restart
                            """;
                }
                @Override public int execute(String[] args) {
                    System.out.println("In-process restart is not supported. Exit this process and relaunch:");
                    System.out.println("  tryst -nosplash -application eu.kalafatic.tryst.application.server");
                    return 0;
                }
            },
            new ServerCommand() {
                @Override public String name() { return "start"; }
                @Override public String syntax() { return "start"; }
                @Override public String summary() { return "Explain how to start the TRYST server."; }
                @Override public String topicHelp() {
                    return """
                            start

                              The TRYST server starts automatically: launch the application
                              with no command argument to run the daemon (non-TTY) or the
                              interactive REPL (TTY). This command only explains the modes.

                              Alias: /start
                            """;
                }
                @Override public int execute(String[] args) {
                    System.out.println("The TRYST server starts automatically with no command argument:");
                    System.out.println("  daemon : tryst -nosplash -application eu.kalafatic.tryst.application.server");
                    System.out.println("  REPL   : run the same command on a terminal (TTY)");
                    return 0;
                }
            },
            new ServerCommand() {
                @Override public String name() { return "exit"; }
                @Override public String syntax() { return "exit"; }
                @Override public String summary() { return "Leave the interactive REPL (REPL only)."; }
                @Override public String topicHelp() {
                    return """
                            exit

                              Stops the server and leaves the interactive REPL. As a one-shot
                              command it has no effect.

                              Alias: /exit
                            """;
                }
                @Override public int execute(String[] args) {
                    System.out.println("exit applies to the interactive REPL; use 'status' etc. for one-shot commands.");
                    return 0;
                }
            }
    );

    @Override
    public Object start(IApplicationContext context) throws Exception {
        String[] args = resolveArgs(context);
        if (args.length > 0) {
            return runOneShot(args);
        }

        // No command argument.
        if (System.console() != null) {
            return runRepl();
        }

        // Daemon mode (non-TTY, no arguments) — unchanged behavior.
        TrystApplicationPlugin plugin = TrystApplicationPlugin.getDefault();
        ApplicationLifecycleCoordinator coord = (plugin != null) ? plugin.getLifecycleCoordinator() : new ApplicationLifecycleCoordinator();
        return runHeadless(context, coord);
    }

    /**
     * One-shot command dispatch driven by the command table. Unknown commands exit 1 with
     * nearest-match suggestions; the known {@code status} and {@code help} behavior is
     * preserved.
     */
    private Object runOneShot(String[] args) {
        String rawName = args[0];
        String name = normalizeCommandName(rawName);
        if ("help".equals(name)) {
            printHelp(Arrays.copyOfRange(args, 1, args.length));
            return IApplication.EXIT_OK;
        }
        ServerCommand command = findCommand(name);
        if (command == null) {
            System.out.println("Unknown command: " + rawName);
            System.out.println("Did you mean: " + String.join(", ", suggestCommands(name)));
            System.out.println("Run with 'help' to list the available TRYST server commands.");
            return Integer.valueOf(1);
        }
        try {
            return command.execute(Arrays.copyOfRange(args, 1, args.length));
        } catch (Throwable t) {
            TrystLog.error(TrystServerApplication.class,
                    "Command '" + name + "' failed: " + t.getMessage(), t);
            System.out.println("Command '" + name + "' failed: " + t.getMessage());
            return Integer.valueOf(1);
        } finally {
            cleanup();
        }
    }

    /**
     * Interactive REPL: starts the server once, prints a banner, then dispatches lines from
     * the console through the same command table until {@code /exit}, {@code /stop} or EOF.
     */
    private Object runRepl() {
        try {
            ensureServerUp();
            logDiagnostics();
            registerShutdownHook();

            Console console = System.console();
            System.out.println("TRYST headless server REPL (server is running).");
            System.out.println("Type /help for commands, /exit to leave.");
            String line;
            while ((line = console.readLine("tryst> ")) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String[] tokens = parseTokens(trimmed);
                String name = normalizeCommandName(tokens[0]);
                if ("exit".equals(name) || "stop".equals(name)) {
                    break;
                }
                if ("help".equals(name)) {
                    printHelp(Arrays.copyOfRange(tokens, 1, tokens.length));
                    continue;
                }
                ServerCommand command = findCommand(name);
                if (command == null) {
                    System.out.println("Unknown command '" + tokens[0] + "'. Did you mean: "
                            + String.join(", ", suggestCommands(name)));
                    continue;
                }
                try {
                    command.execute(Arrays.copyOfRange(tokens, 1, tokens.length));
                } catch (Throwable t) {
                    TrystLog.error(TrystServerApplication.class,
                            "Command '" + name + "' failed: " + t.getMessage(), t);
                    System.out.println("Command '" + name + "' failed: " + t.getMessage());
                }
            }
            System.out.println("Shutting down TRYST server...");
            return IApplication.EXIT_OK;
        } catch (Throwable t) {
            TrystLog.error(TrystServerApplication.class, "Fatal error running TRYST REPL: " + t.getMessage(), t);
            System.out.println("TRYST REPL failed: " + t.getMessage());
            return Integer.valueOf(1);
        } finally {
            cleanup();
        }
    }

    public Object runHeadless(IApplicationContext context, ApplicationLifecycleCoordinator coord) throws Exception {
        this.coordinator = coord != null ? coord : new ApplicationLifecycleCoordinator();

        try {
            running.set(true);
            coordinator.transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);
            coordinator.transitionTo(ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
            coordinator.transitionTo(ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);
            coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_READY);

            logDiagnostics();

            // Register JVM shutdown hook for graceful termination
            registerShutdownHook();

            stopLatch.await();
            return IApplication.EXIT_OK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return IApplication.EXIT_OK;
        } catch (Throwable t) {
            TrystLog.error(TrystServerApplication.class, "Fatal error running TRYST Headless Server: " + t.getMessage(), t);
            throw t;
        } finally {
            cleanup();
        }
    }

    private void registerShutdownHook() {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "tryst-headless-shutdown-hook"));
        } catch (IllegalStateException alreadyShuttingDown) {
            // JVM is already shutting down; nothing to register.
        }
    }

    /**
     * Brings the server up through the regular lifecycle stages (idempotent; reused by the
     * one-shot query commands and the REPL) and returns the live coordinator.
     */
    private ApplicationLifecycleCoordinator ensureServerUp() {
        if (coordinator == null) {
            TrystApplicationPlugin plugin = TrystApplicationPlugin.getDefault();
            coordinator = (plugin != null) ? plugin.getLifecycleCoordinator() : new ApplicationLifecycleCoordinator();
        }
        if (coordinator.getCurrentStage().ordinal() < ApplicationLifecycleStage.PLATFORM_INITIALIZATION.ordinal()) {
            coordinator.transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);
        }
        if (coordinator.getCurrentStage().ordinal() < ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION.ordinal()) {
            coordinator.transitionTo(ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
        }
        if (coordinator.getCurrentStage().ordinal() < ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION.ordinal()) {
            coordinator.transitionTo(ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);
        }
        if (coordinator.getCurrentStage().ordinal() < ApplicationLifecycleStage.APPLICATION_READY.ordinal()) {
            coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_READY);
        }
        return coordinator;
    }

    /**
     * Executes the {@code status} subcommand: brings the server up through the regular
     * lifecycle stages, prints the live host/port from the authoritative runtime endpoint
     * registry (falling back to the bound HTTP listener and the port registry) and exits.
     */
    private int runStatusCommand() {
        try {
            ApplicationLifecycleCoordinator coord = ensureServerUp();

            String host = null;
            int port = -1;
            var cfgService = coord.getConfigurationService();
            if (cfgService != null && cfgService.getEndpointRegistry() != null
                    && cfgService.getEndpointRegistry().getCurrentEndpoint() != null) {
                var ep = cfgService.getEndpointRegistry().getCurrentEndpoint();
                host = ep.bindAddress();
                port = ep.httpPort();
            }
            if (port <= 0) {
                var http = coord.getHttpService();
                if (http != null && http.isRunning()) {
                    port = http.getPort();
                }
            }
            if (port <= 0 && cfgService != null && cfgService.getPortRegistry() != null) {
                port = cfgService.getPortRegistry()
                        .getEffectivePort(eu.kalafatic.tryst.core.port.ServicePortIdentifier.HTTP_SERVICE);
            }
            var cfg = coord.getConfiguration();
            if (host == null || host.isBlank()) {
                host = (cfg != null) ? cfg.serverHost() : "127.0.0.1";
            }
            if (port <= 0) {
                port = (cfg != null) ? cfg.httpPort() : -1;
            }

            boolean setupRequired = true;
            var auth = resolveService(eu.kalafatic.tryst.domain.security.AuthenticationService.class);
            if (auth != null) {
                setupRequired = auth.isSetupRequired();
            }

            System.out.println("TRYST server: UP on " + host + ":" + port + " (setup required: " + setupRequired + ")");
            return IApplication.EXIT_OK;
        } catch (Throwable t) {
            TrystLog.error(TrystServerApplication.class, "Status command failed: " + t.getMessage(), t);
            System.out.println("TRYST server: DOWN (" + t.getMessage() + ")");
            return Integer.valueOf(1);
        }
    }

    /**
     * Prints the general help (command table + configuration properties) or the detailed
     * topic help for a single command.
     */
    private void printHelp(String[] args) {
        if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
            String topic = normalizeCommandName(args[0]);
            ServerCommand command = findCommand(topic);
            if (command == null) {
                System.out.println("Unknown command: " + args[0]);
                System.out.println("Did you mean: " + String.join(", ", suggestCommands(topic)));
                return;
            }
            System.out.println("TRYST headless server - help for '" + command.name() + "'\n");
            System.out.println(command.topicHelp());
            return;
        }

        PrintStream out = System.out;
        out.println("TRYST headless server");
        out.println();
        out.println("Usage:");
        out.println("  tryst -application eu.kalafatic.tryst.application.server [command]");
        out.println();
        out.println("Commands:");
        out.println("  (no arguments)    Start the TRYST server and keep serving until shutdown"
                + " (REPL on a TTY).");
        for (ServerCommand command : commandTable) {
            out.printf("  %-18s %s%n", command.syntax(), command.summary());
        }
        out.println();
        out.println("Slash forms (/status, /help, /web, /terminal, /models, /sessions,");
        out.println("  /providers, /config, /logs, /stop, /restart, /start, /exit) are accepted.");
        out.println();
        out.println("Configuration properties (tryst.properties, Java system properties or environment):");
        out.println();
        out.println("  Server & network:");
        out.println("    server.host                     Bind/display host (default 127.0.0.1)");
        out.println("    server.http.port                HTTP listener port (default 38080; 8080 is forbidden)");
        out.println("    server.tcp.port                 TCP service port (default 38280; 8080 is forbidden)");
        out.println("    server.dynamicPortFallback      true|false (default true)");
        out.println("    server.portRangeStart           Dynamic fallback range start (default 38080)");
        out.println("    server.portRangeEnd             Dynamic fallback range end (default 38580)");
        out.println("    server.networkMode              LOOPBACK | LAN | ALL_INTERFACES");
        out.println("    server.bindAddress              Explicit bind address override");
        out.println("    server.publicUrl                Advertised public URL");
        out.println("    server.reverseProxyEnabled      true|false (default false)");
        out.println("    server.contextPath              Web context path (default /tryst)");
        out.println("    server.canonicalUrl             Canonical access URL");
        out.println("    server.externalBaseUrl          External base URL override");
        out.println();
        out.println("  AI / execution:");
        out.println("    ollama.endpoint                 Ollama endpoint URL (default http://localhost:11434)");
        out.println("    ollama.model                    Default model (default codellama)");
        out.println("    execution.timeoutSeconds        Request timeout seconds (default 60)");
        out.println("    execution.maxIterations         Maximum iterations (default 5)");
        out.println("    execution.maxRetryAttempts      Maximum retry attempts (default 3)");
        out.println("    ai.maxContextTokens             Maximum context tokens (default 4096)");
        out.println("    ai.maxOutputTokens              Maximum output tokens (default 2048)");
        out.println("    security.sandboxEnabled         true|false (default true)");
        out.println();
        out.println("  Runtime flags (system properties / environment):");
        out.println("    tryst.headless                  true|false — force headless mode");
        out.println("    tryst.dev.mode                  true|false — development mode (default true)");
        out.println("    TRYST_HTTP_PORT / TRYST_PORT    HTTP port override");
        out.println("    TRYST_TCP_PORT                  TCP port override");
        out.println("    TRYST_NETWORK_MODE              Network mode override");
        out.println("    TRYST_BIND_ADDRESS              Bind address override");
        out.println("    TRYST_EXTERNAL_BASE_URL         External base URL override");
        out.println("    TRYST_OLLAMA_ENDPOINT           Ollama endpoint override");
        out.println("    OLLAMA_HOST                     Ollama host override");
    }

    /**
     * Resolves the application arguments (first non-flag token is the command); the legacy
     * {@code -h}, {@code --help} and {@code -help} flags map to the help command.
     */
    private static String[] resolveArgs(IApplicationContext context) {
        if (context == null || context.getArguments() == null) {
            return new String[0];
        }
        Object argsObj = context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
        if (!(argsObj instanceof String[] rawArgs)) {
            return new String[0];
        }
        List<String> args = new ArrayList<>();
        for (String arg : rawArgs) {
            if (arg == null || arg.trim().isEmpty()) {
                continue;
            }
            String trimmed = arg.trim();
            if (args.isEmpty()) {
                if ("-h".equals(trimmed) || "--help".equals(trimmed) || "-help".equals(trimmed)) {
                    args.add("help");
                    continue;
                }
                if (trimmed.startsWith("-")) {
                    continue; // runtime flag before the command, e.g. -data / -vmargs leftovers
                }
            }
            args.add(trimmed);
        }
        return args.toArray(new String[0]);
    }

    private static String[] parseTokens(String line) {
        return line.trim().split("\\s+");
    }

    /** Normalizes a command token: strips a leading slash and lowercases it. */
    private static String normalizeCommandName(String token) {
        if (token == null) {
            return "";
        }
        String t = token.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("/")) {
            t = t.substring(1);
        }
        return t;
    }

    private ServerCommand findCommand(String name) {
        for (ServerCommand command : commandTable) {
            if (command.name().equals(name)) {
                return command;
            }
        }
        return null;
    }

    /**
     * Suggests command names for an unknown token using prefix matching and a bounded
     * Levenshtein edit distance over the command table.
     */
    private List<String> suggestCommands(String name) {
        List<String> suggestions = new ArrayList<>();
        String normalized = (name != null) ? name : "";
        if (normalized.isEmpty()) {
            return suggestions;
        }
        // 1. Prefix matches (most likely intent).
        for (ServerCommand command : commandTable) {
            if (command.name().startsWith(normalized)) {
                suggestions.add(command.name());
            }
        }
        // 2. Edit-distance matches (typos).
        for (ServerCommand command : commandTable) {
            if (!suggestions.contains(command.name())
                    && levenshteinDistance(normalized, command.name()) <= 2) {
                suggestions.add(command.name());
            }
        }
        if (suggestions.isEmpty()) {
            suggestions.add("help");
        }
        return suggestions;
    }

    private static int levenshteinDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev[b.length()];
    }

    /**
     * Resolves the live base URL of the running server (bound port, never hardcoded) used by
     * the query commands for local HTTP calls.
     */
    private String resolveBaseUrl(ApplicationLifecycleCoordinator coord) {
        if (coord != null && coord.getConfigurationService() != null) {
            var reg = coord.getConfigurationService().getEndpointRegistry();
            if (reg != null && reg.getCurrentEndpoint() != null) {
                var ep = reg.getCurrentEndpoint();
                if (ep.internalUrl() != null && !ep.internalUrl().isBlank()) {
                    return ep.internalUrl();
                }
            }
        }
        int port = -1;
        ApplicationConfiguration cfg = coord != null ? coord.getConfiguration() : null;
        if (coord != null && coord.getHttpService() != null && coord.getHttpService().isRunning()) {
            port = coord.getHttpService().getPort();
        }
        if (port <= 0 && coord != null && coord.getConfigurationService() != null
                && coord.getConfigurationService().getPortRegistry() != null) {
            port = coord.getConfigurationService().getPortRegistry()
                    .getEffectivePort(eu.kalafatic.tryst.core.port.ServicePortIdentifier.HTTP_SERVICE);
        }
        if (port <= 0) {
            port = (cfg != null) ? cfg.httpPort() : eu.kalafatic.tryst.core.port.ServicePortIdentifier.DEFAULT_HTTP_PORT;
        }
        String contextPath = (cfg != null) ? cfg.contextPath() : "/tryst";
        return "http://127.0.0.1:" + port + contextPath;
    }

    /** Performs a GET against the live server; prints a clear error on failure. */
    private String httpGet(ApplicationLifecycleCoordinator coord, String path) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(resolveBaseUrl(coord) + path))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                System.out.println("HTTP " + response.statusCode() + " from " + path
                        + " (the endpoint may require authentication outside development mode).");
                return null;
            }
            return response.body();
        } catch (Exception e) {
            System.out.println("Failed to query " + path + ": " + e.getMessage());
            return null;
        }
    }

    /** Resolves the current workspace root (falling back to {@code null} when unloaded). */
    private Path resolveWorkspaceRoot(ApplicationLifecycleCoordinator coord) {
        if (coord != null && coord.getWorkspaceService() != null) {
            try {
                var ws = coord.getWorkspaceService().getCurrentWorkspace();
                if (ws != null && ws.getRootPath() != null && Files.isDirectory(ws.getRootPath())) {
                    return ws.getRootPath().toAbsolutePath();
                }
            } catch (Exception e) {
                TrystLog.warn(TrystServerApplication.class, "Could not resolve workspace root: " + e.getMessage());
            }
        }
        return null;
    }

    private Path resolveLogFile() {
        String logDirProp = System.getProperty("tryst.log.dir");
        if (logDirProp != null && !logDirProp.isBlank()) {
            return Path.of(logDirProp).resolve("tryst.log").toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home", "."), ".tryst", "logs", "tryst.log")
                .toAbsolutePath().normalize();
    }

    /**
     * Minimal JSON helpers for the query commands (flat object arrays as produced by
     * {@code /api/models} and {@code /api/tasks}); matches the parsing style already used
     * by the HTTP server itself, avoiding new dependencies.
     */
    private static List<Map<String, String>> extractJsonObjects(String json, String arrayField) {
        List<Map<String, String>> objects = new ArrayList<>();
        if (json == null) {
            return objects;
        }
        int start = json.indexOf("\"" + arrayField + "\"");
        if (start < 0) {
            return objects;
        }
        int bracket = json.indexOf('[', start);
        if (bracket < 0) {
            return objects;
        }
        int depth = 0;
        boolean inString = false;
        StringBuilder current = new StringBuilder();
        for (int i = bracket; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                current.append(c);
                if (c == '\\' && i + 1 < json.length()) {
                    current.append(json.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                current.append(c);
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (c == ']') {
                break;
            }
            if (depth == 0) {
                continue;
            }
            current.append(c);
            if (depth == 1 && c == '}') {
                objects.add(extractFlatObject(current.toString()));
                current.setLength(0);
            }
        }
        return objects;
    }

    /**
     * Parses a top-level JSON array of flat objects (as produced by
     * {@code /api/integration/applications}); same manual style as
     * {@link #extractJsonObjects(String, String)}, avoiding new dependencies.
     */
    private static List<Map<String, String>> extractJsonArray(String json) {
        List<Map<String, String>> objects = new ArrayList<>();
        if (json == null) {
            return objects;
        }
        int bracket = json.indexOf('[');
        if (bracket < 0) {
            return objects;
        }
        int depth = 0;
        boolean inString = false;
        StringBuilder current = new StringBuilder();
        for (int i = bracket; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                current.append(c);
                if (c == '\\' && i + 1 < json.length()) {
                    current.append(json.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                current.append(c);
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (c == ']') {
                break;
            }
            if (depth == 0) {
                continue;
            }
            current.append(c);
            if (depth == 1 && c == '}') {
                objects.add(extractFlatObject(current.toString()));
                current.setLength(0);
            }
        }
        return objects;
    }

    private static Map<String, String> extractFlatObject(String objectBody) {        Map<String, String> fields = new LinkedHashMap<>();
        java.util.regex.Pattern fieldPattern = java.util.regex.Pattern.compile(
                "\"([^\"]+)\"\\s*:\\s*(\"(?:\\\\\"|[^\"])*\"|-?[0-9a-zA-Z_.]+)");
        java.util.regex.Matcher matcher = fieldPattern.matcher(objectBody);
        while (matcher.find()) {
            String value = matcher.group(2);
            if (value.startsWith("\"")) {
                value = value.substring(1, value.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
            }
            fields.put(matcher.group(1), value);
        }
        return fields;
    }

    private static String jsonStringField(String json, String field) {
        if (json == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "\"" + field + "\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"").matcher(json);
        if (matcher.find()) {
            return matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return null;
    }

    private <T> T resolveService(Class<T> serviceClass) {
        try {
            Bundle bundle = FrameworkUtil.getBundle(getClass());
            if (bundle != null) {
                BundleContext context = bundle.getBundleContext();
                if (context != null) {
                    ServiceReference<T> ref = context.getServiceReference(serviceClass);
                    if (ref != null) {
                        return context.getService(ref);
                    }
                }
            }
        } catch (Throwable t) {
            TrystLog.warn(TrystServerApplication.class, "Failed to resolve " + serviceClass.getSimpleName() + " from OSGi: " + t.getMessage());
        }
        return null;
    }

    private void logDiagnostics() {
        try {
            var cfg = coordinator.getConfiguration();
            var readiness = coordinator.getServerReadinessService();
            var http = coordinator.getHttpService();
            var tcp = coordinator.getTcpService();

            TrystLog.info(TrystServerApplication.class, "=======================================================");
            TrystLog.info(TrystServerApplication.class, " TRYST Autonomous Development Server (Headless Mode)   ");
            TrystLog.info(TrystServerApplication.class, "=======================================================");
            TrystLog.info(TrystServerApplication.class, "Java Version     : " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
            TrystLog.info(TrystServerApplication.class, "Config Directory : " + (cfg != null ? cfg.configurationDirectory() : "unknown"));
            TrystLog.info(TrystServerApplication.class, "Workspace Root   : " + (cfg != null ? cfg.defaultWorkspaceDirectory() : "unknown"));
            TrystLog.info(TrystServerApplication.class, "Database Path    : " + (coordinator.getDatabaseManager() != null ? coordinator.getDatabaseManager().getDatabasePath() : "unknown"));
            if (http != null) {
                TrystLog.info(TrystServerApplication.class, "HTTP Bind Port   : " + http.getPort() + " (configured: " + (cfg != null ? cfg.httpPort() : "unknown") + ")");
            }
            if (tcp != null) {
                TrystLog.info(TrystServerApplication.class, "TCP Service      : 127.0.0.1:" + (cfg != null ? cfg.tcpPort() : "unknown"));
            }
            String canonicalUrl = (cfg != null && http != null) ? cfg.resolveEffectiveExternalUrl(http.getPort()) : "http://localhost/tryst";
            TrystLog.info(TrystServerApplication.class, "Canonical URL    : " + canonicalUrl);

            if (readiness != null) {
                var report = readiness.evaluateReadiness(coordinator.getCurrentStage());
                TrystLog.info(TrystServerApplication.class, "Server Readiness : " + (report.ready() ? "READY" : (report.degraded() ? "DEGRADED" : "NOT READY")));
                report.components().forEach((id, health) -> {
                    TrystLog.info(TrystServerApplication.class, "  - Component [" + id + "]: " + health.state() + " (mandatory=" + health.mandatory() + ")"
                            + (health.lastError() != null ? " error=" + health.lastError() : ""));
                });
            }
            TrystLog.info(TrystServerApplication.class, "TRYST Headless Server running. Awaiting shutdown signal...");
            TrystLog.info(TrystServerApplication.class, "=======================================================");
        } catch (Exception e) {
            TrystLog.warn(TrystServerApplication.class, "Diagnostics logging encountered non-fatal error: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            stopLatch.countDown();
        }
    }

    private void cleanup() {
        if (coordinator != null) {
            try {
                if (coordinator.getCurrentStage() == ApplicationLifecycleStage.APPLICATION_READY) {
                    coordinator.transitionTo(ApplicationLifecycleStage.WORKBENCH_RUNNING);
                }
                if (coordinator.getCurrentStage() == ApplicationLifecycleStage.WORKBENCH_RUNNING) {
                    coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_SHUTDOWN);
                }
                if (coordinator.getCurrentStage() == ApplicationLifecycleStage.APPLICATION_SHUTDOWN) {
                    coordinator.transitionTo(ApplicationLifecycleStage.SERVICE_DISPOSAL);
                }
                if (coordinator.getCurrentStage() == ApplicationLifecycleStage.SERVICE_DISPOSAL) {
                    coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_TERMINATED);
                }
            } catch (Exception e) {
                TrystLog.warn(TrystServerApplication.class, "Error during server application disposal: " + e.getMessage());
            }
        }
    }
}
