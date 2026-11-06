package eu.kalafatic.tryst.infrastructure.server;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.service.WorkspaceService;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Robust multi-threaded pure Java 21 TCP server integrated into the RCP lifecycle.
 * Dispatches text-based line commands (PING, STATUS, TASKS, PROJECTS) to client sockets
 * and safely delegates domain actions without UI/SWT coupling.
 */
public class TrystTcpServer implements TrystTcpService {

    private final String host;
    private final int port;
    private final WorkspaceService workspaceService;
    private eu.kalafatic.tryst.core.port.PortRegistry portRegistry;

    private ServerSocket serverSocket;
    private ExecutorService connectionPool;
    private Thread acceptorThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();

    public TrystTcpServer(String host, int port, WorkspaceService workspaceService) {
        this(host, port, workspaceService, null);
    }

    public TrystTcpServer(String host, int port, WorkspaceService workspaceService, eu.kalafatic.tryst.core.port.PortRegistry portRegistry) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.port = port;
        this.workspaceService = workspaceService;
        this.portRegistry = portRegistry;
    }

    public void setPortRegistry(eu.kalafatic.tryst.core.port.PortRegistry portRegistry) {
        this.portRegistry = portRegistry;
    }

    @Override
    public synchronized void start() {
        if (running.get()) {
            return;
        }

        try {
            this.serverSocket = new ServerSocket();
            this.serverSocket.setReuseAddress(true);
            this.serverSocket.bind(new InetSocketAddress(host, port));
            this.connectionPool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "tryst-tcp-worker-" + System.currentTimeMillis());
                t.setDaemon(true);
                return t;
            });
            this.running.set(true);

            this.acceptorThread = new Thread(this::acceptConnections, "tryst-tcp-acceptor");
            this.acceptorThread.setDaemon(true);
            this.acceptorThread.start();

            if (portRegistry != null) {
                portRegistry.registerBoundPort(eu.kalafatic.tryst.core.port.ServicePortIdentifier.TCP_SERVICE, serverSocket.getLocalPort());
            }

            TrystLog.info(TrystTcpServer.class, "TCP Server successfully started on " + host + ":" + serverSocket.getLocalPort());
        } catch (IOException e) {
            this.running.set(false);
            throw new TrystInfrastructureException("Failed to start TCP server on " + host + ":" + port, e);
        }
    }

    private void acceptConnections() {
        while (running.get() && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                activeSockets.add(clientSocket);
                connectionPool.submit(() -> handleClient(clientSocket));
            } catch (SocketException se) {
                // Expected when socket is closed during stop()
                break;
            } catch (IOException e) {
                if (running.get()) {
                    TrystLog.error(TrystTcpServer.class, "Error accepting TCP client connection: " + e.getMessage(), e);
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            writer.write("TRYST-TCP/1.0 READY\n");
            writer.flush();

            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                if ("QUIT".equalsIgnoreCase(line) || "EXIT".equalsIgnoreCase(line)) {
                    writer.write("BYE\n");
                    writer.flush();
                    break;
                }

                String response = processCommand(line);
                writer.write(response + "\n");
                writer.flush();
            }
        } catch (IOException e) {
            // Client disconnect or socket close
        } finally {
            activeSockets.remove(socket);
        }
    }

    private String processCommand(String commandLine) {
        String[] parts = commandLine.split("\\s+", 2);
        String cmd = parts[0].toUpperCase();

        switch (cmd) {
            case "PING" -> {
                return "PONG";
            }
            case "STATUS" -> {
                return "OK status=RUNNING host=" + host + " port=" + getPort();
            }
            case "PROJECTS" -> {
                if (workspaceService != null && workspaceService.getCurrentWorkspace() != null) {
                    var projects = workspaceService.getCurrentWorkspace().getProjects();
                    return "OK count=" + projects.size();
                }
                return "OK count=0";
            }
            case "TASKS" -> {
                if (workspaceService != null && workspaceService.getCurrentWorkspace() != null) {
                    var tasks = workspaceService.getCurrentWorkspace().getTasks();
                    return "OK count=" + tasks.size();
                }
                return "OK count=0";
            }
            default -> {
                return "ERR Unknown command: " + cmd;
            }
        }
    }

    @Override
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        TrystLog.info(TrystTcpServer.class, "Stopping TCP Server...");

        for (Socket s : activeSockets) {
            try {
                if (!s.isClosed()) {
                    s.close();
                }
            } catch (IOException ignored) {}
        }
        activeSockets.clear();

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                TrystLog.warn(TrystTcpServer.class, "Error closing ServerSocket: " + e.getMessage());
            }
            serverSocket = null;
        }

        if (connectionPool != null) {
            connectionPool.shutdownNow();
            try {
                if (!connectionPool.awaitTermination(2, TimeUnit.SECONDS)) {
                    TrystLog.warn(TrystTcpServer.class, "Connection pool did not terminate cleanly");
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            connectionPool = null;
        }

        TrystLog.info(TrystTcpServer.class, "TCP Server stopped.");
    }

    @Override
    public boolean isRunning() {
        return running.get() && serverSocket != null && !serverSocket.isClosed();
    }

    @Override
    public int getPort() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            return serverSocket.getLocalPort();
        }
        return port;
    }

    @Override
    public String getHost() {
        return host;
    }
}
