package com.project.migration.infrastructure.mcp;

import com.project.migration.domain.exception.McpException;
import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.config.McpServerConfig;
import com.project.migration.domain.port.McpClientPort;
import com.project.migration.domain.port.McpClientsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Android and iOS Appium MCP sessions used side by side. A platform's server is started on first use and kept
 * until {@link #close()}.
 */
public final class DualMcpClientAdapter implements McpClientsPort {

    private static final Logger LOG = LoggerFactory.getLogger(DualMcpClientAdapter.class);

    private final Function<Platform, McpClientPort> opener;
    private final Map<Platform, McpClientPort> sessions = new EnumMap<>(Platform.class);

    public DualMcpClientAdapter(EngineConfig config) {
        this(config, System::getenv);
    }

    /** @param environment lookup for {@code ${NAME}} references in the server environments */
    public DualMcpClientAdapter(EngineConfig config, UnaryOperator<String> environment) {
        this(platform -> {
            McpServerConfig server = config.mcpServers().get(platform);
            if (server == null) {
                throw new McpException(platform, "no MCP server configured (mcp_servers." + platform.name() + ")");
            }
            return StdioMcpClient.open(platform, server, config.agentLoop().toolCallTimeout(), environment);
        });
    }

    /** Sessions come from {@code opener}; used to plug in other transports. */
    public DualMcpClientAdapter(Function<Platform, McpClientPort> opener) {
        this.opener = Checks.notNull(opener, "opener");
    }

    @Override
    public synchronized McpClientPort forPlatform(Platform platform) {
        Checks.notNull(platform, "platform");
        McpClientPort session = sessions.get(platform);
        if (session == null) {
            LOG.info("Opening {} MCP session", platform);
            session = opener.apply(platform);
            sessions.put(platform, session);
        }
        return session;
    }

    @Override
    public synchronized void close() {
        sessions.values().forEach(session -> {
            try {
                session.close();
            } catch (RuntimeException e) {
                LOG.warn("Closing {} MCP session failed: {}", session.platform(), e.getMessage());
            }
        });
        sessions.clear();
    }
}
