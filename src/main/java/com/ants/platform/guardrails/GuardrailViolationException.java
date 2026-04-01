package com.ants.platform.guardrails;

import java.util.stream.Collectors;

/**
 * Thrown when a guardrail check detects a policy violation and the policy
 * action is ENFORCE (block).
 */
public class GuardrailViolationException extends RuntimeException {

    private final String direction;
    private final GuardrailResult guardrailResult;

    public GuardrailViolationException(String direction, GuardrailResult result) {
        super(buildMessage(direction, result));
        this.direction = direction;
        this.guardrailResult = result;
    }

    public String getDirection() { return direction; }
    public GuardrailResult getGuardrailResult() { return guardrailResult; }

    private static String buildMessage(String direction, GuardrailResult result) {
        String violations = result.getViolations().stream()
                .map(Violation::toString)
                .collect(Collectors.joining("; "));
        return "Guardrail violation on " + direction + ": "
                + (violations.isEmpty() ? "Content blocked" : violations);
    }
}
