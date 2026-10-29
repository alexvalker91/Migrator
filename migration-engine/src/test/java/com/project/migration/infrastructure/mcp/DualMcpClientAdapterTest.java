package com.project.migration.infrastructure.mcp;

import com.project.migration.domain.exception.McpException;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.config.AgentLoopConfig;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.config.McpServerConfig;
import com.project.migration.domain.model.llm.ToolSpec;
import com.project.migration.domain.model.mcp.McpToolCall;
import com.project.migration.domain.model.mcp.McpToolResult;
import com.project.migration.domain.port.McpClientPort;
import com.project.migration.infrastructure.config.JsonEngineConfigLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DualMcpClientAdapterTest {

    @TempDir
    Path dir;

    private final List<DualMcpClientAdapter> adapters = new ArrayList<>();

    @AfterEach
    void closeAll() {
        adapters.forEach(DualMcpClientAdapter::close);
    }

    @Test
    void startsTheServerRunsStartupCallsAndListsTools() throws IOException {
        Path log = dir.resolve("android.log");
        McpClientPort android = adapter(Map.of(Platform.ANDROID, server(Map.of("FAKE_LOG", log.toString()), Optional.empty())))
                .forPlatform(Platform.ANDROID);

        assertThat(android.platform()).isEqualTo(Platform.ANDROID);
        assertThat(android.listTools()).extracting(ToolSpec::name).contains("appium_gesture", "appium_get_page_source");
        assertThat(android.listTools().getFirst().parametersJsonSchema()).contains("\"type\"");
        assertThat(Files.readAllLines(log)).hasSize(2);
        assertThat(Files.readAllLines(log).getFirst()).startsWith("select_device").contains("android");
    }

    @Test
    void returnsToolAnswersAndToolLevelErrorsAsValues() {
        McpClientPort ios = adapter(Map.of(Platform.IOS, server(Map.of("FAKE_FAIL_TOOL", "appium_gesture"), Optional.empty())))
                .forPlatform(Platform.IOS);

        McpToolResult ok = ios.invokeTool("appium_find_element", "{\"strategy\": \"id\", \"selector\": \"x\"}");
        McpToolResult failed = ios.invokeTool("appium_gesture", "{}");
        McpToolResult badArguments = ios.invokeTool("appium_find_element", "not json");

        assertThat(ok.isError()).isFalse();
        assertThat(ok.text()).contains("ok appium_find_element").contains("selector");
        assertThat(failed.isError()).isTrue();
        assertThat(failed.text()).isEqualTo("boom");
        assertThat(badArguments.isError()).isTrue();
    }

    @Test
    void extractsPageSourceXmlFromSurroundingTextAndDecodesScreenshots() {
        McpClientPort android = adapter(Map.of(Platform.ANDROID, server(Map.of(), Optional.empty())))
                .forPlatform(Platform.ANDROID);

        assertThat(android.getPageSource().xml()).isEqualTo("<hierarchy><node text=\"Hello\"/></hierarchy>");
        assertThat(android.getPageSource().platform()).isEqualTo(Platform.ANDROID);
        assertThat(android.takeScreenshot().data()).isEqualTo(FakeAppiumMcpServer.PNG);
    }

    @Test
    void restartsTheAppWithTerminateThenActivate() throws IOException {
        Path log = dir.resolve("ios.log");
        McpClientPort ios = adapter(Map.of(Platform.IOS,
                server(Map.of("FAKE_LOG", log.toString()), Optional.of("com.bank.app")))).forPlatform(Platform.IOS);

        ios.restartApp();

        List<String> calls = Files.readAllLines(log);
        assertThat(calls.subList(calls.size() - 2, calls.size())).satisfiesExactly(
                terminate -> assertThat(terminate).startsWith("appium_app_lifecycle").contains("terminate", "com.bank.app"),
                activate -> assertThat(activate).startsWith("appium_app_lifecycle").contains("activate", "com.bank.app"));
    }

    @Test
    void restartWithoutAppIdIsAnMcpException() {
        McpClientPort android = adapter(Map.of(Platform.ANDROID, server(Map.of(), Optional.empty())))
                .forPlatform(Platform.ANDROID);

        assertThatThrownBy(android::restartApp).isInstanceOf(McpException.class).hasMessageContaining("app_id");
    }

    @Test
    void aHungToolCallBecomesAToolErrorWithinTheCallTimeout() {
        DualMcpClientAdapter adapter = adapter(Map.of(Platform.ANDROID, server(Map.of(), Optional.empty())),
                Duration.ofMillis(400));
        McpClientPort android = adapter.forPlatform(Platform.ANDROID);

        McpToolResult result = android.invokeTool("slow_tool", "{}");

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("slow_tool").contains("did not answer");
    }

    @Test
    void aFailingStartupCallIsReportedAndLeavesNoSession() {
        DualMcpClientAdapter adapter = adapter(Map.of(Platform.ANDROID,
                server(Map.of("FAKE_FAIL_TOOL", "appium_session_management"), Optional.empty())));

        assertThatThrownBy(() -> adapter.forPlatform(Platform.ANDROID)).isInstanceOf(McpException.class)
                .hasMessageContaining("startup call appium_session_management failed").hasMessageContaining("boom");
    }

    @Test
    void anUnstartableServerAndAMissingPlatformAreMcpExceptions() {
        McpServerConfig missingBinary = new McpServerConfig(dir.resolve("no-such-binary").toString(), List.of(),
                Map.of(), List.of(), Optional.empty(), Duration.ofSeconds(5));
        DualMcpClientAdapter adapter = adapter(Map.of(Platform.ANDROID, missingBinary));

        assertThatThrownBy(() -> adapter.forPlatform(Platform.ANDROID)).isInstanceOf(McpException.class)
                .hasMessageContaining("cannot start MCP server");
        assertThatThrownBy(() -> adapter.forPlatform(Platform.IOS)).isInstanceOf(McpException.class)
                .hasMessageContaining("no MCP server configured");
    }

    @Test
    void unresolvedEnvironmentReferencesNameTheMissingVariable() {
        McpServerConfig withReference = new McpServerConfig("java", List.of(),
                Map.of("CAPABILITIES_CONFIG", "${MIGRATION_TEST_UNSET}"), List.of(), Optional.empty(), Duration.ofSeconds(5));
        DualMcpClientAdapter adapter = adapter(Map.of(Platform.IOS, withReference));

        assertThatThrownBy(() -> adapter.forPlatform(Platform.IOS)).isInstanceOf(McpException.class)
                .hasMessageContaining("MIGRATION_TEST_UNSET").hasMessageContaining("mcp_servers.IOS.env.CAPABILITIES_CONFIG");
    }

    @Test
    void keepsOneSessionPerPlatformUntilClosed() {
        DualMcpClientAdapter adapter = adapter(Map.of(Platform.ANDROID, server(Map.of(), Optional.empty())));

        McpClientPort first = adapter.forPlatform(Platform.ANDROID);
        assertThat(adapter.forPlatform(Platform.ANDROID)).isSameAs(first);

        adapter.close();
        adapter.close();
        assertThatThrownBy(() -> first.invokeTool("appium_find_element", "{}")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void environmentReferencesAreExpandedFromTheEngineEnvironment() {
        Map<String, String> resolved = McpEnvironment.resolve(Platform.IOS,
                Map.of("A", "${HOME_X}/caps.json", "B", "plain"), name -> name.equals("HOME_X") ? "/tmp" : null);

        assertThat(resolved).containsEntry("A", "/tmp/caps.json").containsEntry("B", "plain");
    }

    private DualMcpClientAdapter adapter(Map<Platform, McpServerConfig> servers) {
        return adapter(servers, Duration.ofSeconds(20));
    }

    private DualMcpClientAdapter adapter(Map<Platform, McpServerConfig> servers, Duration callTimeout) {
        EngineConfig base = new JsonEngineConfigLoader().load(dir, false, Optional.empty());
        AgentLoopConfig loop = new AgentLoopConfig(30, 8, 150_000, callTimeout, 12_000, AgentLoopConfig.DEFAULT_ALLOWED_TOOLS);
        Map<Platform, McpServerConfig> byPlatform = new EnumMap<>(Platform.class);
        byPlatform.putAll(servers);
        DualMcpClientAdapter adapter = new DualMcpClientAdapter(
                base.withMcpServers(byPlatform).withAgentLoop(loop), name -> null);
        adapters.add(adapter);
        return adapter;
    }

    private static McpServerConfig server(Map<String, String> env, Optional<String> appId) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new McpServerConfig(java,
                List.of("-cp", System.getProperty("java.class.path"), FakeAppiumMcpServer.class.getName()), env,
                List.of(new McpToolCall("select_device", "{\"platform\": \"android\"}"),
                        new McpToolCall("appium_session_management", "{\"action\": \"create\"}")),
                appId, Duration.ofSeconds(30));
    }
}
