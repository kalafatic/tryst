package eu.kalafatic.tryst.infrastructure.server;

/**
 * Server-side control surface for the TRYST AI inference service lifecycle.
 * Implemented by the application lifecycle coordinator so the embedded HTTP server
 * (and therefore the web/headless surfaces) can start, stop and observe the LLM
 * service without depending on the application bundle.
 */
public interface AiServiceController {

    boolean isAiServiceRunning();

    void startAiService();

    void stopAiService();
}
