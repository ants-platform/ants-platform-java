package com.ants.platform.guardrails;

import java.util.List;
import java.util.Map;

/**
 * Payload for trace logging via the ANTS ingestion API.
 * Use {@link #builder()} for convenient construction.
 */
public class TracePayload {

    private final String model;
    private final String provider;
    private final List<Map<String, String>> inputMessages;
    private final String outputText;
    private final Usage usage;
    private final Long latencyMs;
    private final GuardrailResults guardrailResults;
    private final String traceId;
    private final String sessionId;
    private final String userId;
    private final String agentId;
    private final String agentName;
    private final List<String> tags;
    private final Map<String, Object> extraMetadata;

    private TracePayload(Builder b) {
        this.model = b.model;
        this.provider = b.provider;
        this.inputMessages = b.inputMessages;
        this.outputText = b.outputText;
        this.usage = b.usage;
        this.latencyMs = b.latencyMs;
        this.guardrailResults = b.guardrailResults;
        this.traceId = b.traceId;
        this.sessionId = b.sessionId;
        this.userId = b.userId;
        this.agentId = b.agentId;
        this.agentName = b.agentName;
        this.tags = b.tags;
        this.extraMetadata = b.extraMetadata;
    }

    public static Builder builder() { return new Builder(); }

    public String getModel() { return model; }
    public String getProvider() { return provider; }
    public List<Map<String, String>> getInputMessages() { return inputMessages; }
    public String getOutputText() { return outputText; }
    public Usage getUsage() { return usage; }
    public Long getLatencyMs() { return latencyMs; }
    public GuardrailResults getGuardrailResults() { return guardrailResults; }
    public String getTraceId() { return traceId; }
    public String getSessionId() { return sessionId; }
    public String getUserId() { return userId; }
    public String getAgentId() { return agentId; }
    public String getAgentName() { return agentName; }
    public List<String> getTags() { return tags; }
    public Map<String, Object> getExtraMetadata() { return extraMetadata; }

    /** Token usage counts. */
    public static class Usage {
        private final Integer inputTokens;
        private final Integer outputTokens;
        private final Integer totalTokens;

        public Usage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.totalTokens = totalTokens;
        }

        public Integer getInputTokens() { return inputTokens; }
        public Integer getOutputTokens() { return outputTokens; }
        public Integer getTotalTokens() { return totalTokens; }
    }

    /** Guardrail check results for both input and output directions. */
    public static class GuardrailResults {
        private final GuardrailResult input;
        private final GuardrailResult output;

        public GuardrailResults(GuardrailResult input, GuardrailResult output) {
            this.input = input;
            this.output = output;
        }

        public GuardrailResult getInput() { return input; }
        public GuardrailResult getOutput() { return output; }
    }

    public static class Builder {
        private String model;
        private String provider;
        private List<Map<String, String>> inputMessages;
        private String outputText;
        private Usage usage;
        private Long latencyMs;
        private GuardrailResults guardrailResults;
        private String traceId;
        private String sessionId;
        private String userId;
        private String agentId;
        private String agentName;
        private List<String> tags;
        private Map<String, Object> extraMetadata;

        public Builder model(String model) { this.model = model; return this; }
        public Builder provider(String provider) { this.provider = provider; return this; }
        public Builder inputMessages(List<Map<String, String>> msgs) { this.inputMessages = msgs; return this; }
        public Builder outputText(String text) { this.outputText = text; return this; }
        public Builder usage(Usage usage) { this.usage = usage; return this; }
        public Builder usage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {
            this.usage = new Usage(inputTokens, outputTokens, totalTokens);
            return this;
        }
        public Builder latencyMs(long ms) { this.latencyMs = ms; return this; }
        public Builder guardrailResults(GuardrailResult input, GuardrailResult output) {
            this.guardrailResults = new GuardrailResults(input, output);
            return this;
        }
        public Builder traceId(String id) { this.traceId = id; return this; }
        public Builder sessionId(String id) { this.sessionId = id; return this; }
        public Builder userId(String id) { this.userId = id; return this; }
        public Builder agentId(String id) { this.agentId = id; return this; }
        public Builder agentName(String name) { this.agentName = name; return this; }
        public Builder tags(List<String> tags) { this.tags = tags; return this; }
        public Builder extraMetadata(Map<String, Object> meta) { this.extraMetadata = meta; return this; }

        public TracePayload build() {
            if (model == null) throw new IllegalStateException("model is required");
            if (provider == null) throw new IllegalStateException("provider is required");
            return new TracePayload(this);
        }
    }
}
