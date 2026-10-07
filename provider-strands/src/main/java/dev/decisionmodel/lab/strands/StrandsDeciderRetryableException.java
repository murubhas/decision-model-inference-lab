package dev.decisionmodel.lab.strands;

public final class StrandsDeciderRetryableException extends StrandsDeciderApiException {

    public StrandsDeciderRetryableException(int statusCode) {
        super(statusCode);
    }
}
