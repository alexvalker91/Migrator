package com.project.migration.infrastructure.cache;

import com.project.migration.domain.exception.CacheCorruptedException;
import com.project.migration.domain.model.cache.GlobalComponentEntry;
import com.project.migration.domain.model.cache.IosLearnedRule;
import com.project.migration.domain.model.cache.ScreenCacheEntry;
import com.project.migration.domain.model.cache.ScreenElementMapping;
import com.project.migration.domain.model.cache.StepCacheEntry;
import com.project.migration.domain.model.cache.TestCacheEntry;
import com.project.migration.domain.model.cache.VerificationStatus;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.IosLocatorStrategy;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.TestMethodRef;
import com.project.migration.domain.model.report.TestMigrationStatus;
import com.project.migration.domain.model.screen.ComponentId;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenState;
import com.project.migration.domain.model.screen.ScreenStateKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonCacheRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
    private static final ScreenId LOGIN = new ScreenId("LoginPage");
    private static final TestMethodRef LOGIN_TEST = new TestMethodRef("webdriverio.LoginTest", "loginNegativeTest");
    private static final MethodRef ENTER_EMAIL = MethodRef.of("alex.valker91.page.LoginPage", "enterEmail", "String");

    @TempDir
    Path migrationDir;

    private JsonCacheRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JsonCacheRepository(migrationDir, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void emptyKnowledgeBaseYieldsEmptyResults() {
        assertThat(repository.findGlobalComponents()).isEmpty();
        assertThat(repository.findLearnedRules()).isEmpty();
        assertThat(repository.findScreen(LOGIN)).isEmpty();
        assertThat(repository.findAllScreens()).isEmpty();
        assertThat(repository.findStep(ENTER_EMAIL)).isEmpty();
        assertThat(repository.findTest(LOGIN_TEST)).isEmpty();
        assertThat(repository.findAllTests()).isEmpty();
        assertThat(migrationDir).isEmptyDirectory();
    }

    @Test
    void screenRoundTripsThroughItsOwnShard() {
        ScreenCacheEntry screen = ScreenCacheEntry.empty(LOGIN, NOW)
                .withState(ScreenStateKey.of(LOGIN, ScreenState.DEFAULT), VerificationStatus.VERIFIED_STABLE, NOW)
                .withState(ScreenStateKey.of(LOGIN, ScreenState.BOTTOM_SHEET, "card_picker"),
                        VerificationStatus.UNVERIFIED, NOW)
                .withMapping(mapping("emailField"), NOW);

        repository.saveScreen(screen);

        assertThat(repository.findScreen(LOGIN)).contains(screen);
        assertThat(repository.findAllScreens()).containsExactly(screen);
        assertThat(migrationDir.resolve("screens/LoginPage.json")).exists();
    }

    @Test
    void stepsOfOneClassShareAShardAndOverloadsStayApart() {
        MethodRef overload = MethodRef.of(ENTER_EMAIL.classFqcn(), "enterEmail", "String", "boolean");
        StepCacheEntry first = StepCacheEntry.verified(ENTER_EMAIL, LOGIN_TEST, NOW);
        StepCacheEntry second = new StepCacheEntry(overload, VerificationStatus.UNVERIFIED, Optional.empty(), NOW);

        repository.saveStep(first);
        repository.saveStep(second);
        repository.saveStep(StepCacheEntry.verified(overload, LOGIN_TEST, NOW));

        assertThat(repository.findStep(ENTER_EMAIL)).contains(first);
        assertThat(repository.findStep(overload)).map(StepCacheEntry::status).contains(VerificationStatus.VERIFIED_STABLE);
        try (var files = Files.list(migrationDir.resolve("steps"))) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .containsExactly("alex.valker91.page.LoginPage.json");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void testsAreShardedPerTestClassAndReplacedByMethod() {
        repository.saveTest(new TestCacheEntry(LOGIN_TEST, TestMigrationStatus.IN_PROGRESS, NOW));
        repository.saveTest(new TestCacheEntry(LOGIN_TEST, TestMigrationStatus.STABLE_PASS, NOW));
        TestCacheEntry other = new TestCacheEntry(new TestMethodRef("webdriverio.CardsTest", "open"),
                TestMigrationStatus.NEEDS_MANUAL_FIX, NOW);
        repository.saveTest(other);

        assertThat(repository.findTest(LOGIN_TEST)).map(TestCacheEntry::status).contains(TestMigrationStatus.STABLE_PASS);
        assertThat(repository.findAllTests()).hasSize(2).contains(other);
    }

    @Test
    void globalSectionsLiveInTheIndexWithADerivedSummary() throws IOException {
        GlobalComponentEntry topBar = new GlobalComponentEntry(new ComponentId("TopAppBar"),
                List.of(mapping("backButton")), VerificationStatus.VERIFIED_STABLE, NOW);
        IosLearnedRule rule = new IosLearnedRule("nbsp-in-amounts", "Amounts use U+00A0 before the currency",
                Optional.of(LOGIN_TEST), NOW);
        repository.saveGlobalComponent(topBar);
        repository.saveLearnedRule(rule);
        repository.saveLearnedRule(new IosLearnedRule("nbsp-in-amounts", "updated", Optional.empty(), NOW));
        repository.saveTest(new TestCacheEntry(LOGIN_TEST, TestMigrationStatus.STABLE_PASS, NOW));

        assertThat(repository.findGlobalComponent(new ComponentId("TopAppBar"))).contains(topBar);
        assertThat(repository.findLearnedRules()).singleElement()
                .satisfies(saved -> assertThat(saved.description()).isEqualTo("updated"));
        String index = Files.readString(migrationDir.resolve("migration_cache.json"));
        assertThat(index)
                .contains("\"schema_version\" : 1", "\"global_components\"", "\"ios_learned_rules\"")
                .contains("\"tests\" : {", "\"STABLE_PASS\" : 1");
    }

    @Test
    void unparsableShardIsReportedAsCorruptedAndNeverOverwritten() throws IOException {
        Path shard = Files.createDirectories(migrationDir.resolve("screens")).resolve("LoginPage.json");
        Files.writeString(shard, "{ not json");

        assertThatThrownBy(() -> repository.findScreen(LOGIN))
                .isInstanceOf(CacheCorruptedException.class)
                .hasMessageContaining("LoginPage.json");
        assertThatThrownBy(() -> repository.saveScreen(ScreenCacheEntry.empty(LOGIN, NOW)))
                .isInstanceOf(CacheCorruptedException.class);
        assertThat(Files.readString(shard)).isEqualTo("{ not json");
    }

    @Test
    void newerSchemaVersionIsRejected() throws IOException {
        Files.writeString(migrationDir.resolve("migration_cache.json"),
                "{\"schema_version\": 2, \"global_components\": [], \"ios_learned_rules\": []}");

        assertThatThrownBy(() -> repository.findGlobalComponents())
                .isInstanceOf(CacheCorruptedException.class)
                .hasMessageContaining("schema_version 2");
    }

    @Test
    void domainInconsistencyInAFileIsCorruption() throws IOException {
        Path shard = Files.createDirectories(migrationDir.resolve("screens")).resolve("LoginPage.json");
        Files.writeString(shard, """
                {"schema_version": 1, "screen": "bad name!", "states": [], "mappings": [],
                 "updated_at": "2026-10-29T10:00:00Z"}
                """);

        assertThatThrownBy(() -> repository.findScreen(LOGIN)).isInstanceOf(CacheCorruptedException.class);
    }

    @Test
    void concurrentSavesToOneShardLoseNothing() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> saves = IntStream.range(0, 40)
                    .mapToObj(i -> pool.submit(() -> repository.saveStep(
                            StepCacheEntry.verified(MethodRef.of(ENTER_EMAIL.classFqcn(), "step" + i), LOGIN_TEST, NOW))))
                    .<Future<?>>map(future -> future)
                    .toList();
            for (Future<?> save : saves) {
                save.get();
            }
        } finally {
            pool.shutdown();
        }

        assertThat(IntStream.range(0, 40)
                .filter(i -> repository.findStep(MethodRef.of(ENTER_EMAIL.classFqcn(), "step" + i)).isPresent())
                .count()).isEqualTo(40);
    }

    private static ScreenElementMapping mapping(String field) {
        return new ScreenElementMapping(new FieldRef("alex.valker91.page.LoginPage", field),
                "accessibility = \"" + field + "\"", IosLocatorStrategy.ACCESSIBILITY_ID, field,
                VerificationStatus.UNVERIFIED);
    }
}
