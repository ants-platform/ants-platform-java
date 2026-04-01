package com.ants.platform.guardrails.providers;

import com.ants.platform.guardrails.AntsGuardrailsClient;
import com.ants.platform.guardrails.AntsTracer;
import com.ants.platform.guardrails.GuardrailResult;
import com.ants.platform.guardrails.GuardrailViolationException;
import com.ants.platform.guardrails.TracePayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Google Generative AI (Gemini) wrapper with guardrail enforcement and trace logging.
 *
 * <pre>{@code
 * var client = AntsGoogleGenAI.builder()
 *     .googleApiKey("AIza...")
 *     .antsApiKey("pk:sk")
 *     .agentId("agent_123")
 *     .build();
 *
 * JsonNode response = client.generateContent(
 *     "gemini-2.0-flash",
 *     List.of(Map.of("role", "user", "content", "Hello"))
 * );
 * }</pre>
 */
public class AntsGoogleGenAI {

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String googleApiKey;
    private final AntsGuardrailsClient guardrails;
    private final AntsTracer tracer;
    private final String agentId;
    private final String agentName;
    private final HttpClient httpClient;

    private AntsGoogleGenAI(Builder builder) {
        this.googleApiKey = builder.googleApiKey;
        this.agentId = builder.agentId;
        this.agentName = builder.agentName;
        this.guardrails = AntsGuardrailsClient.builder()
                .antsApiKey(builder.antsApiKey)
                .baseUrl(builder.antsBaseUrl)
                .agentId(builder.agentId)
                .guardrailServiceUrl(builder.guardrailServiceUrl)
                .build();
        this.tracer = new AntsTracer(builder.antsApiKey, builder.antsBaseUrl);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public static Builder builder() { return new Builder(); }

    /**
     * Generate content using Google Generative AI with guardrail checks.
     * A trace is logged asynchronously after each call.
     *
     * @param model    Gemini model ID (e.g. "gemini-2.0-flash")
     * @param messages list of message maps with "role" and "content" keys
     * @return raw JSON response from the generateContent API
     */
    public JsonNode generateContent(String model, List<Map<String, String>> messages) {
        String inputText = messages.stream()
                .filter(m -> "user".equals(m.get("role")))
                .map(m -> m.getOrDefault("content", ""))
                .reduce("", (a, b) -> a + "\n" + b)
                .trim();

        boolean guardrailActive = guardrails.isEnabled();
        GuardrailResult inputCheck = null;
        GuardrailResult outputCheck = null;

        // Input guardrail check
        List<Map<String, String>> effectiveMessages = messages;
        if (guardrailActive) {
            inputCheck = guardrails.checkInput(inputText);
            if (inputCheck.getResult() == GuardrailResult.Result.BLOCKED) {
                throw new GuardrailViolationException("input", inputCheck);
            }
            if (inputCheck.getResult() == GuardrailResult.Result.SANITIZED
                    && inputCheck.getSanitizedText() != null) {
                effectiveMessages = List.of(Map.of("role", "user", "content", inputCheck.getSanitizedText()));
            }
        }

        long startMs = System.currentTimeMillis();
        try {
            ObjectNode body = buildRequestBody(effectiveMessages);

            String url = BASE_URL + model + ":generateContent?key=" + googleApiKey;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = System.currentTimeMillis() - startMs;

            if (response.statusCode() >= 400) {
                throw new RuntimeException(
                        "Google GenAI API failed (" + response.statusCode() + "): " + response.body());
            }

            JsonNode json = MAPPER.readTree(response.body());
            String outputText = extractOutputText(json);

            // Output guardrail check
            if (guardrailActive && !outputText.isEmpty()) {
                outputCheck = guardrails.checkOutput(outputText, inputText);
                if (outputCheck.getResult() == GuardrailResult.Result.BLOCKED) {
                    throw new GuardrailViolationException("output", outputCheck);
                }
            }

            TracePayload.Usage usage = extractUsage(json);
            logTrace(model, messages, outputText, usage, latencyMs, inputCheck, outputCheck);

            return json;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Google GenAI API call failed", e);
        }
    }

    private ObjectNode buildRequestBody(List<Map<String, String>> messages) {
        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode contents = body.putArray("contents");
        for (Map<String, String> msg : messages) {
            ObjectNode content = contents.addObject();
            content.put("role", mapRole(msg.get("role")));
            ArrayNode parts = content.putArray("parts");
            parts.addObject().put("text", msg.get("content"));
        }
        return body;
    }

    /** Map standard roles to Google GenAI roles. */
    private String mapRole(String role) {
        if ("assistant".equals(role)) return "model";
        return "user";
    }

    private String extractOutputText(JsonNode json) {
        JsonNode candidates = json.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) return "";
        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : parts) {
            sb.append(part.path("text").asText(""));
        }
        return sb.toString();
    }

    private TracePayload.Usage extractUsage(JsonNode json) {
        JsonNode usageNode = json.path("usageMetadata");
        if (usageNode.isMissingNode()) return null;
        Integer inputTokens = usageNode.has("promptTokenCount")
                ? usageNode.get("promptTokenCount").asInt() : null;
        Integer outputTokens = usageNode.has("candidatesTokenCount")
                ? usageNode.get("candidatesTokenCount").asInt() : null;
        Integer total = usageNode.has("totalTokenCount")
                ? usageNode.get("totalTokenCount").asInt() : null;
        return new TracePayload.Usage(inputTokens, outputTokens, total);
    }

    private void logTrace(String model, List<Map<String, String>> messages, String outputText,
                           TracePayload.Usage usage, long latencyMs,
                           GuardrailResult inputCheck, GuardrailResult outputCheck) {
        TracePayload.Builder payload = TracePayload.builder()
                .model(model)
                .provider("google-genai")
                .inputMessages(messages)
                .outputText(outputText)
                .latencyMs(latencyMs)
                .agentId(agentId)
                .agentName(agentName);

        if (usage != null) payload.usage(usage);
        if (inputCheck != null || outputCheck != null) {
            payload.guardrailResults(inputCheck, outputCheck);
        }

        tracer.log(payload.build());
    }

    public static class Builder {
        private String googleApiKey;
        private String antsApiKey;
        private String antsBaseUrl;
        private String agentId;
        private String agentName;
        private String guardrailServiceUrl;

        public Builder googleApiKey(String key) { this.googleApiKey = key; return this; }
        public Builder antsApiKey(String key) { this.antsApiKey = key; return this; }
        public Builder antsBaseUrl(String url) { this.antsBaseUrl = url; return this; }
        public Builder agentId(String id) { this.agentId = id; return this; }
        public Builder agentName(String name) { this.agentName = name; return this; }
        public Builder guardrailServiceUrl(String url) { this.guardrailServiceUrl = url; return this; }

        public AntsGoogleGenAI build() {
            if (googleApiKey == null) throw new IllegalStateException("googleApiKey is required");
            if (antsApiKey == null) throw new IllegalStateException("antsApiKey is required");
            return new AntsGoogleGenAI(this);
        }
    }
}
