package com.ants.platform.guardrails.providers;

import com.ants.platform.guardrails.AntsGuardrailsClient;
import com.ants.platform.guardrails.AntsTracer;
import com.ants.platform.guardrails.GuardrailResult;
import com.ants.platform.guardrails.GuardrailTraceUtils;
import com.ants.platform.guardrails.GuardrailViolationException;
import com.ants.platform.guardrails.TracePayload;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AWS Bedrock Converse wrapper with guardrail enforcement and trace logging.
 *
 * <pre>{@code
 * var client = AntsBedrock.builder()
 *     .antsApiKey("pk:sk")
 *     .agentId("agent_123")
 *     .region(Region.US_EAST_1)
 *     .build();
 *
 * ConverseResponse response = client.converse(ConverseRequest.builder()
 *     .modelId("anthropic.claude-3-haiku-20240307-v1:0")
 *     .messages(Message.builder()
 *         .role(ConversationRole.USER)
 *         .content(ContentBlock.fromText("Hello"))
 *         .build())
 *     .build());
 * }</pre>
 */
public class AntsBedrock {

    private final BedrockRuntimeClient client;
    private final AntsGuardrailsClient guardrails;
    private final AntsTracer tracer;
    private final String agentId;
    private final String agentName;

    private AntsBedrock(Builder builder) {
        this.agentId = builder.agentId;
        this.agentName = builder.agentName;
        this.client = BedrockRuntimeClient.builder()
                .region(builder.region)
                .build();
        this.guardrails = AntsGuardrailsClient.builder()
                .antsApiKey(builder.antsApiKey)
                .baseUrl(builder.antsBaseUrl)
                .agentId(builder.agentId)
                .guardrailServiceUrl(builder.guardrailServiceUrl)
                .build();
        this.tracer = new AntsTracer(builder.antsApiKey, builder.antsBaseUrl);
    }

    public static Builder builder() { return new Builder(); }

    public ConverseResponse converse(ConverseRequest request) {
        String inputText = extractInputText(request);
        boolean guardrailActive = guardrails.isEnabled();
        GuardrailResult inputCheck = null;
        GuardrailResult outputCheck = null;

        // Input guardrail check (only if agent has a policy configured)
        ConverseRequest effectiveRequest = request;
        if (guardrailActive) {
            inputCheck = guardrails.checkInput(inputText);
            if (inputCheck.getResult() == GuardrailResult.Result.BLOCKED) {
                throw new GuardrailViolationException("input", inputCheck);
            }

            if (inputCheck.getResult() == GuardrailResult.Result.SANITIZED
                    && inputCheck.getSanitizedText() != null) {
                effectiveRequest = request.toBuilder()
                        .messages(Message.builder()
                                .role(ConversationRole.USER)
                                .content(ContentBlock.fromText(inputCheck.getSanitizedText()))
                                .build())
                        .build();
            }
        }
        String effectiveInputText = GuardrailTraceUtils.effectiveText(inputText, inputCheck);

        // LLM call with latency tracking
        long startMs = System.currentTimeMillis();
        ConverseResponse response = client.converse(effectiveRequest);
        long latencyMs = System.currentTimeMillis() - startMs;

        String outputText = extractOutputText(response);

        // Output guardrail check (only if agent has a policy configured)
        if (guardrailActive && outputText != null && !outputText.isEmpty()) {
            outputCheck = guardrails.checkOutput(outputText, effectiveInputText);
            if (outputCheck.getResult() == GuardrailResult.Result.BLOCKED) {
                throw new GuardrailViolationException("output", outputCheck);
            }
            outputText = GuardrailTraceUtils.effectiveText(outputText, outputCheck);
            response = applySanitizedOutput(response, outputText);
        }

        // Extract usage from Bedrock response
        TracePayload.Usage usage = extractUsage(response);
        String guardrailResult = GuardrailTraceUtils.overallGuardrailResult(
                guardrailActive, inputCheck, outputCheck);

        // Fire-and-forget trace logging
        logTrace(
                effectiveRequest.modelId(),
                toTraceMessages(effectiveRequest),
                outputText,
                usage,
                latencyMs,
                inputCheck,
                outputCheck,
                guardrailResult
        );

        return response;
    }

    private String extractInputText(ConverseRequest request) {
        if (!request.hasMessages()) return "";
        return request.messages().stream()
                .flatMap(m -> m.content().stream())
                .filter(block -> block.text() != null)
                .map(ContentBlock::text)
                .collect(Collectors.joining("\n"));
    }

    private String extractOutputText(ConverseResponse response) {
        if (response.output() == null || response.output().message() == null) return "";
        return response.output().message().content().stream()
                .filter(block -> block.text() != null)
                .map(ContentBlock::text)
                .collect(Collectors.joining());
    }

    private TracePayload.Usage extractUsage(ConverseResponse response) {
        if (response.usage() == null) return null;
        return new TracePayload.Usage(
                response.usage().inputTokens(),
                response.usage().outputTokens(),
                response.usage().totalTokens()
        );
    }

    private void logTrace(String modelId, List<Map<String, String>> inputMessages, String outputText,
                           TracePayload.Usage usage, long latencyMs,
                           GuardrailResult inputCheck, GuardrailResult outputCheck,
                           String guardrailResult) {
        TracePayload.Builder payload = TracePayload.builder()
                .model(modelId != null ? modelId : "bedrock-unknown")
                .provider("bedrock")
                .inputMessages(inputMessages)
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

    private List<Map<String, String>> toTraceMessages(ConverseRequest request) {
        if (!request.hasMessages()) {
            return List.of();
        }

        return request.messages().stream()
                .map(message -> Map.of(
                        "role", message.roleAsString().toLowerCase(),
                        "content", message.content().stream()
                                .filter(block -> block.text() != null)
                                .map(ContentBlock::text)
                                .collect(Collectors.joining("\n"))
                ))
                .collect(Collectors.toList());
    }

    private ConverseResponse applySanitizedOutput(ConverseResponse response, String outputText) {
        if (response.output() == null || response.output().message() == null) {
            return response;
        }

        Message sanitizedMessage = response.output().message().toBuilder()
                .content(ContentBlock.fromText(outputText))
                .build();
        ConverseOutput sanitizedOutput = response.output().toBuilder()
                .message(sanitizedMessage)
                .build();
        return response.toBuilder().output(sanitizedOutput).build();
    }

    public static class Builder {
        private String antsApiKey;
        private String antsBaseUrl;
        private String agentId;
        private String agentName;
        private String guardrailServiceUrl;
        private Region region = Region.US_EAST_1;

        public Builder antsApiKey(String key) { this.antsApiKey = key; return this; }
        public Builder antsBaseUrl(String url) { this.antsBaseUrl = url; return this; }
        public Builder agentId(String id) { this.agentId = id; return this; }
        public Builder agentName(String name) { this.agentName = name; return this; }
        public Builder guardrailServiceUrl(String url) { this.guardrailServiceUrl = url; return this; }
        public Builder region(Region region) { this.region = region; return this; }

        public AntsBedrock build() {
            if (antsApiKey == null) throw new IllegalStateException("antsApiKey is required");
            return new AntsBedrock(this);
        }
    }
}
