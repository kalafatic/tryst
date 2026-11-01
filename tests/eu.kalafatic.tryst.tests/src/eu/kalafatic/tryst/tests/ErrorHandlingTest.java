package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.TrystException;
import eu.kalafatic.tryst.core.error.TrystApplicationException;
import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ErrorHandlingTest {

    @Test
    void testDomainExceptionPreservesCauseAndMessage() {
        IllegalArgumentException cause = new IllegalArgumentException("Invalid state");
        TrystDomainException ex = new TrystDomainException("Domain invariant failed", cause);

        assertEquals("Domain invariant failed", ex.getMessage());
        assertSame(cause, ex.getCause());
        assertInstanceOf(TrystException.class, ex);
    }

    @Test
    void testApplicationException() {
        TrystApplicationException ex = new TrystApplicationException("Coordination error");
        assertEquals("Coordination error", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void testInfrastructureExceptionPreservesIOError() {
        IOException cause = new IOException("Disk failure");
        TrystInfrastructureException ex = new TrystInfrastructureException("Failed to persist workspace", cause);

        assertEquals("Failed to persist workspace", ex.getMessage());
        assertSame(cause, ex.getCause());
    }
}
