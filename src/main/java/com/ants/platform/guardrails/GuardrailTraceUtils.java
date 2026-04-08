package com.ants.platform.guardrails;

public final class GuardrailTraceUtils {

    private GuardrailTraceUtils() {}

    public static String overallGuardrailResult(
            boolean guardrailActive,
            GuardrailResult inputCheck,
            GuardrailResult outputCheck
    ) {
        if (!guardrailActive) {
            return "NOT_CONFIGURED";
        }
        if (isSanitized(inputCheck) || isSanitized(outputCheck)) {
            return "SANITIZED";
        }
        return "PASS";
    }

    public static String effectiveText(String originalText, GuardrailResult check) {
        if (isSanitized(check) && check.getSanitizedText() != null) {
            return check.getSanitizedText();
        }
        return originalText;
    }

    private static boolean isSanitized(GuardrailResult check) {
        return check != null && check.getResult() == GuardrailResult.Result.SANITIZED;
    }
}
