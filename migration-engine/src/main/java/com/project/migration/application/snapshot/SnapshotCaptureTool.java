package com.project.migration.application.snapshot;

import com.project.migration.application.agent.FlatJson;
import com.project.migration.application.agent.SyntheticTool;
import com.project.migration.application.agent.ToolOutcome;
import com.project.migration.application.pagesource.XmlPageSourceCleaner;
import com.project.migration.application.telemetry.TelemetryCollector;
import com.project.migration.domain.exception.McpException;
import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.llm.ToolSpec;
import com.project.migration.domain.model.media.ImageAttachment;
import com.project.migration.domain.model.screen.CleaningResult;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenState;
import com.project.migration.domain.model.screen.ScreenStateKey;
import com.project.migration.domain.model.screen.StateSnapshot;
import com.project.migration.domain.port.McpClientPort;
import com.project.migration.domain.port.MigrationLoggerPort;
import com.project.migration.domain.port.SnapshotRepositoryPort;

import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The engine-defined {@code capture_snapshot} tool of one agent pass. The model only names the screen state; the
 * engine takes the page source and screenshot from the session, compresses the XML and stores both. The
 * model never sees or repeats the XML. Only requested states can be captured, and a state that already has a
 * snapshot on this platform is not overwritten (code &amp; app freeze).
 */
final class SnapshotCaptureTool {

    static final String NAME = "capture_snapshot";

    private final Platform platform;
    private final McpClientPort mcp;
    private final XmlPageSourceCleaner cleaner;
    private final SnapshotRepositoryPort snapshots;
    private final TelemetryCollector telemetry;
    private final MigrationLoggerPort logger;
    private final Clock clock;
    private final Set<ScreenStateKey> requested;
    private final Set<ScreenStateKey> done = new LinkedHashSet<>();

    SnapshotCaptureTool(McpClientPort mcp, XmlPageSourceCleaner cleaner, SnapshotRepositoryPort snapshots,
                        TelemetryCollector telemetry, MigrationLoggerPort logger, Clock clock,
                        Set<ScreenStateKey> requested) {
        this.mcp = Checks.notNull(mcp, "mcp");
        this.platform = mcp.platform();
        this.cleaner = Checks.notNull(cleaner, "cleaner");
        this.snapshots = Checks.notNull(snapshots, "snapshots");
        this.telemetry = Checks.notNull(telemetry, "telemetry");
        this.logger = Checks.notNull(logger, "logger");
        this.clock = Checks.notNull(clock, "clock");
        this.requested = Set.copyOf(requested);
    }

    SyntheticTool tool() {
        String states = Arrays.stream(ScreenState.values()).map(state -> "\"" + state.name() + "\"")
                .collect(Collectors.joining(","));
        String schema = "{\"type\":\"object\",\"properties\":{\"screen\":{\"type\":\"string\"},"
                + "\"state\":{\"type\":\"string\",\"enum\":[" + states + "]},\"variant\":{\"type\":\"string\","
                + "\"description\":\"default unless the request names a variant\"}},\"required\":[\"screen\",\"state\"]}";
        return new SyntheticTool(new ToolSpec(NAME, "Store a snapshot (compressed screen XML and screenshot) of the "
                + "current screen as the given requested screen state.", schema), this::capture);
    }

    /** States stored or already present, in capture order. */
    Set<ScreenStateKey> captured() {
        return Set.copyOf(done);
    }

    private ToolOutcome capture(String argumentsJson) {
        Optional<ScreenStateKey> parsed = parse(argumentsJson);
        if (parsed.isEmpty()) {
            return ToolOutcome.error("Arguments must be a JSON object with screen, state (one of "
                    + Arrays.toString(ScreenState.values()) + ") and an optional variant. Requested: " + requested);
        }
        ScreenStateKey key = parsed.get();
        if (!requested.contains(key)) {
            return ToolOutcome.error(key + " was not requested. Requested states: " + requested);
        }
        if (done.contains(key) || snapshots.exists(key, platform)) {
            boolean first = done.add(key);
            return first ? ToolOutcome.progress("A " + platform + " snapshot of " + key + " is already stored; "
                    + "no need to capture it again.") : ToolOutcome.text(key + " is already captured.");
        }
        CleaningResult cleaned = cleaner.clean(mcp.getPageSource());
        telemetry.recordCleaning(cleaned);
        Optional<ImageAttachment> screenshot = screenshot();
        snapshots.save(new StateSnapshot(key, platform, cleaned.cleanedXml(), screenshot, clock.instant()));
        done.add(key);
        logger.info("Stored " + platform + " snapshot " + key + " (" + cleaned.rawTokensEstimate() + " -> "
                + cleaned.cleanedTokensEstimate() + " tokens)");
        return ToolOutcome.progress("Stored the " + platform + " snapshot of " + key + ".");
    }

    private Optional<ImageAttachment> screenshot() {
        try {
            return Optional.of(mcp.takeScreenshot());
        } catch (McpException e) {
            logger.warn("No screenshot for " + platform + " snapshot: " + e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<ScreenStateKey> parse(String argumentsJson) {
        try {
            Map<String, String> arguments = FlatJson.parseStringObject(argumentsJson);
            String variant = arguments.getOrDefault("variant", "");
            return Optional.of(new ScreenStateKey(new ScreenId(arguments.getOrDefault("screen", "")),
                    ScreenState.valueOf(arguments.getOrDefault("state", "").strip().toUpperCase()),
                    variant.isBlank() ? ScreenStateKey.DEFAULT_VARIANT : variant.strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
