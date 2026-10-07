package dev.decisionmodel.lab.benchmark;

public record ObservedNoulAnswer(double probabilityTrue, boolean predictedTrue) {

    public ObservedNoulAnswer {
        if (!Double.isFinite(probabilityTrue) || probabilityTrue < 0.0 || probabilityTrue > 1.0) {
            throw new IllegalArgumentException("probabilityTrue must be between 0 and 1");
        }
        if (predictedTrue != (probabilityTrue >= 0.5)) {
            throw new IllegalArgumentException("predictedTrue must use the fixed 0.5 classification threshold");
        }
    }

    public static ObservedNoulAnswer from(double probabilityTrue) {
        return new ObservedNoulAnswer(probabilityTrue, probabilityTrue >= 0.5);
    }
}
