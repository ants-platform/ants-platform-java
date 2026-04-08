package com.ants.platform.guardrails.providers;

import com.ants.platform.guardrails.AntsGuardrailsClient;
import com.ants.platform.guardrails.AntsTracer;
import com.ants.platform.guardrails.GuardrailResult;
import com.ants.platform.guardrails.GuardrailTraceUtils;
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
 * OpenAI Chat Completions wrapper with guardrail enforcement and trace logging.
 *
 * <pre>{@code
 * var client = AntsOpenAI.builder()
 *     .openaiApiKey("sk-...")
 *     .antsApiKey("pk:sk")
 *     .agentId("agent_123")
 *     .build();
 *
 * JsonNode response = client.chatCompletion(
 *     "gpt-4o-mini", 100,
 *     List.of(Map.of("role", "user", "content", "Hello"))
 * );
 * }</pre>
 */
public class AntsOpenAI {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String openaiApiKey;
    private final AntsGuardrailsClient guardrails;
    private final AntsTracer tracer;
    private final String agentId;
    private final String agentName;
    private final HttpClient httpClient;

    private AntsOpenAI(Builder builder) {
        this.openaiApiKey = builder.openaiApiKey;
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
     * Send a chat completion request with guardrail checks on input and output.
     * A trace is logged asynchronously after each call.
     */
    public JsonNode chatCompletion(String model, int maxTokens, List<Map<String, String>> messages) {
        String inputText = messages.stream()
                .filter(m -> "user".equals(m.get("role")))
                .map(m -> m.getOrDefault("content", ""))
                .reduce("", (a, b) -> a + "\n" + b)
                .trim();

        boolean guardrailActive = guardrails.isEnabled();
        GuardrailResult inputCheck = null;
        GuardrailResult outputCheck = null;

        // Input guardrail check (only if agent has a policy configured)
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
        String effectiveInputText = GuardrailTraceUtils.effectiveText(inputText, inputCheck);

        long startMs = System.currentTimeMillis();
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", maxTokens);
            ArrayNode msgs = body.putArray("messages");
            for (Map<String, String> msg : effectiveMessages) {
                ObjectNode m = msgs.addObject();
                m.put("role", msg.get("role"));
                m.put("content", msg.get("content"));
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OPENAI_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + openaiApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = System.currentTimeMillis() - startMs;

            if (response.statusCode() >= 400) {
                throw new RuntimeException("OpenAI API failed (" + response.statusCode() + "): " + response.body());
            }

            JsonNode json = MAPPER.readTree(response.body());
            String outputText = json.path("choices").path(0).path("message").path("content").asText("");

            // Output guardrail check (only if agent has a policy configured)
            if (guardrailActive && !outputText.isEmpty()) {
                outputCheck = guardrails.checkOutput(outputText, effectiveInputText);
                if (outputCheck.getResult() == GuardrailResult.Result.BLOCKED) {
                    throw new GuardrailViolationException("output", outputCheck);
                }
                outputText = GuardrailTraceUtils.effectiveText(outputText, outputCheck);
                applySanitizedOutput(json, outputText);
            }

            // Extract usage from OpenAI response
            TracePayload.Usage usage = extractUsage(json);
            String guardrailResult = GuardrailTraceUtils.overallGuardrailResult(
                    guardrailActive, inputCheck, outputCheck);

            // Fire-and-forget trace logging
            logTrace(model, effectiveMessages, outputText, usage, latencyMs, inputCheck, outputCheck, guardrailResult);

            return json;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("OpenAI API call failed", e);
        }
    }

    private TracePayload.Usage extractUsage(JsonNode json) {
        JsonNode usageNode = json.path("usage");
        if (usageNode.isMissingNode()) return null;
        return new TracePayload.Usage(
                usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : null,
                usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asInt() : null,
                usageNode.has("total_tokens") ? usageNode.get("total_tokens").asInt() : null
        );
    }

    private void logTrace(String model, List<Map<String, String>> messages, String outputText,
                           TracePayload.Usage usage, long latencyMs,
                           GuardrailResult inputCheck, GuardrailResult outputCheck,
                           String guardrailResult) {
        TracePayload.Builder payload = TracePayload.builder()
                .model(model)
                .provider("openai")
                .inputMessages(messages)
                .outputText(outputText)
                .latencyMs(latencyMs)
                .guardrailResult(guardrailResult)
                .agentId(agentId)
                .agentName(agentName);

        if (usage != null) payload.usage(usage);
        if (inputCheck != null || outputCheck != null) {
            payload.guardrailResults(inputCheck, outputCheck);
        }

        tracer.log(payload.build());
    }

    private void applySanitizedOutput(JsonNode json, String outputText) {
        JsonNode message = json.path("choices").path(0).path("message");
        if (message instanceof ObjectNode objectNode) {
            objectNode.put("content", outputText);
        }
    }

    public static class Builder {
        private String openaiApiKey;
        private String antsApiKey;
        private String antsBaseUrl;
        private String agentId;
        private String agentName;
        private String guardrailServiceUrl;

        public Builder openaiApiKey(String key) { this.openaiApiKey = key; return this; }
        public Builder antsApiKey(String key) { this.antsApiKey = key; return this; }
        public Builder antsBaseUrl(String url) { this.antsBaseUrl = url; return this; }
        public Builder agentId(String id) { this.agentId = id; return this; }
        public Builder agentName(String name) { this.agentName = name; return this; }
        public Builder guardrailServiceUrl(String url) { this.guardrailServiceUrl = url; return this; }

        public AntsOpenAI build() {
            if (openaiApiKey == null) throw new IllegalStateException("openaiApiKey is required");
            if (antsApiKey == null) throw new IllegalStateException("antsApiKey is required");
            return new AntsOpenAI(this);
        }
    }
}
