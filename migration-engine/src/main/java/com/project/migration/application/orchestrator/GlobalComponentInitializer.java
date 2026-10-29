package com.project.migration.application.orchestrator;

import com.project.migration.application.codegen.LocatorMapper;
import com.project.migration.application.codegen.MappingOutcome;
import com.project.migration.application.codegen.ResilientApplier;
import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.analysis.MigrationTaskDelta;
import com.project.migration.domain.model.cache.GlobalComponentEntry;
import com.project.migration.domain.model.cache.ScreenElementMapping;
import com.project.migration.domain.model.cache.VerificationStatus;
import com.project.migration.domain.model.code.AddIosLocator;
import com.project.migration.domain.model.code.LocatorField;
import com.project.migration.domain.model.codegen.MappedLocator;
import com.project.migration.domain.model.codegen.UnresolvedLocator;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.screen.ComponentId;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.port.CacheRepositoryPort;
import com.project.migration.domain.port.CodeAnalyzerPort;
import com.project.migration.domain.port.CodeModifierPort;
import com.project.migration.domain.port.LlmClientPort;
import com.project.migration.domain.port.MigrationLoggerPort;
import com.project.migration.domain.port.SnapshotRepositoryPort;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Stage 0: maps the Page Objects of cross-screen components ({@code TopAppBar}, {@code BottomNavBar}, ...), once.
 * There is no separate pass through the app: a component is visible on every screen, so its fields are looked
 * for in the snapshot pairs Stage 2 has just stored for the class under migration. A component that cannot be
 * judged yet (no pairs) is retried with the next class. The entry is {@code UNVERIFIED} until the first test that
 * uses the component passes the 3/3 gate.
 */
public final class GlobalComponentInitializer implements ComponentInitializer {

    private final CodeAnalyzerPort analyzer;
    private final CacheRepositoryPort cache;
    private final MigrationLoggerPort logger;
    private final EngineConfig config;
    private final Clock clock;
    private final LocatorMapper mapper;
    private final ResilientApplier applier;

    public GlobalComponentInitializer(CodeAnalyzerPort analyzer, CodeModifierPort modifier, CacheRepositoryPort cache,
                                      SnapshotRepositoryPort snapshots, LlmClientPort llm, MigrationLoggerPort logger,
                                      EngineConfig config, Clock clock) {
        this.analyzer = Checks.notNull(analyzer, "analyzer");
        this.cache = Checks.notNull(cache, "cache");
        this.logger = Checks.notNull(logger, "logger");
        this.config = Checks.notNull(config, "config");
        this.clock = Checks.notNull(clock, "clock");
        this.mapper = new LocatorMapper(snapshots, llm, logger);
        this.applier = new ResilientApplier(modifier, logger);
    }

    @Override
    public ComponentInitResult initialize(MigrationTaskDelta delta) {
        List<ComponentId> initialized = new ArrayList<>();
        List<UnresolvedLocator> unresolved = new ArrayList<>();
        List<ScreenId> screens = delta.screenOfClass().values().stream().distinct().toList();
        for (Map.Entry<ComponentId, String> component : config.globalComponents().entrySet()) {
            if (cache.findGlobalComponent(component.getKey()).isPresent()) {
                continue;
            }
            List<LocatorField> fields = analyzer.listLocatorFields(component.getValue()).stream()
                    .filter(field -> !field.hasIosLocator()).toList();
            if (fields.isEmpty()) {
                save(component.getKey(), List.of());
                initialized.add(component.getKey());
                continue;
            }
            MappingOutcome outcome = mapper.mapAcross(screens, fields);
            boolean noSnapshots = outcome.mapped().isEmpty() && outcome.unresolved().stream()
                    .allMatch(u -> u.reason().startsWith("no Android/iOS snapshot pair"));
            if (noSnapshots) {
                logger.info("Global component " + component.getKey() + " waits for snapshot pairs; tried again with the next class");
                continue;
            }
            ResilientApplier.Applied applied = applier.apply(new ArrayList<>(outcome.changes()));
            List<ScreenElementMapping> mappings = new ArrayList<>();
            for (MappedLocator locator : outcome.mapped()) {
                AddIosLocator change = new AddIosLocator(locator.field(), locator.strategy(), locator.value());
                if (applied.failures().containsKey(change)) {
                    unresolved.add(new UnresolvedLocator(locator.field(), "the annotation could not be applied: "
                            + applied.failures().get(change)));
                    continue;
                }
                String android = fields.stream().filter(f -> f.field().equals(locator.field())).findFirst()
                        .map(LocatorField::androidLocator).orElse("unknown");
                mappings.add(new ScreenElementMapping(locator.field(), android, locator.strategy(), locator.value(),
                        VerificationStatus.UNVERIFIED));
            }
            unresolved.addAll(outcome.unresolved());
            save(component.getKey(), mappings);
            initialized.add(component.getKey());
            logger.info("Global component " + component.getKey() + ": " + mappings.size() + " locators mapped, "
                    + outcome.unresolved().size() + " unresolved"
                    + (outcome.conflicts().isEmpty() ? "" : ", conflicts: " + outcome.conflicts().stream()
                    .map(c -> c.field().fieldName()).collect(Collectors.joining(", "))));
        }
        return new ComponentInitResult(initialized, unresolved);
    }

    private void save(ComponentId id, List<ScreenElementMapping> mappings) {
        cache.saveGlobalComponent(new GlobalComponentEntry(id, mappings, VerificationStatus.UNVERIFIED, clock.instant()));
    }
}
