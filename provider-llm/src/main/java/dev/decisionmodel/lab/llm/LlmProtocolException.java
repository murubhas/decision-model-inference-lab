package dev.decisionmodel.lab.llm;

public class LlmProtocolException extends RuntimeException {

    public LlmProtocolException(String message) {
        super(message);
    }

    public LlmProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
