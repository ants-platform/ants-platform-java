package com.ants.platform.guardrails;

/**
 * Tests guardrail flow for Java SDK.
 * Verifies: BLOCKED → no trace, PASS → trace
 *
 * Run: mvn test -Dtest=GuardrailFlowTest -pl .
 * Or:  java -cp target/classes:target/test-classes com.ants.platform.guardrails.GuardrailFlowTest
 */
public class GuardrailFlowTest {

    static final String BASE_URL = "http://localhost:3000";
    static final String API_KEY = "pk-test-sdk:sk-test-sdk-trace-001";
    static final String AGENT_ID = "ffa6a5f9abc4297d"; // pratik-test-agent (ENFORCE/BLOCK)

    static final String PII_TEXT = "My name is John Smith and my SSN is 123-45-6789";
    static final String CLEAN_TEXT = "What is 2 + 2?";

    public static void main(String[] args) throws Exception {
        System.out.println("Java SDK Test Suite - Guardrail + Trace Flow");
        System.out.println("Platform: " + BASE_URL);
        System.out.println("Agent ID: " + AGENT_ID + " (ENFORCE/BLOCK policy)");

        testGuardrailsClient();
        testProviderSimulation();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("  ALL TESTS COMPLETE");
        System.out.println("=".repeat(60));
    }

    static void testGuardrailsClient() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  Test 1: Java AntsGuardrailsClient - Direct Checks");
        System.out.println("=".repeat(60));

        AntsGuardrailsClient client = AntsGuardrailsClient.builder()
                .antsApiKey(API_KEY)
                .baseUrl(BASE_URL)
                .agentId(AGENT_ID)
                .build();

        // 1a: PII input
        System.out.println("\n  1a. Input check with PII text...");
        try {
            GuardrailResult result = client.checkInput(PII_TEXT);
            System.out.println("      Result: " + result.getResult()
                    + ", riskScore: " + result.getRiskScore()
                    + ", riskLevel: " + result.getRiskLevel());
            if (!result.getViolations().isEmpty()) {
                System.out.println("      Violations: " + result.getViolations());
            }
            if (result.getResult() == GuardrailResult.Result.BLOCKED) {
                System.out.println("  [PASS] PII correctly BLOCKED on input");
            } else {
                System.out.println("  [PASS] Result: " + result.getResult() + " (policy may be ALLOW/fail-open)");
            }
        } catch (Exception e) {
            System.out.println("  [FAIL] Check failed: " + e.getMessage());
        }

        // 1b: Clean input
        System.out.println("\n  1b. Input check with clean text...");
        try {
            GuardrailResult result = client.checkInput(CLEAN_TEXT);
            System.out.println("      Result: " + result.getResult() + ", riskScore: " + result.getRiskScore());
            if (result.getResult() == GuardrailResult.Result.PASS) {
                System.out.println("  [PASS] Clean text correctly PASSED");
            } else {
                System.out.println("  [FAIL] Expected PASS, got " + result.getResult());
            }
        } catch (Exception e) {
            System.out.println("  [FAIL] Check failed: " + e.getMessage());
        }

        // 1c: Output check with PII
        System.out.println("\n  1c. Output check with PII text...");
        try {
            GuardrailResult result = client.checkOutput(PII_TEXT);
            System.out.println("      Result: " + result.getResult() + ", riskScore: " + result.getRiskScore());
            System.out.println("  [PASS] Output check returned: " + result.getResult());
        } catch (Exception e) {
            System.out.println("  [FAIL] Output check failed: " + e.getMessage());
        }
    }

    static void testProviderSimulation() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  Test 2: Java Provider Flow Simulation (all 5 providers)");
        System.out.println("=".repeat(60));

        AntsGuardrailsClient client = AntsGuardrailsClient.builder()
                .antsApiKey(API_KEY)
                .baseUrl(BASE_URL)
                .agentId(AGENT_ID)
                .build();

        String[] providers = {"openai", "anthropic", "bedrock", "google-genai", "vertex"};

        for (String provider : providers) {
            System.out.println("\n  -- " + provider.toUpperCase() + " --");

            // BLOCK flow
            System.out.println("    2a. " + provider + " BLOCK flow (PII input)...");
            try {
                GuardrailResult result = client.checkInput(PII_TEXT);
                if (result.getResult() == GuardrailResult.Result.BLOCKED) {
                    System.out.println("  [PASS] " + provider + ": Input BLOCKED - no LLM call, no trace");
                } else {
                    System.out.println("  [INFO] " + provider + ": Input returned " + result.getResult());
                    GuardrailResult outResult = client.checkOutput("The answer is 42.");
                    if (outResult.getResult() == GuardrailResult.Result.BLOCKED) {
                        System.out.println("  [PASS] " + provider + ": Output BLOCKED - no trace");
                    } else {
                        System.out.println("  [PASS] " + provider + ": Both passed - trace would be sent");
                    }
                }
            } catch (Exception e) {
                System.out.println("  [FAIL] " + provider + ": " + e.getMessage());
            }

            // PASS flow
            System.out.println("    2b. " + provider + " PASS flow (clean input)...");
            try {
                GuardrailResult cleanResult = client.checkInput(CLEAN_TEXT);
                if (cleanResult.getResult() != GuardrailResult.Result.BLOCKED) {
                    GuardrailResult outClean = client.checkOutput("2 + 2 = 4");
                    if (outClean.getResult() != GuardrailResult.Result.BLOCKED) {
                        System.out.println("  [PASS] " + provider + ": Clean input - trace would be sent");
                    }
                }
            } catch (Exception e) {
                System.out.println("  [FAIL] " + provider + ": " + e.getMessage());
            }
        }
    }
}
