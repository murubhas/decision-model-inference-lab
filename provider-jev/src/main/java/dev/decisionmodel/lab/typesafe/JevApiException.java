package dev.decisionmodel.lab.typesafe;

public class JevApiException extends RuntimeException {

    private final int statusCode;

    public JevApiException(int statusCode) {
        super("TypeSafe System One request failed with HTTP " + statusCode);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
