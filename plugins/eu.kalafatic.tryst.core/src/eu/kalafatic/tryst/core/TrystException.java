package eu.kalafatic.tryst.core;

/**
 * Root unchecked exception for TRYST platform errors.
 */
public class TrystException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TrystException(String message) {
        super(message);
    }

    public TrystException(String message, Throwable cause) {
        super(message, cause);
    }
}
