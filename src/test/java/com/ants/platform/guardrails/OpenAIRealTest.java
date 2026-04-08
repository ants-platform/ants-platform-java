package com.ants.platform.guardrails;

import com.ants.platform.guardrails.providers.AntsOpenAI;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Tests OpenAI provider with real LLM calls using the Java SDK.
 * Run: java -cp ... com.ants.platform.guardrails.OpenAIRealTest
 */
public class OpenAIRealTest {

    static final String BASE_URL = "http://localhost:3000";
    static final String API_KEY = "pk-test-sdk:sk-test-sdk-trace-001";
    static final String AGENT_ID = "ffa6a5f9abc4297d";
    static final String OPENAI_KEY = System.getenv("OPENAI_API_KEY") != null
            ? System.getenv("OPENAI_API_KEY") : "";

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("  Java SDK — OpenAI Real LLM + Guardrail Test");
        System.out.println("=".repeat(60));
        System.out.println("  Platform: " + BASE_URL);
        System.out.println("  Agent ID: " + AGENT_ID);
        System.out.println("  Model:    gpt-4o-mini");
        System.out.println();

        // 1. Clean input
        runTest("1. Clean input (expect PASS + LLM response)",
                "What is 2 + 2? Just the number.", false, 10);

        // 2. PII input
        runTest("2. PII input (expect BLOCKED on input)",
                "Send invoice to john.doe@company.com with SSN 123-45-6789", true, 100);

        // 3. PCI input
        runTest("3. PCI input (expect BLOCKED on input)",
                "Charge card 4111-1111-1111-1111 CVV 123 for $500", true, 100);

        // 4. PHI input
        runTest("4. PHI input (expect BLOCKED on input)",
                "Patient diagnosed with Type 2 Diabetes, prescribed Metformin 500mg", true, 100);

        System.out.println("=".repeat(60));
        System.out.println("  Results: " + passed + " passed, " + failed + " failed out of " + (passed + failed));
        System.out.println("=".repeat(60));
        System.exit(failed > 0 ? 1 : 0);
    }

    static void runTest(String name, String message, boolean expectBlocked, int maxTokens) {
        System.out.println("  " + name);
        try {
            AntsOpenAI client = AntsOpenAI.builder()
                    .openaiApiKey(OPENAI_KEY)
                    .antsApiKey(API_KEY)
                    .antsBaseUrl(BASE_URL)
                    .agentId(AGENT_ID)
                    .agentName("pratik-test-agent")
                    .build();

            JsonNode response = client.chatCompletion("gpt-4o-mini", maxTokens,
                    List.of(Map.of("role", "user", "content", message)));

            String output = response.path("choices").path(0).path("message").path("content").asText("");
            if (expectBlocked) {
                System.out.println("     [FAIL] Expected BLOCKED, got: " + output.substring(0, Math.min(100, output.length())));
                failed++;
            } else {
                System.out.println("     [PASS] Response: " + output.substring(0, Math.min(100, output.length())));
                passed++;
            }
        } catch (GuardrailViolationException e) {
            if (expectBlocked) {
                System.out.println("     [PASS] " + e.getDirection().toUpperCase() + " BLOCKED — risk: "
                        + e.getGuardrailResult().getRiskScore() + " (" + e.getGuardrailResult().getRiskLevel() + ")");
                System.out.println("     Violations: " + e.getGuardrailResult().getViolations());
                passed++;
            } else {
                System.out.println("     [FAIL] Unexpected BLOCKED: " + e.getMessage());
                failed++;
            }
        } catch (Exception e) {
            System.out.println("     [ERROR] " + e.getMessage());
            failed++;
        }
        System.out.println();
    }
}
