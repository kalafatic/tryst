package eu.kalafatic.tryst.domain.repository;

import java.util.function.Supplier;

/**
 * Interface representing transaction management and atomic execution boundary.
 */
public interface UnitOfWork {

    void runInTransaction(Runnable action);

    <T> T supplyInTransaction(Supplier<T> action);
}
