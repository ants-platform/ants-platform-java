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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Google Vertex AI (Gemini) wrapper with guardrail enforcement and trace logging.
 *
 * <p>Uses the Vertex AI REST API with Application Default Credentials (ADC).
 * The access token is obtained via {@code gcloud auth print-access-token} or
 * can be provided directly via the builder.
 *
 * <pre>{@code
 * var client = AntsVertexAI.builder()
 *     .projectId("my-gcp-project")
 *     .location("us-central1")
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
public class AntsVertexAI {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String projectId;
    private final String location;
    private final String accessToken;
    private final AntsGuardrailsClient guardrails;
    private final AntsTracer tracer;
    private final String agentId;
    private final String agentName;
    private final HttpClient httpClient;

    private AntsVertexAI(Builder builder) {
        this.projectId = builder.projectId;
        this.location = builder.location;
        this.agentId = builder.agentId;
        this.agentName = builder.agentName;
        this.accessToken = builder.accessToken;
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
     * Generate content using Vertex AI with guardrail checks.
     * A trace is logged asynchronously after each call.
     *
     * @param model    Gemini model ID (e.g. "gemini-2.0-flash")
     * @param messages list of message maps with "role" and "content" keys
     * @return raw JSON response from the Vertex AI generateContent API
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
        String effectiveInputText = GuardrailTraceUtils.effectiveText(inputText, inputCheck);

        long startMs = System.currentTimeMillis();
        try {
            ObjectNode body = buildRequestBody(effectiveMessages);

            String url = String.format(
                    "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent",
                    location, projectId, location, model);

            String token = resolveAccessToken();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = System.currentTimeMillis() - startMs;

            if (response.statusCode() >= 400) {
                throw new RuntimeException(
                        "Vertex AI API failed (" + response.statusCode() + "): " + response.body());
            }

            JsonNode json = MAPPER.readTree(response.body());
            String outputText = extractOutputText(json);

            // Output guardrail check
            if (guardrailActive && !outputText.isEmpty()) {
                outputCheck = guardrails.checkOutput(outputText, effectiveInputText);
                if (outputCheck.getResult() == GuardrailResult.Result.BLOCKED) {
                    throw new GuardrailViolationException("output", outputCheck);
                }
                outputText = GuardrailTraceUtils.effectiveText(outputText, outputCheck);
                applySanitizedOutput(json, outputText);
            }

            TracePayload.Usage usage = extractUsage(json);
            String guardrailResult = GuardrailTraceUtils.overallGuardrailResult(
                    guardrailActive, inputCheck, outputCheck);
            logTrace(model, effectiveMessages, outputText, usage, latencyMs, inputCheck, outputCheck, guardrailResult);

            return json;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Vertex AI API call failed", e);
        }
    }

    private String resolveAccessToken() {
        if (accessToken != null && !accessToken.isEmpty()) {
            return accessToken;
        }
        // Fall back to gcloud CLI for ADC
        try {
            ProcessBuilder pb = new ProcessBuilder("gcloud", "auth", "print-access-token");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String token = reader.readLine();
                int exitCode = process.waitFor();
                if (exitCode != 0 || token == null || token.isEmpty()) {
                    throw new RuntimeException(
                            "Failed to get access token from gcloud CLI (exit code " + exitCode + "). "
                            + "Provide an accessToken via the builder or configure Application Default Credentials.");
                }
                return token.trim();
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(
                    "Failed to get access token from gcloud CLI. "
                    + "Provide an accessToken via the builder or configure Application Default Credentials.", e);
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

    /** Map standard roles to Vertex AI roles. */
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
                           GuardrailResult inputCheck, GuardrailResult outputCheck,
                           String guardrailResult) {
        TracePayload.Builder payload = TracePayload.builder()
                .model(model)
                .provider("vertex-ai")
                .inputMessages(messages)
                .outputText(outputText)
                .latencyMs(latencyMs)
                .guardrailResult(guardrailResult)
                .agentId(agentId);

        if (usage != null) payload.usage(usage);
        if (inputCheck != null || outputCheck != null) {
            payload.guardrailResults(inputCheck, outputCheck);
        }

        tracer.log(payload.build());
    }

    private void applySanitizedOutput(JsonNode json, String outputText) {
        JsonNode candidates = json.path("candidates");
        if (!(candidates instanceof ArrayNode candidatesArray)) return;

        boolean replaced = false;
        for (JsonNode candidate : candidatesArray) {
            JsonNode parts = candidate.path("content").path("parts");
            if (!(parts instanceof ArrayNode partsArray)) continue;
            for (JsonNode part : partsArray) {
                if (!(part instanceof ObjectNode partNode)) continue;
                if (!partNode.has("text")) continue;
                partNode.put("text", replaced ? "" : outputText);
                replaced = true;
            }
        }
    }

    public static class Builder {
        private String projectId;
        private String location = "us-central1";
        private String accessToken;
        private String antsApiKey;
        private String antsBaseUrl;
        private String agentId;
        private String agentName;
        private String guardrailServiceUrl;

        public Builder projectId(String id) { this.projectId = id; return this; }
        public Builder location(String loc) { this.location = loc; return this; }
        public Builder accessToken(String token) { this.accessToken = token; return this; }
        public Builder antsApiKey(String key) { this.antsApiKey = key; return this; }
        public Builder antsBaseUrl(String url) { this.antsBaseUrl = url; return this; }
        public Builder agentId(String id) { this.agentId = id; return this; }
        public Builder agentName(String name) { this.agentName = name; return this; }
        public Builder guardrailServiceUrl(String url) { this.guardrailServiceUrl = url; return this; }

        public AntsVertexAI build() {
            if (projectId == null) throw new IllegalStateException("projectId is required");
            if (antsApiKey == null) throw new IllegalStateException("antsApiKey is required");
            return new AntsVertexAI(this);
        }
    }
}
