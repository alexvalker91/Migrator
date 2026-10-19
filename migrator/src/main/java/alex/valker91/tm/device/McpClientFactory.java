package alex.valker91.tm.device;

import alex.valker91.tm.config.MigrationConfig;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

public class McpClientFactory {

    public static McpSyncClient createClient(String capabilitiesConfigPath) {
        ServerParameters params = ServerParameters.builder("npx")
                .args("-y", "appium-mcp@latest")
                .env(Map.of("CAPABILITIES_CONFIG", capabilitiesConfigPath))
                .build();

        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new JsonMapper());
        StdioClientTransport transport = new StdioClientTransport(params, jsonMapper, MigrationConfig.MCP_BUFFER_SIZE);

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(MigrationConfig.MCP_TIMEOUT)
                .build();

        client.initialize();
        return client;
    }
}
