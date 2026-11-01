package eu.kalafatic.tryst.core.error;

import eu.kalafatic.tryst.core.TrystException;

/**
 * Exception thrown when infrastructure operations (IO, process launch, network, storage) fail.
 */
public class TrystInfrastructureException extends TrystException {

    private static final long serialVersionUID = 1L;

    public TrystInfrastructureException(String message) {
        super(message);
    }

    public TrystInfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}
