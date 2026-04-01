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
 * Anthropic Messages API wrapper with guardrail enforcement and trace logging.
 *
 * <pre>{@code
 * var client = AntsAnthropic.builder()
 *     .anthropicApiKey("sk-ant-...")
 *     .antsApiKey("pk:sk")
 *     .agentId("agent_123")
 *     .build();
 *
 * JsonNode response = client.createMessage(
 *     "claude-sonnet-4-20250514", 1024,
 *     List.of(Map.of("role", "user", "content", "Hello"))
 * );
 * }</pre>
 */
public class AntsAnthropic {

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String anthropicApiKey;
    private final AntsGuardrailsClient guardrails;
    private final AntsTracer tracer;
    private final String agentId;
    private final String agentName;
    private final HttpClient httpClient;

    private AntsAnthropic(Builder builder) {
        this.anthropicApiKey = builder.anthropicApiKey;
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
     * Send a message creation request with guardrail checks on input and output.
     * A trace is logged asynchronously after each call.
     *
     * @param model     Anthropic model ID (e.g. "claude-sonnet-4-20250514")
     * @param maxTokens maximum tokens in the response
     * @param messages  list of message maps with "role" and "content" keys
     * @return raw JSON response from the Anthropic Messages API
     */
    public JsonNode createMessage(String model, int maxTokens, List<Map<String, String>> messages) {
        return createMessage(model, maxTokens, messages, null);
    }

    /**
     * Send a message creation request with an optional system prompt.
     *
     * @param model        Anthropic model ID
     * @param maxTokens    maximum tokens in the response
     * @param messages     list of message maps with "role" and "content" keys
     * @param systemPrompt optional system prompt (may be null)
     * @return raw JSON response from the Anthropic Messages API
     */
    public JsonNode createMessage(String model, int maxTokens, List<Map<String, String>> messages,
                                   String systemPrompt) {
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
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", maxTokens);
            if (systemPrompt != null) {
                body.put("system", systemPrompt);
            }

            ArrayNode msgs = body.putArray("messages");
            for (Map<String, String> msg : effectiveMessages) {
                ObjectNode m = msgs.addObject();
                m.put("role", msg.get("role"));
                m.put("content", msg.get("content"));
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ANTHROPIC_URL))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", anthropicApiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = System.currentTimeMillis() - startMs;

            if (response.statusCode() >= 400) {
                throw new RuntimeException(
                        "Anthropic API failed (" + response.statusCode() + "): " + response.body());
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
            throw new RuntimeException("Anthropic API call failed", e);
        }
    }

    private String extractOutputText(JsonNode json) {
        JsonNode content = json.path("content");
        if (!content.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                sb.append(block.path("text").asText(""));
            }
        }
        return sb.toString();
    }

    private TracePayload.Usage extractUsage(JsonNode json) {
        JsonNode usageNode = json.path("usage");
        if (usageNode.isMissingNode()) return null;
        Integer inputTokens = usageNode.has("input_tokens") ? usageNode.get("input_tokens").asInt() : null;
        Integer outputTokens = usageNode.has("output_tokens") ? usageNode.get("output_tokens").asInt() : null;
        Integer total = (inputTokens != null && outputTokens != null) ? inputTokens + outputTokens : null;
        return new TracePayload.Usage(inputTokens, outputTokens, total);
    }

    private void logTrace(String model, List<Map<String, String>> messages, String outputText,
                           TracePayload.Usage usage, long latencyMs,
                           GuardrailResult inputCheck, GuardrailResult outputCheck) {
        TracePayload.Builder payload = TracePayload.builder()
                .model(model)
                .provider("anthropic")
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
        private String anthropicApiKey;
        private String antsApiKey;
        private String antsBaseUrl;
        private String agentId;
        private String agentName;
        private String guardrailServiceUrl;

        public Builder anthropicApiKey(String key) { this.anthropicApiKey = key; return this; }
        public Builder antsApiKey(String key) { this.antsApiKey = key; return this; }
        public Builder antsBaseUrl(String url) { this.antsBaseUrl = url; return this; }
        public Builder agentId(String id) { this.agentId = id; return this; }
        public Builder agentName(String name) { this.agentName = name; return this; }
        public Builder guardrailServiceUrl(String url) { this.guardrailServiceUrl = url; return this; }

        public AntsAnthropic build() {
            if (anthropicApiKey == null) throw new IllegalStateException("anthropicApiKey is required");
            if (antsApiKey == null) throw new IllegalStateException("antsApiKey is required");
            return new AntsAnthropic(this);
        }
    }
}
