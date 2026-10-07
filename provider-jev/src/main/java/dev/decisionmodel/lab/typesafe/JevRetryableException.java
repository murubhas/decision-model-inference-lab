package dev.decisionmodel.lab.typesafe;

public final class JevRetryableException extends JevApiException {

    public JevRetryableException(int statusCode) {
        super(statusCode);
    }
}
