package eu.kalafatic.tryst.domain.contextual;

/** Execution feedback channel (contract v1.0, §4). */
public interface ActionFeedback {

    void progress(String message);

    void info(String message);

    void error(String message, Throwable cause);

    static ActionFeedback silent() {
        return new ActionFeedback() {
            @Override
            public void progress(String message) {
            }

            @Override
            public void info(String message) {
            }

            @Override
            public void error(String message, Throwable cause) {
            }
        };
    }
}
