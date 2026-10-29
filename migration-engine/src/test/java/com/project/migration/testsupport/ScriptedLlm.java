package com.project.migration.testsupport;

import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.LlmResponse;
import com.project.migration.domain.model.llm.ToolCall;
import com.project.migration.domain.model.telemetry.TokenUsage;
import com.project.migration.domain.port.LlmClientPort;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@link LlmClientPort} answering from a script: each call consumes the next reply, which sees the request.
 * A reply returns the structured record / text, a {@link ToolCalls}, or a {@link Reply}.
 */
public final class ScriptedLlm implements LlmClientPort {

    /** Tool calls as the whole answer. */
    public record ToolCalls(List<ToolCall> calls, long tokens) {
        public static ToolCalls of(ToolCall... calls) {
            return new ToolCalls(List.of(calls), 100);
        }

        public ToolCalls withTokens(long tokens) {
            return new ToolCalls(calls, tokens);
        }
    }

    /** Content with an explicit token cost and optional reasoning. */
    public record Reply(Object content, long tokens, String reasoning) {
        public static Reply of(Object content) {
            return new Reply(content, 100, null);
        }
    }

    private final List<Function<LlmRequest<?>, Object>> script = new ArrayList<>();
    private final List<LlmRequest<?>> requests = new ArrayList<>();

    public ScriptedLlm then(Function<LlmRequest<?>, Object> reply) {
        script.add(reply);
        return this;
    }

    public ScriptedLlm thenReturn(Object reply) {
        script.add(request -> reply);
        return this;
    }

    public List<LlmRequest<?>> requests() {
        return requests;
    }

    public int remaining() {
        return script.size() - requests.size();
    }

    public static ToolCall call(String id, String name, String argumentsJson) {
        return new ToolCall(id, name, argumentsJson);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> LlmResponse<T> chat(LlmRequest<T> request) {
        if (requests.size() >= script.size()) {
            throw new AssertionError("unscripted LLM call #" + (requests.size() + 1) + ": " + request.purpose());
        }
        Object reply = script.get(requests.size()).apply(request);
        requests.add(request);
        if (reply instanceof ToolCalls toolCalls) {
            return new LlmResponse<>(Optional.empty(), Optional.empty(), toolCalls.calls(), usage(toolCalls.tokens()),
                    "fake-model", Duration.ofMillis(5));
        }
        if (reply instanceof Reply withMeta) {
            return new LlmResponse<>(Optional.of((T) withMeta.content()), Optional.ofNullable(withMeta.reasoning()),
                    List.of(), usage(withMeta.tokens()), "fake-model", Duration.ofMillis(5));
        }
        return new LlmResponse<>(Optional.of((T) reply), Optional.empty(), List.of(), usage(100), "fake-model",
                Duration.ofMillis(5));
    }

    private static TokenUsage usage(long total) {
        return new TokenUsage(total / 2, total - total / 2, 0, 0);
    }
}
