package com.ants.platform.guardrails;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class GuardrailResult {

    public enum Result { PASS, BLOCKED, SANITIZED }
    public enum RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

    @JsonProperty("result")
    private Result result;

    @JsonProperty("riskScore")
    private double riskScore;

    @JsonProperty("riskLevel")
    private RiskLevel riskLevel;

    @JsonProperty("sanitizedText")
    private String sanitizedText;

    @JsonProperty("violations")
    private List<Violation> violations;

    @JsonProperty("guardrailAction")
    private String guardrailAction;

    @JsonProperty("blockedMessage")
    private String blockedMessage;

    public GuardrailResult() {
        this.violations = Collections.emptyList();
    }

    /** Create a PASS result (no violations, no guardrail configured). */
    public static GuardrailResult pass() {
        GuardrailResult r = new GuardrailResult();
        r.result = Result.PASS;
        r.riskScore = 0.0;
        r.riskLevel = RiskLevel.LOW;
        return r;
    }

    public Result getResult() { return result; }
    public double getRiskScore() { return riskScore; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public String getSanitizedText() { return sanitizedText; }
    public String getGuardrailAction() { return guardrailAction; }
    public String getBlockedMessage() { return blockedMessage; }

    public List<Violation> getViolations() {
        return violations != null ? violations : Collections.emptyList();
    }
}
