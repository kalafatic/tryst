package eu.kalafatic.tryst.domain.ai;

import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.Workspace;

import java.util.function.Consumer;

/**
 * Optional provider SPI allowing external engines such as EVOLUTION (TEIP 1.0)
 * to be delegated to during TaskWorker AI implementation steps without coupling
 * domain or core to external classes.
 */
public interface ExternalAiDelegator {

    record DelegationResult(
            boolean handled,
            boolean success,
            String summary,
            String errorMessage
    ) {
        public static DelegationResult notHandled() {
            return new DelegationResult(false, false, "", "");
        }

        public static DelegationResult ok(String summary) {
            return new DelegationResult(true, true, summary != null ? summary : "", "");
        }

        public static DelegationResult error(String error) {
            return new DelegationResult(true, false, "", error != null ? error : "Unknown error");
        }
    }

    boolean isAvailableForTask(Task task);

    DelegationResult delegateImplementation(Task task, Workspace workspace, String instructions, String prompt, Consumer<String> logConsumer);
}
