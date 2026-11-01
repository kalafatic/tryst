package eu.kalafatic.tryst.core.error;

import eu.kalafatic.tryst.core.TrystException;

/**
 * Exception thrown when a domain invariant or domain rule violation occurs.
 */
public class TrystDomainException extends TrystException {

    private static final long serialVersionUID = 1L;

    public TrystDomainException(String message) {
        super(message);
    }

    public TrystDomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
