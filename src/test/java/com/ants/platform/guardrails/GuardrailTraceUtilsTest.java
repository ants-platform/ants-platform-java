package com.ants.platform.guardrails;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class GuardrailTraceUtilsTest {

    @Test
    void overallGuardrailResultReturnsNotConfiguredWhenDisabled() {
        assertEquals("NOT_CONFIGURED", GuardrailTraceUtils.overallGuardrailResult(false, null, null));
    }

    @Test
    void overallGuardrailResultReturnsSanitizedForSanitizedInput() {
        GuardrailResult inputCheck = new GuardrailResult();
        setResult(inputCheck, GuardrailResult.Result.SANITIZED);
        setSanitizedText(inputCheck, "[REDACTED]");

        assertEquals("SANITIZED", GuardrailTraceUtils.overallGuardrailResult(true, inputCheck, null));
    }

    @Test
    void overallGuardrailResultReturnsSanitizedForSanitizedOutput() {
        GuardrailResult outputCheck = new GuardrailResult();
        setResult(outputCheck, GuardrailResult.Result.SANITIZED);
        setSanitizedText(outputCheck, "[REDACTED]");

        assertEquals("SANITIZED", GuardrailTraceUtils.overallGuardrailResult(true, null, outputCheck));
    }

    @Test
    void effectiveTextUsesSanitizedTextEvenWhenEmpty() {
        GuardrailResult check = new GuardrailResult();
        setResult(check, GuardrailResult.Result.SANITIZED);
        setSanitizedText(check, "");

        assertEquals("", GuardrailTraceUtils.effectiveText("secret", check));
    }

    @Test
    void effectiveTextReturnsOriginalForPassResult() {
        GuardrailResult check = GuardrailResult.pass();

        assertEquals("safe", GuardrailTraceUtils.effectiveText("safe", check));
    }

    private void setResult(GuardrailResult result, GuardrailResult.Result value) {
        setField(result, "result", value);
    }

    private void setSanitizedText(GuardrailResult result, String value) {
        setField(result, "sanitizedText", value);
    }

    private void setField(GuardrailResult result, String fieldName, Object value) {
        try {
            var field = GuardrailResult.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(result, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to set field " + fieldName, e);
        }
    }
}
