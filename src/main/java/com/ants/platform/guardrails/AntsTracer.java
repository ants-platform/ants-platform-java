package com.ants.platform.guardrails;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fire-and-forget tracer that sends trace and generation events to the
 * ANTS ingestion API ({@code POST /api/public/ingestion}).
 *
 * <p>All logging is asynchronous -- callers are never blocked.
 *
 * <pre>{@code
 * AntsTracer tracer = new AntsTracer("pk:sk", "http://localhost:3000");
 * tracer.log(TracePayload.builder()
 *     .model("gpt-4o-mini")
 *     .provider("openai")
 *     .inputMessages(messages)
 *     .outputText(output)
 *     .build());
 * }</pre>
 */
public class AntsTracer {

    private static final Logger LOG = Logger.getLogger(AntsTracer.class.getName());
    private static final String DEFAULT_BASE_URL = "https://app.antsplatform.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String authHeader;
    private final HttpClient httpClient;
    private final ExecutorService executor;

    public AntsTracer(String antsApiKey) {
        this(antsApiKey, null);
    }

    public AntsTracer(String antsApiKey, String baseUrl) {
        this.baseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URL;

        String[] parts = antsApiKey.split(":");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid antsApiKey format. Expected 'publicKey:secretKey'.");
        }
        String credentials = Base64.getEncoder().encodeToString(
                (parts[0] + ":" + parts[1]).getBytes(StandardCharsets.UTF_8));
        this.authHeader = "Basic " + credentials;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ants-tracer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Log a trace + generation event asynchronously (fire-and-forget).
     * Errors are printed to stderr but never propagated to the caller.
     */
    public void log(TracePayload payload) {
        executor.submit(() -> {
            try {
                sendBatch(payload);
            } catch (Exception e) {
                LOG.log(Level.FINE, "Failed to send trace", e);
            }
        });
    }

    private void sendBatch(TracePayload payload) throws Exception {
        String traceId = payload.getTraceId() != null ? payload.getTraceId() : UUID.randomUUID().toString();
        String observationId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        ObjectNode batch = buildBatch(payload, traceId, observationId, now);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/public/ingestion"))
                .header("Content-Type", "application/json")
                .header("Authorization", authHeader)
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(batch)))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            LOG.log(Level.FINE, "Ingestion failed ({0}): {1}", new Object[]{response.statusCode(), response.body()});
        }
    }

    private ObjectNode buildBatch(TracePayload payload, String traceId, String observationId, String now) {
        ArrayNode tags = MAPPER.createArrayNode();
        tags.add("ants-sdk");
        if (payload.getTags() != null) {
            for (String tag : payload.getTags()) {
                tags.add(tag);
            }
        }
        if (payload.getAgentId() != null) {
            tags.add("agent:" + payload.getAgentId());
        }

        ObjectNode traceMetadata = buildTraceMetadata(payload);

        // trace-create event
        ObjectNode traceBody = MAPPER.createObjectNode();
        traceBody.put("id", traceId);
        traceBody.put("timestamp", now);
        traceBody.put("name", payload.getProvider() + "/" + payload.getModel());
        traceBody.set("input", toJsonNode(payload.getInputMessages()));
        traceBody.put("output", payload.getOutputText());
        if (payload.getUserId() != null) traceBody.put("userId", payload.getUserId());
        if (payload.getSessionId() != null) traceBody.put("sessionId", payload.getSessionId());
        traceBody.set("tags", tags);
        traceBody.set("metadata", traceMetadata);
        if (payload.getAgentId() != null) {
            traceBody.put("agentId", payload.getAgentId());
            if (payload.getAgentName() != null) {
                traceBody.put("agentName", payload.getAgentName());
            }
        }

        ObjectNode traceEvent = MAPPER.createObjectNode();
        traceEvent.put("id", UUID.randomUUID().toString());
        traceEvent.put("type", "trace-create");
        traceEvent.put("timestamp", now);
        traceEvent.set("body", traceBody);

        // generation-create event
        ObjectNode genBody = MAPPER.createObjectNode();
        genBody.put("id", observationId);
        genBody.put("traceId", traceId);
        genBody.put("name", payload.getModel());
        genBody.put("startTime", now);
        genBody.put("endTime", now);
        genBody.put("model", payload.getModel());
        genBody.set("input", toJsonNode(payload.getInputMessages()));
        genBody.put("output", payload.getOutputText());

        if (payload.getUsage() != null) {
            ObjectNode usage = MAPPER.createObjectNode();
            if (payload.getUsage().getInputTokens() != null) {
                usage.put("promptTokens", payload.getUsage().getInputTokens());
            }
            if (payload.getUsage().getOutputTokens() != null) {
                usage.put("completionTokens", payload.getUsage().getOutputTokens());
            }
            if (payload.getUsage().getTotalTokens() != null) {
                usage.put("totalTokens", payload.getUsage().getTotalTokens());
            }
            genBody.set("usage", usage);
        }

        if (payload.getAgentId() != null) {
            genBody.put("agentId", payload.getAgentId());
        }

        ObjectNode genMeta = MAPPER.createObjectNode();
        genMeta.put("provider", payload.getProvider());
        genMeta.put("model", payload.getModel());
        if (payload.getLatencyMs() != null) {
            genMeta.put("latency_ms", payload.getLatencyMs());
        }
        if (payload.getGuardrailResult() != null) {
            genMeta.put("guardrail_result", payload.getGuardrailResult());
        }
        genBody.set("metadata", genMeta);

        ObjectNode genEvent = MAPPER.createObjectNode();
        genEvent.put("id", UUID.randomUUID().toString());
        genEvent.put("type", "generation-create");
        genEvent.put("timestamp", now);
        genEvent.set("body", genBody);

        // Wrap in batch
        ArrayNode batchArray = MAPPER.createArrayNode();
        batchArray.add(traceEvent);
        batchArray.add(genEvent);

        ObjectNode root = MAPPER.createObjectNode();
        root.set("batch", batchArray);
        return root;
    }

    private ObjectNode buildTraceMetadata(TracePayload payload) {
        ObjectNode meta = MAPPER.createObjectNode();
        meta.put("source", "ants-sdk");
        meta.put("source_platform", payload.getProvider());
        meta.put("provider", payload.getProvider());
        meta.put("model", payload.getModel());
        meta.put("status", "success");

        if (payload.getAgentId() != null) meta.put("agent_id", payload.getAgentId());
        if (payload.getLatencyMs() != null) meta.put("latency_ms", String.valueOf(payload.getLatencyMs()));

        if (payload.getUsage() != null) {
            TracePayload.Usage usage = payload.getUsage();
            if (usage.getInputTokens() != null) meta.put("input_tokens", String.valueOf(usage.getInputTokens()));
            if (usage.getOutputTokens() != null) meta.put("output_tokens", String.valueOf(usage.getOutputTokens()));
            if (usage.getTotalTokens() != null) meta.put("total_tokens", String.valueOf(usage.getTotalTokens()));
            meta.set("usage", MAPPER.valueToTree(usage));
        }
        if (payload.getGuardrailResults() != null) {
            meta.set("guardrailResults", MAPPER.valueToTree(payload.getGuardrailResults()));
        }
        if (payload.getGuardrailResult() != null) {
            meta.put("guardrail_result", payload.getGuardrailResult());
        }
        if (payload.getExtraMetadata() != null) {
            payload.getExtraMetadata().forEach((k, v) -> {
                if (v instanceof String) {
                    meta.put(k, (String) v);
                } else {
                    meta.set(k, MAPPER.valueToTree(v));
                }
            });
        }
        return meta;
    }

    private com.fasterxml.jackson.databind.JsonNode toJsonNode(List<Map<String, String>> messages) {
        if (messages == null) return MAPPER.nullNode();
        return MAPPER.valueToTree(messages);
    }

    /** Shut down the background executor. Pending traces may be lost. */
    public void shutdown() {
        executor.shutdown();
    }
}
