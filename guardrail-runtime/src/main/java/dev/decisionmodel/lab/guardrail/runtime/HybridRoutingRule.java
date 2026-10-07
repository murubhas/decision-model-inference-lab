package dev.decisionmodel.lab.guardrail.runtime;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public record HybridRoutingRule(
        HybridQuestionRoute route,
        Set<String> requiredStateKeys) {

    public HybridRoutingRule {
        if (route == null) {
            throw new IllegalArgumentException("route must not be null");
        }
        if (requiredStateKeys == null || requiredStateKeys.isEmpty()) {
            throw new IllegalArgumentException("requiredStateKeys must not be empty");
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String key : requiredStateKeys) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("requiredStateKeys must not contain blank values");
            }
            keys.add(key);
        }
        requiredStateKeys = Collections.unmodifiableSet(keys);
    }
}
