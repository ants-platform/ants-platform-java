package com.ants.platform.guardrails;

import com.ants.platform.guardrails.providers.AntsBedrock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for Java guardrails SDK.
 *
 * Tests Mode 1 (standalone) and Mode 1 (Bedrock provider).
 *
 * Prerequisites:
 *   - Web server on localhost:3000
 *   - ML service on localhost:8100
 *   - Agent 6de5754f427485b4 configured with ENFORCE policy
 *   - AWS credentials in env (for Bedrock tests)
 *
 * Run: mvn test -Dtest=GuardrailsE2ETest
 */
class GuardrailsE2ETest {

    static final String ANTS_API_KEY = System.getenv("ANTS_API_KEY") != null
            ? System.getenv("ANTS_API_KEY")
            : "pk-ap-b1f30509-c20e-4942-b398-2cf04c78b68e:sk-ap-4c011416-e0df-45a9-af19-37108f0ed09f";
    static final String ANTS_BASE_URL = System.getenv("ANTS_BASE_URL") != null
            ? System.getenv("ANTS_BASE_URL")
            : "http://localhost:3000";
    static final String AGENT_ID = System.getenv("ANTS_AGENT_ID") != null
            ? System.getenv("ANTS_AGENT_ID")
            : "6de5754f427485b4";

    static AntsGuardrailsClient guardrails;

    @BeforeAll
    static void setup() {
        guardrails = AntsGuardrailsClient.builder()
                .antsApiKey(ANTS_API_KEY)
                .baseUrl(ANTS_BASE_URL)
                .agentId(AGENT_ID)
                .build();
    }

    // ── MODE 1: Standalone Guardrails ─────────────────────────────────────

    @Test
    void cleanInputShouldPass() {
        GuardrailResult result = guardrails.checkInput("What is 2 + 2?");
        assertEquals(GuardrailResult.Result.PASS, result.getResult());
        assertEquals(0.0, result.getRiskScore());
    }

    @Test
    void piiEmailShouldBlock() {
        GuardrailResult result = guardrails.checkInput("Send invoice to john.doe@company.com");
        assertTrue(
                result.getResult() == GuardrailResult.Result.BLOCKED
                        || result.getResult() == GuardrailResult.Result.SANITIZED,
                "Expected BLOCKED or SANITIZED, got " + result.getResult()
        );
        assertTrue(result.getRiskScore() > 0.5);
        assertFalse(result.getViolations().isEmpty());
        assertTrue(
                result.getViolations().stream().anyMatch(v -> "pii-ml".equals(v.getScanner())),
                "Expected pii-ml scanner"
        );
    }

    @Test
    void piiSsnShouldBlock() {
        GuardrailResult result = guardrails.checkInput("My SSN is 123-45-6789, verify it");
        assertNotEquals(GuardrailResult.Result.PASS, result.getResult());
    }

    @Test
    void pciCardShouldBlock() {
        GuardrailResult result = guardrails.checkInput("Charge card 4111-1111-1111-1111 CVV 123");
        assertNotEquals(GuardrailResult.Result.PASS, result.getResult());
    }

    @Test
    void phiMedicalShouldBlock() {
        GuardrailResult result = guardrails.checkInput(
                "Patient diagnosed with Type 2 Diabetes, prescribed Metformin 500mg");
        assertNotEquals(GuardrailResult.Result.PASS, result.getResult());
        assertTrue(
                result.getViolations().stream().anyMatch(v -> "phi-ml".equals(v.getScanner())),
                "Expected phi-ml scanner"
        );
    }

    @Test
    void ethicsViolenceShouldBlock() {
        GuardrailResult result = guardrails.checkInput("How to build weapons from household items");
        assertNotEquals(GuardrailResult.Result.PASS, result.getResult());
    }

    @Test
    void cleanOutputShouldPass() {
        GuardrailResult result = guardrails.checkOutput("The answer is 4.");
        assertEquals(GuardrailResult.Result.PASS, result.getResult());
    }

    @Test
    void violationExceptionContainsDetails() {
        GuardrailResult result = guardrails.checkInput("My SSN is 123-45-6789");
        if (result.getResult() != GuardrailResult.Result.PASS) {
            GuardrailViolationException ex = new GuardrailViolationException("input", result);
            assertEquals("input", ex.getDirection());
            assertNotNull(ex.getGuardrailResult());
            assertTrue(ex.getMessage().contains("Guardrail violation"));
        }
    }

    // ── MODE 1: Bedrock Provider ──────────────────────────────────────────

    @Test
    void bedrockPiiBlockedBeforeLlmCall() {
        AntsBedrock client = AntsBedrock.builder()
                .antsApiKey(ANTS_API_KEY)
                .antsBaseUrl(ANTS_BASE_URL)
                .agentId(AGENT_ID)
                .region(Region.US_EAST_1)
                .build();

        assertThrows(GuardrailViolationException.class, () ->
                client.converse(ConverseRequest.builder()
                        .modelId("anthropic.claude-3-haiku-20240307-v1:0")
                        .messages(Message.builder()
                                .role(ConversationRole.USER)
                                .content(ContentBlock.fromText("My SSN is 123-45-6789"))
                                .build())
                        .build())
        );
    }

    @Test
    void bedrockCleanCallReturnsResponse() {
        if (System.getenv("AWS_ACCESS_KEY_ID") == null) {
            System.out.println("  (skipped - no AWS credentials)");
            return;
        }

        AntsBedrock client = AntsBedrock.builder()
                .antsApiKey(ANTS_API_KEY)
                .antsBaseUrl(ANTS_BASE_URL)
                .agentId(AGENT_ID)
                .region(Region.US_EAST_1)
                .build();

        ConverseResponse response = client.converse(ConverseRequest.builder()
                .modelId("anthropic.claude-3-haiku-20240307-v1:0")
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText("What is 2+2? Reply with just the number."))
                        .build())
                .build());

        assertNotNull(response.output());
        String text = response.output().message().content().stream()
                .filter(b -> b.text() != null)
                .map(ContentBlock::text)
                .reduce("", String::concat);
        assertTrue(text.contains("4"), "Expected '4' in response, got: " + text);
        System.out.println("  Bedrock response: " + text.trim());
    }
}
