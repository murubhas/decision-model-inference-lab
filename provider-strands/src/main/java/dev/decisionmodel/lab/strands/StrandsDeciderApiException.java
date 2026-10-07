package dev.decisionmodel.lab.strands;

public class StrandsDeciderApiException extends RuntimeException {

    private final int statusCode;

    public StrandsDeciderApiException(int statusCode) {
        super("Strands Decider request failed with HTTP " + statusCode);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
