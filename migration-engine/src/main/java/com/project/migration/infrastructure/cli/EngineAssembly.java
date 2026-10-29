package com.project.migration.infrastructure.cli;

import com.project.migration.application.codegen.CodeAdaptationService;
import com.project.migration.application.delta.DeltaAnalyzerService;
import com.project.migration.application.healing.CompilationRepairService;
import com.project.migration.application.healing.SelfHealingService;
import com.project.migration.application.orchestrator.AndroidRegressionGuard;
import com.project.migration.application.orchestrator.ClassMigrator;
import com.project.migration.application.orchestrator.GlobalComponentInitializer;
import com.project.migration.application.orchestrator.ProjectProgressCalculator;
import com.project.migration.application.orchestrator.TestClassMigrationOrchestrator;
import com.project.migration.application.pagesource.XmlPageSourceCleaner;
import com.project.migration.application.report.DeterministicNarrator;
import com.project.migration.application.report.Tier3ReportNarrator;
import com.project.migration.application.snapshot.SnapshotCollectionService;
import com.project.migration.application.stability.StabilityGateService;
import com.project.migration.application.telemetry.TelemetryCollector;
import com.project.migration.application.telemetry.TelemetryRecordingLlmClient;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.port.CodeAnalyzerPort;
import com.project.migration.domain.port.LlmClientPort;
import com.project.migration.infrastructure.ast.JavaParserAstAdapter;
import com.project.migration.infrastructure.cache.JsonCacheRepository;
import com.project.migration.infrastructure.fs.FileArtifactStore;
import com.project.migration.infrastructure.git.GitServiceAdapter;
import com.project.migration.infrastructure.history.JsonlRunHistory;
import com.project.migration.infrastructure.llm.DialChatModelFactory;
import com.project.migration.infrastructure.llm.DialSettings;
import com.project.migration.infrastructure.llm.DialTieredLlmRouter;
import com.project.migration.infrastructure.logging.LogbackMigrationLogger;
import com.project.migration.infrastructure.maven.MavenTestNgRunnerAdapter;
import com.project.migration.infrastructure.mcp.DualMcpClientAdapter;
import com.project.migration.infrastructure.report.HtmlInfographicReportGenerator;
import com.project.migration.infrastructure.report.JsonWalkthroughSink;
import com.project.migration.infrastructure.snapshot.FileSnapshotRepository;
import com.project.migration.infrastructure.telemetry.JsonlTelemetrySink;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;

/**
 * The composition root: the one place that knows every adapter and wires them into the services. Everything else
 * depends on ports and interfaces only.
 */
final class EngineAssembly {

    private EngineAssembly() {
    }

    /**
     * @throws IllegalStateException if {@code DIAL_API_KEY} is not set (checked by the caller beforehand with a friendlier message)
     */
    static Engine assemble(EngineConfig config, DialSettings dial) {
        Clock clock = Clock.systemUTC();
        LogbackMigrationLogger logger = new LogbackMigrationLogger(config.reportsDir(), config.showReasoning());
        TelemetryCollector telemetry = new TelemetryCollector(config, clock, new JsonlTelemetrySink(config.reportsDir()), logger);
        LlmClientPort llm = new TelemetryRecordingLlmClient(
                new DialTieredLlmRouter(config, new DialChatModelFactory(dial), logger, clock), telemetry, logger);

        var cache = new JsonCacheRepository(config.migrationDir(), clock);
        var snapshots = new FileSnapshotRepository(config.migrationDir());
        JavaParserAstAdapter analyzer = new JavaParserAstAdapter(config);
        var modifier = analyzer.modifier();
        var mcps = new DualMcpClientAdapter(config);
        var runner = new MavenTestNgRunnerAdapter(config);
        var git = new GitServiceAdapter(config.projectRoot());
        var artifacts = new FileArtifactStore();
        var history = new JsonlRunHistory(config.migrationDir());
        var cleaner = new XmlPageSourceCleaner();

        Set<String> componentClasses = new HashSet<>(config.globalComponents().values());
        var delta = new DeltaAnalyzerService(analyzer, cache, snapshots, llm, telemetry, logger, componentClasses);
        var collector = new SnapshotCollectionService(mcps, snapshots, llm, analyzer, new JsonWalkthroughSink(config.reportsDir()),
                cleaner, telemetry, logger, config.agentLoop(), clock);
        var components = new GlobalComponentInitializer(analyzer, modifier, cache, snapshots, llm, logger, config, clock);
        var adapter = new CodeAdaptationService(analyzer, modifier, cache, snapshots, llm, logger, clock);
        var repairer = new CompilationRepairService(analyzer, modifier, llm, artifacts, logger, config);
        var healing = new SelfHealingService(analyzer, modifier, runner, git, cache, snapshots, mcps, llm, artifacts, cleaner,
                telemetry, logger, config, clock);
        var gate = new StabilityGateService(runner, healing, git, modifier, analyzer, cache, artifacts, cleaner, telemetry,
                logger, config, clock);
        var guard = new AndroidRegressionGuard(runner, logger);
        var generator = new HtmlInfographicReportGenerator(config.reportsDir(), history,
                new Tier3ReportNarrator(llm, new DeterministicNarrator(), logger));
        var orchestrator = new TestClassMigrationOrchestrator(delta, collector, components, adapter, repairer, gate, guard,
                runner, git, generator, analyzer, cache, telemetry, logger, new ProjectProgressCalculator(analyzer, cache),
                config, clock, history);

        return new Engine() {
            @Override
            public ClassMigrator orchestrator() {
                return orchestrator;
            }

            @Override
            public CodeAnalyzerPort analyzer() {
                return analyzer;
            }

            @Override
            public void close() {
                mcps.close();
                logger.close();
            }
        };
    }
}
