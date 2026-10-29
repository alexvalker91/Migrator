package com.project.migration.infrastructure.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.code.FrameworkContract;
import com.project.migration.domain.model.config.AgentLoopConfig;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.config.GateConfig;
import com.project.migration.domain.model.config.MavenConfig;
import com.project.migration.domain.model.config.McpServerConfig;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.llm.TierModelConfig;
import com.project.migration.domain.model.mcp.McpToolCall;
import com.project.migration.domain.model.screen.ComponentId;
import com.project.migration.domain.model.telemetry.ModelPricing;
import com.project.migration.infrastructure.json.JsonMappers;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds {@link EngineConfig} from {@code engine-defaults.json} (classpath) deep-merged with an optional override
 * file: objects merge key by key, arrays and scalars are replaced. Tier {@code pricing} applies to every
 * deployment of the tier (base, reasoning, alternatives); {@code model_pricing} overrides single deployments.
 * Secrets are never part of the configuration.
 */
public final class JsonEngineConfigLoader {

    static final String DEFAULTS_RESOURCE = "/engine-defaults.json";
    static final int SCHEMA_VERSION = 1;

    record ConfigJson(int schemaVersion, int stabilityRuns, int maxHealingAttempts, Map<LlmTier, TierJson> tiers,
                      Map<String, PricingJson> modelPricing, Map<Platform, McpServerJson> mcpServers,
                      AgentLoopJson agentLoop, MavenJson maven, GateJson gate, Map<String, String> globalComponents) {
    }

    record MavenJson(String executable, List<String> extraArgs, String testModule, long compileTimeoutSeconds,
                     long testTimeoutSeconds, long classTimeoutSeconds) {
    }

    record GateJson(long maxTestMinutes, long maxTestTokens, int environmentRetries,
                    int maxConsecutiveEnvironmentFailures) {
    }

    record McpServerJson(String command, List<String> args, Map<String, String> env,
                         List<StartupCallJson> startupCalls, String appId, long startupTimeoutSeconds) {
    }

    record StartupCallJson(String tool, JsonNode arguments) {
    }

    record AgentLoopJson(int maxSteps, int maxStepsWithoutProgress, long maxTokens, long toolCallTimeoutSeconds,
                         int maxToolResultChars, List<String> allowedTools) {
    }

    record TierJson(String modelId, List<String> fallbackModelIds, String reasoningModelId,
                    List<String> reasoningFallbackModelIds, boolean supportsVision, PricingJson pricing) {
    }

    /** Keys spelled out: {@code SNAKE_CASE} does not split digits ({@code input_per1m}). */
    record PricingJson(@JsonProperty("input_per_1m") BigDecimal inputPer1m,
                       @JsonProperty("output_per_1m") BigDecimal outputPer1m,
                       @JsonProperty("reasoning_per_1m") BigDecimal reasoningPer1m,
                       @JsonProperty("cached_input_per_1m") BigDecimal cachedInputPer1m) {
    }

    private final ObjectMapper mapper = JsonMappers.persistence();

    /**
     * @param overrideFile project-specific settings merged over the defaults (e.g. {@code --config})
     * @throws IllegalArgumentException if the merged configuration is invalid
     */
    public EngineConfig load(Path projectRoot, boolean showReasoning, Optional<Path> overrideFile) {
        JsonNode merged = defaults();
        String source = "engine-defaults.json";
        if (overrideFile.isPresent()) {
            merge((ObjectNode) merged, readFile(overrideFile.get()));
            source += " + " + overrideFile.get();
        }
        ConfigJson config;
        try {
            config = mapper.treeToValue(merged, ConfigJson.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Invalid engine config (" + source + "): " + e.getOriginalMessage(), e);
        }
        try {
            return toEngineConfig(config, projectRoot, showReasoning);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Invalid engine config (" + source + "): " + e.getMessage(), e);
        }
    }

    private static EngineConfig toEngineConfig(ConfigJson config, Path projectRoot, boolean showReasoning) {
        if (config.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("schema_version " + config.schemaVersion() + " is not supported");
        }
        List<TierModelConfig> tiers = new ArrayList<>();
        Map<String, ModelPricing> pricing = new LinkedHashMap<>();
        for (Map.Entry<LlmTier, TierJson> entry : config.tiers().entrySet()) {
            TierJson tier = entry.getValue();
            TierModelConfig model = new TierModelConfig(entry.getKey(), tier.modelId(),
                    Optional.ofNullable(tier.reasoningModelId()), tier.supportsVision(),
                    orEmpty(tier.fallbackModelIds()), orEmpty(tier.reasoningFallbackModelIds()));
            tiers.add(model);
            if (tier.pricing() != null) {
                model.allModelIds().forEach(id -> pricing.putIfAbsent(id, toPricing(id, tier.pricing())));
            }
        }
        if (config.modelPricing() != null) {
            config.modelPricing().forEach((id, price) -> pricing.put(id, toPricing(id, price)));
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        return new EngineConfig(root, root.resolve(".migration"), root.resolve("migration_reports"), showReasoning,
                config.stabilityRuns(), config.maxHealingAttempts(), tiers, List.copyOf(pricing.values()),
                List.of(), FrameworkContract.DEFAULT, toMcpServers(config.mcpServers()), toAgentLoop(config.agentLoop()),
                toMaven(config.maven()), toGate(config.gate()), toComponents(config.globalComponents()));
    }

    private static Map<Platform, McpServerConfig> toMcpServers(Map<Platform, McpServerJson> servers) {
        Map<Platform, McpServerConfig> result = new EnumMap<>(Platform.class);
        if (servers != null) {
            servers.forEach((platform, server) -> result.put(platform, new McpServerConfig(server.command(),
                    orEmpty(server.args()), server.env() == null ? Map.of() : server.env(),
                    orEmptyCalls(server.startupCalls()), Optional.ofNullable(server.appId()),
                    Duration.ofSeconds(server.startupTimeoutSeconds()))));
        }
        return result;
    }

    private static List<McpToolCall> orEmptyCalls(List<StartupCallJson> calls) {
        if (calls == null) {
            return List.of();
        }
        return calls.stream().map(call -> new McpToolCall(call.tool(),
                call.arguments() == null ? "{}" : call.arguments().toString())).toList();
    }

    private static MavenConfig toMaven(MavenJson maven) {
        if (maven == null) {
            return MavenConfig.DEFAULT;
        }
        return new MavenConfig(Optional.ofNullable(maven.executable()), orEmpty(maven.extraArgs()),
                Optional.ofNullable(maven.testModule()), Duration.ofSeconds(maven.compileTimeoutSeconds()),
                Duration.ofSeconds(maven.testTimeoutSeconds()), Duration.ofSeconds(maven.classTimeoutSeconds()));
    }

    private static GateConfig toGate(GateJson gate) {
        return gate == null ? GateConfig.DEFAULT : new GateConfig(Duration.ofMinutes(gate.maxTestMinutes()),
                gate.maxTestTokens(), gate.environmentRetries(), gate.maxConsecutiveEnvironmentFailures());
    }

    private static Map<ComponentId, String> toComponents(Map<String, String> components) {
        Map<ComponentId, String> result = new LinkedHashMap<>();
        if (components != null) {
            components.forEach((id, fqcn) -> result.put(new ComponentId(id), fqcn));
        }
        return result;
    }

    private static AgentLoopConfig toAgentLoop(AgentLoopJson loop) {
        if (loop == null) {
            return AgentLoopConfig.DEFAULT;
        }
        return new AgentLoopConfig(loop.maxSteps(), loop.maxStepsWithoutProgress(), loop.maxTokens(),
                Duration.ofSeconds(loop.toolCallTimeoutSeconds()), loop.maxToolResultChars(),
                Set.copyOf(orEmpty(loop.allowedTools())));
    }

    private static ModelPricing toPricing(String modelId, PricingJson json) {
        return new ModelPricing(modelId, json.inputPer1m(), json.outputPer1m(), json.reasoningPer1m(),
                json.cachedInputPer1m());
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    private JsonNode defaults() {
        try (InputStream in = JsonEngineConfigLoader.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + DEFAULTS_RESOURCE);
            }
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + DEFAULTS_RESOURCE, e);
        }
    }

    private JsonNode readFile(Path file) {
        try {
            JsonNode node = mapper.readTree(Files.readString(file));
            if (!(node instanceof ObjectNode)) {
                throw new IllegalArgumentException("Engine config " + file + " must be a JSON object");
            }
            return node;
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Engine config " + file + " is not valid JSON: " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read engine config " + file, e);
        }
    }

    /** Objects merge recursively; any other value in {@code override} replaces the one in {@code target}. */
    static void merge(ObjectNode target, JsonNode override) {
        Iterator<Map.Entry<String, JsonNode>> fields = override.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode existing = target.get(field.getKey());
            if (existing instanceof ObjectNode existingObject && field.getValue() instanceof ObjectNode) {
                merge(existingObject, field.getValue());
            } else {
                target.set(field.getKey(), field.getValue().deepCopy());
            }
        }
    }
}
