package com.project.migration.infrastructure.cache;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.migration.domain.exception.CacheCorruptedException;
import com.project.migration.domain.model.cache.GlobalComponentEntry;
import com.project.migration.domain.model.cache.IosLearnedRule;
import com.project.migration.domain.model.cache.ScreenCacheEntry;
import com.project.migration.domain.model.cache.StepCacheEntry;
import com.project.migration.domain.model.cache.TestCacheEntry;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.screen.ComponentId;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.port.CacheRepositoryPort;
import com.project.migration.infrastructure.fs.AtomicFiles;
import com.project.migration.infrastructure.fs.DirectoryLock;
import com.project.migration.infrastructure.json.JsonMappers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

/**
 * {@link CacheRepositoryPort} over a sharded JSON knowledge base:
 * <pre>
 * .migration/migration_cache.json        schema_version, global_components, ios_learned_rules, summary
 * .migration/screens/&lt;ScreenName&gt;.json   one screen
 * .migration/steps/&lt;ClassFqcn&gt;.json      verified methods of one Page Object / step class
 * .migration/tests/&lt;TestClassFqcn&gt;.json  test statuses of one test class
 * </pre>
 * Shards are the source of truth; the index summary is a derived view regenerated after every save. Writes are
 * atomic (temp + rename) and serialized across processes with a {@link DirectoryLock}; reads need no lock.
 * Nothing is cached in memory, so parallel engine processes always see each other's writes.
 */
public final class JsonCacheRepository implements CacheRepositoryPort {

    static final String INDEX_FILE = "migration_cache.json";
    static final String SCREENS_DIR = "screens";
    static final String STEPS_DIR = "steps";
    static final String TESTS_DIR = "tests";
    private static final String JSON = ".json";

    private final Path root;
    private final Clock clock;
    private final DirectoryLock lock;
    private final ObjectMapper mapper = JsonMappers.persistence();

    public JsonCacheRepository(Path migrationDir, Clock clock) {
        this.root = migrationDir.toAbsolutePath().normalize();
        this.clock = clock;
        this.lock = new DirectoryLock(root);
    }

    // ---- global sections (index) ----

    @Override
    public List<GlobalComponentEntry> findGlobalComponents() {
        return readIndex().globalComponents().stream().map(CacheJsonMapper::toDomain).toList();
    }

    @Override
    public Optional<GlobalComponentEntry> findGlobalComponent(ComponentId component) {
        return findGlobalComponents().stream().filter(entry -> entry.component().equals(component)).findFirst();
    }

    @Override
    public void saveGlobalComponent(GlobalComponentEntry entry) {
        lock.withLock(() -> {
            CacheJson.Index index = readIndex();
            List<CacheJson.GlobalComponent> components = new ArrayList<>(index.globalComponents().stream()
                    .filter(existing -> !existing.component().equals(entry.component().value())).toList());
            components.add(CacheJsonMapper.toJson(entry));
            components.sort(Comparator.comparing(CacheJson.GlobalComponent::component));
            writeIndex(new CacheJson.Index(CacheJson.SCHEMA_VERSION, components, index.iosLearnedRules(), null));
        });
    }

    @Override
    public List<IosLearnedRule> findLearnedRules() {
        return readIndex().iosLearnedRules().stream().map(CacheJsonMapper::toDomain).toList();
    }

    @Override
    public void saveLearnedRule(IosLearnedRule rule) {
        lock.withLock(() -> {
            CacheJson.Index index = readIndex();
            List<CacheJson.LearnedRule> rules = new ArrayList<>(index.iosLearnedRules().stream()
                    .filter(existing -> !existing.id().equals(rule.id())).toList());
            rules.add(CacheJsonMapper.toJson(rule));
            rules.sort(Comparator.comparing(CacheJson.LearnedRule::learnedAt).thenComparing(CacheJson.LearnedRule::id));
            writeIndex(new CacheJson.Index(CacheJson.SCHEMA_VERSION, index.globalComponents(), rules, null));
        });
    }

    // ---- screens ----

    @Override
    public Optional<ScreenCacheEntry> findScreen(ScreenId screen) {
        return read(screenFile(screen.value()), CacheJson.ScreenShard.class, CacheJson.ScreenShard::schemaVersion)
                .map(shard -> convert(screenFile(screen.value()), shard, CacheJsonMapper::toDomain));
    }

    @Override
    public List<ScreenCacheEntry> findAllScreens() {
        return shardFiles(SCREENS_DIR).stream()
                .map(file -> read(file, CacheJson.ScreenShard.class, CacheJson.ScreenShard::schemaVersion)
                        .map(shard -> convert(file, shard, CacheJsonMapper::toDomain)))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public void saveScreen(ScreenCacheEntry entry) {
        lock.withLock(() -> {
            findScreen(entry.screen()); // a corrupted shard throws here instead of being overwritten
            write(screenFile(entry.screen().value()), CacheJsonMapper.toJson(entry));
            refreshSummary();
        });
    }

    // ---- steps ----

    @Override
    public Optional<StepCacheEntry> findStep(MethodRef step) {
        return readSteps(step.classFqcn()).stream().filter(entry -> entry.step().equals(step)).findFirst();
    }

    @Override
    public void saveStep(StepCacheEntry entry) {
        lock.withLock(() -> {
            String stepClass = entry.step().classFqcn();
            List<CacheJson.Step> steps = new ArrayList<>(readStepShard(stepClass)
                    .map(CacheJson.StepShard::steps).orElse(List.of()).stream()
                    .filter(existing -> !(existing.method().equals(entry.step().methodName())
                            && existing.parameterTypes().equals(entry.step().parameterTypes())))
                    .toList());
            steps.add(CacheJsonMapper.toJson(entry));
            steps.sort(Comparator.comparing(CacheJson.Step::method)
                    .thenComparing(step -> String.join(",", step.parameterTypes())));
            write(stepFile(stepClass), new CacheJson.StepShard(CacheJson.SCHEMA_VERSION, stepClass, steps));
            refreshSummary();
        });
    }

    // ---- tests ----

    @Override
    public Optional<TestCacheEntry> findTest(TestMethodRef test) {
        return readTests(testFile(test.classFqcn())).stream().filter(entry -> entry.test().equals(test)).findFirst();
    }

    @Override
    public List<TestCacheEntry> findAllTests() {
        return shardFiles(TESTS_DIR).stream().flatMap(file -> readTests(file).stream()).toList();
    }

    @Override
    public void saveTest(TestCacheEntry entry) {
        lock.withLock(() -> {
            String testClass = entry.test().classFqcn();
            Path file = testFile(testClass);
            List<CacheJson.Test> tests = new ArrayList<>(
                    read(file, CacheJson.TestShard.class, CacheJson.TestShard::schemaVersion)
                            .map(CacheJson.TestShard::tests).orElse(List.of()).stream()
                            .filter(existing -> !existing.method().equals(entry.test().methodName()))
                            .toList());
            tests.add(CacheJsonMapper.toJson(entry));
            tests.sort(Comparator.comparing(CacheJson.Test::method));
            write(file, new CacheJson.TestShard(CacheJson.SCHEMA_VERSION, testClass, tests));
            refreshSummary();
        });
    }

    // ---- internals ----

    private List<StepCacheEntry> readSteps(String stepClass) {
        Path file = stepFile(stepClass);
        return readStepShard(stepClass)
                .map(shard -> shard.steps().stream()
                        .map(step -> convert(file, step, json -> CacheJsonMapper.toDomain(shard.stepClass(), json)))
                        .toList())
                .orElse(List.of());
    }

    private Optional<CacheJson.StepShard> readStepShard(String stepClass) {
        return read(stepFile(stepClass), CacheJson.StepShard.class, CacheJson.StepShard::schemaVersion);
    }

    private List<TestCacheEntry> readTests(Path file) {
        return read(file, CacheJson.TestShard.class, CacheJson.TestShard::schemaVersion)
                .map(shard -> shard.tests().stream()
                        .map(test -> convert(file, test, json -> CacheJsonMapper.toDomain(shard.testClass(), json)))
                        .toList())
                .orElse(List.of());
    }

    private CacheJson.Index readIndex() {
        return read(root.resolve(INDEX_FILE), CacheJson.Index.class, CacheJson.Index::schemaVersion)
                .map(index -> new CacheJson.Index(index.schemaVersion(),
                        index.globalComponents() == null ? List.of() : index.globalComponents(),
                        index.iosLearnedRules() == null ? List.of() : index.iosLearnedRules(),
                        index.summary()))
                .orElse(CacheJson.Index.empty());
    }

    /** Writes the index with a freshly computed summary. Caller holds the lock. */
    private void writeIndex(CacheJson.Index index) {
        write(root.resolve(INDEX_FILE), new CacheJson.Index(CacheJson.SCHEMA_VERSION, index.globalComponents(),
                index.iosLearnedRules(), summary()));
    }

    private void refreshSummary() {
        writeIndex(readIndex());
    }

    private CacheJson.Summary summary() {
        Map<String, Long> screens = new TreeMap<>();
        findAllScreens().forEach(screen -> screens.merge(screen.status().name(), 1L, Long::sum));
        Map<String, Long> steps = new TreeMap<>();
        for (Path file : shardFiles(STEPS_DIR)) {
            read(file, CacheJson.StepShard.class, CacheJson.StepShard::schemaVersion).ifPresent(shard ->
                    shard.steps().forEach(step -> steps.merge(step.status().name(), 1L, Long::sum)));
        }
        Map<String, Long> tests = new TreeMap<>();
        findAllTests().forEach(test -> tests.merge(test.status().name(), 1L, Long::sum));
        return new CacheJson.Summary(clock.instant(), screens, steps, tests);
    }

    private <T> Optional<T> read(Path file, Class<T> type, ToIntFunction<T> schemaVersion) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        T value;
        try {
            value = mapper.readValue(file.toFile(), type);
        } catch (JacksonException e) {
            throw new CacheCorruptedException("Cannot parse cache file " + file + ": " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read cache file " + file, e);
        }
        if (value == null) {
            throw new CacheCorruptedException("Cache file " + file + " is empty");
        }
        int version = schemaVersion.applyAsInt(value);
        if (version != CacheJson.SCHEMA_VERSION) {
            throw new CacheCorruptedException("Cache file " + file + " has schema_version " + version
                    + "; this engine reads version " + CacheJson.SCHEMA_VERSION);
        }
        return Optional.of(value);
    }

    /** Domain validation failures of a parsed file mean the file is inconsistent, i.e. corrupted. */
    private static <J, D> D convert(Path file, J json, Function<J, D> toDomain) {
        try {
            return toDomain.apply(json);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new CacheCorruptedException("Inconsistent cache file " + file + ": " + e.getMessage(), e);
        }
    }

    private void write(Path file, Object value) {
        try {
            AtomicFiles.writeString(file, mapper.writeValueAsString(value) + System.lineSeparator());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write cache file " + file, e);
        }
    }

    private List<Path> shardFiles(String directory) {
        Path dir = root.resolve(directory);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> {
                        String name = file.getFileName().toString();
                        return name.endsWith(JSON) && !name.startsWith(".");
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + dir, e);
        }
    }

    private Path screenFile(String screen) {
        return root.resolve(SCREENS_DIR).resolve(screen + JSON);
    }

    private Path stepFile(String classFqcn) {
        return root.resolve(STEPS_DIR).resolve(classFqcn + JSON);
    }

    private Path testFile(String classFqcn) {
        return root.resolve(TESTS_DIR).resolve(classFqcn + JSON);
    }
}
