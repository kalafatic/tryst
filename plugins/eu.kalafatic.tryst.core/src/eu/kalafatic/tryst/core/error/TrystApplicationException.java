package eu.kalafatic.tryst.core.error;

import eu.kalafatic.tryst.core.TrystException;

/**
 * Exception thrown when application-level use cases, workflows, or lifecycle coordination fail.
 */
public class TrystApplicationException extends TrystException {

    private static final long serialVersionUID = 1L;

    public TrystApplicationException(String message) {
        super(message);
    }

    public TrystApplicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
