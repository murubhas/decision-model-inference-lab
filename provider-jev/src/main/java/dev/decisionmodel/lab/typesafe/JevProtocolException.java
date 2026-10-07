package dev.decisionmodel.lab.typesafe;

public final class JevProtocolException extends RuntimeException {

    public JevProtocolException(String message) {
        super(message);
    }

    public JevProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
