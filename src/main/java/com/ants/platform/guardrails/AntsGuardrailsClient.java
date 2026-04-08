package com.ants.platform.guardrails;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP client for the ANTS guardrail ML-check API.
 *
 * <p>Guardrails are only active when an {@code agentId} is provided AND the
 * agent has a guardrail policy configured on the platform. If no policy exists,
 * the client caches that fact and skips HTTP calls on subsequent requests.
 *
 * <pre>{@code
 * var guardrails = AntsGuardrailsClient.builder()
 *     .antsApiKey("pk:sk")
 *     .agentId("agent_123")
 *     .build();
 *
 * GuardrailResult result = guardrails.checkInput("My SSN is 123-45-6789");
 * if (result.getResult() == GuardrailResult.Result.BLOCKED) {
 *     throw new GuardrailViolationException("input", result);
 * }
 * }</pre>
 */
public class AntsGuardrailsClient {

    private static final String DEFAULT_BASE_URL = "https://app.antsplatform.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final GuardrailResult PASS_RESULT = GuardrailResult.pass();

    private final String baseUrl;
    private final String agentId;
    private final String guardrailServiceUrl;
    private final String authHeader;
    private final HttpClient httpClient;

    /** Cache: null = not checked, true = policy exists, false = no policy. */
    private Boolean policyExists;

    private AntsGuardrailsClient(Builder builder) {
        this.baseUrl = builder.baseUrl != null ? builder.baseUrl : DEFAULT_BASE_URL;
        this.agentId = builder.agentId;
        this.guardrailServiceUrl = builder.guardrailServiceUrl;

        String[] parts = builder.antsApiKey.split(":");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException("Invalid antsApiKey format. Expected 'publicKey:secretKey'.");
        }
        String credentials = Base64.getEncoder().encodeToString(
                (parts[0] + ":" + parts[1]).getBytes(StandardCharsets.UTF_8));
        this.authHeader = "Basic " + credentials;

        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(builder.timeoutSeconds))
                .build();
    }

    public static Builder builder() { return new Builder(); }

    /** Whether guardrail checks are enabled (requires agentId). */
    public boolean isEnabled() {
        return agentId != null && !agentId.isEmpty();
    }

    public GuardrailResult checkInput(String text) {
        return check(text, "input", null);
    }

    public GuardrailResult checkOutput(String text) {
        return check(text, "output", null);
    }

    public GuardrailResult checkOutput(String text, String inputText) {
        return check(text, "output", inputText);
    }

    private GuardrailResult check(String text, String direction, String inputText) {
        // No agentId → no guardrail configured → skip
        if (agentId == null || agentId.isEmpty()) {
            return PASS_RESULT;
        }

        // Cached: we already know there's no policy for this agent
        if (Boolean.FALSE.equals(policyExists)) {
            return PASS_RESULT;
        }

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("text", text);
            body.put("direction", direction);
            body.put("agentId", agentId);
            if ("output".equals(direction) && inputText != null) {
                body.put("inputText", inputText);
            }

            String url = guardrailServiceUrl != null
                    ? guardrailServiceUrl + "/api/v1/guardrail-check"
                    : baseUrl + "/api/public/v1/guardrails/ml-check";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", authHeader)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException(
                        "ANTS guardrails check failed (" + response.statusCode() + "): " + response.body());
            }

            JsonNode json = MAPPER.readTree(response.body());
            GuardrailResult result = MAPPER.readValue(response.body(), GuardrailResult.class);

            // If no guardrailAction in response, no policy was found.
            // Cache this to skip future HTTP calls for this agent.
            if (!json.has("guardrailAction") || json.get("guardrailAction").isNull()) {
                policyExists = false;
                return PASS_RESULT;
            }

            policyExists = true;
            return result;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("ANTS guardrails check failed", e);
        }
    }

    public static class Builder {
        private String antsApiKey;
        private String baseUrl;
        private String agentId;
        private String guardrailServiceUrl;
        private int timeoutSeconds = 30;

        public Builder antsApiKey(String antsApiKey) { this.antsApiKey = antsApiKey; return this; }
        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        public Builder agentId(String agentId) { this.agentId = agentId; return this; }
        public Builder guardrailServiceUrl(String url) { this.guardrailServiceUrl = url; return this; }
        public Builder timeoutSeconds(int timeout) { this.timeoutSeconds = timeout; return this; }

        public AntsGuardrailsClient build() {
            if (antsApiKey == null || antsApiKey.isEmpty()) {
                throw new IllegalStateException("antsApiKey is required");
            }
            return new AntsGuardrailsClient(this);
        }
    }
}
