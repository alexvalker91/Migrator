package com.project.migration.application.healing;

import com.project.migration.application.agent.AgentLoop;
import com.project.migration.application.agent.AgentRunResult;
import com.project.migration.application.agent.AgentTask;
import com.project.migration.application.agent.AgentToolbox;
import com.project.migration.application.codegen.IosLocatorValidator;
import com.project.migration.application.codegen.XmlElement;
import com.project.migration.application.pagesource.XmlPageSourceCleaner;
import com.project.migration.application.prompt.PromptTemplates;
import com.project.migration.application.telemetry.TelemetryCollector;
import com.project.migration.domain.exception.CodeModificationException;
import com.project.migration.domain.exception.McpException;
import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.code.CodeChange;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.FrameworkContract;
import com.project.migration.domain.model.code.LocatorField;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.cache.IosLearnedRule;
import com.project.migration.domain.model.config.AgentLoopConfig;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.LlmResponse;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.llm.SystemMessage;
import com.project.migration.domain.model.llm.UserMessage;
import com.project.migration.domain.model.mcp.RawPageSource;
import com.project.migration.domain.model.media.ImageAttachment;
import com.project.migration.domain.model.report.HealingAttempt;
import com.project.migration.domain.model.run.CompilationResult;
import com.project.migration.domain.model.run.TestRunResult;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenState;
import com.project.migration.domain.model.screen.ScreenStateKey;
import com.project.migration.domain.port.ArtifactReaderPort;
import com.project.migration.domain.port.CacheRepositoryPort;
import com.project.migration.domain.port.CodeAnalyzerPort;
import com.project.migration.domain.port.CodeModifierPort;
import com.project.migration.domain.port.GitServicePort;
import com.project.migration.domain.port.LlmClientPort;
import com.project.migration.domain.port.McpClientPort;
import com.project.migration.domain.port.McpClientsPort;
import com.project.migration.domain.port.MigrationLoggerPort;
import com.project.migration.domain.port.SnapshotRepositoryPort;
import com.project.migration.domain.port.TestRunnerPort;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One healing attempt for a test that failed on iOS: gather the dossier, check hypotheses on the live iOS app
 * (bounded), let the Tier-1 vision model decide the root cause and propose changes, validate them, apply them and
 * compile. When the cause is not in the code (exhausted data, environment, shared framework) nothing is changed and
 * the attempt says so. When the code does not compile the attempt is reverted to the baseline, followed by the
 * batches of the earlier attempts, so their progress survives.
 */
public final class SelfHealingService {

    private static final int INVESTIGATION_STEPS = 10;
    private static final int INVESTIGATION_STEPS_WITHOUT_PROGRESS = 6;
    private static final long INVESTIGATION_TOKENS = 60_000;
    private static final int SOURCE_LIMIT = 3_000;
    private static final int XML_LIMIT = 8_000;
    private static final int PROPOSAL_ROUNDS = 2;

    private final CodeAnalyzerPort analyzer;
    private final CodeModifierPort modifier;
    private final TestRunnerPort runner;
    private final GitServicePort git;
    private final CacheRepositoryPort cache;
    private final SnapshotRepositoryPort snapshots;
    private final McpClientsPort mcps;
    private final LlmClientPort llm;
    private final ArtifactReaderPort artifacts;
    private final XmlPageSourceCleaner cleaner;
    private final TelemetryCollector telemetry;
    private final MigrationLoggerPort logger;
    private final EngineConfig config;
    private final Clock clock;

    public SelfHealingService(CodeAnalyzerPort analyzer, CodeModifierPort modifier, TestRunnerPort runner,
                              GitServicePort git, CacheRepositoryPort cache, SnapshotRepositoryPort snapshots,
                              McpClientsPort mcps, LlmClientPort llm, ArtifactReaderPort artifacts,
                              XmlPageSourceCleaner cleaner, TelemetryCollector telemetry, MigrationLoggerPort logger,
                              EngineConfig config, Clock clock) {
        this.analyzer = Checks.notNull(analyzer, "analyzer");
        this.modifier = Checks.notNull(modifier, "modifier");
        this.runner = Checks.notNull(runner, "runner");
        this.git = Checks.notNull(git, "git");
        this.cache = Checks.notNull(cache, "cache");
        this.snapshots = Checks.notNull(snapshots, "snapshots");
        this.mcps = Checks.notNull(mcps, "mcps");
        this.llm = Checks.notNull(llm, "llm");
        this.artifacts = Checks.notNull(artifacts, "artifacts");
        this.cleaner = Checks.notNull(cleaner, "cleaner");
        this.telemetry = Checks.notNull(telemetry, "telemetry");
        this.logger = Checks.notNull(logger, "logger");
        this.config = Checks.notNull(config, "config");
        this.clock = Checks.notNull(clock, "clock");
    }

    /**
     * @throws com.project.migration.domain.exception.LlmCallException if the gateway fails
     * @throws com.project.migration.domain.exception.GitOperationException if a revert fails
     */
    public HealingResult heal(HealingRequest request) {
        List<MethodRef> callTree = analyzer.resolveCallTree(request.test());
        Set<String> allowed = allowedClasses(request, callTree);
        Map<FieldRef, LocatorField> fields = locatorFields(callTree);

        Optional<ImageAttachment> screenshot = request.failedRun().failureScreenshot().flatMap(artifacts::readImage);
        Optional<FailureScreen> failureScreen = request.failedRun().failurePageSource().flatMap(artifacts::readText)
                .flatMap(this::cleanFailureXml);
        if (request.failedRun().failureScreenshot().isEmpty() || request.failedRun().failurePageSource().isEmpty()) {
            logger.warn("Failure evidence of " + request.test().methodName() + " is incomplete (screenshot "
                    + (screenshot.isPresent() ? "present" : "missing") + ", screen XML "
                    + (failureScreen.isPresent() ? "present" : "missing") + ")");
        }
        String failureXml = failureScreen.map(FailureScreen::xml).orElse("");
        List<XmlElement> iosScreen = failureScreen.map(FailureScreen::elements).orElseGet(() -> storedIosElements(callTree));

        String dossier = dossier(request, callTree, failureXml);
        String findings = investigate(request, dossier, screenshot);
        HealingProposalValidator validator = new HealingProposalValidator(analyzer, allowed, fields, iosScreen);

        HealingProposal proposal = null;
        Optional<String> reasoning = Optional.empty();
        String problems = "";
        List<CodeChange> applied = List.of();
        for (int round = 0; round < PROPOSAL_ROUNDS && applied.isEmpty(); round++) {
            LlmResponse<HealingProposal> response = llm.chat(proposalRequest(request, dossier, findings, allowed,
                    problems, screenshot));
            proposal = response.requireContent();
            reasoning = response.reasoning();
            HealingRootCause cause = proposal.rootCause() == null ? HealingRootCause.OTHER : proposal.rootCause();
            Optional<HealingResult> noCodeCause = noCodeResult(cause, proposal, reasoning);
            if (noCodeCause.isPresent()) {
                return noCodeCause.get();
            }
            HealingProposalValidator.Validation validation = validator.validate(proposal);
            if (!validation.isValid()) {
                problems = "\nYour previous proposal was rejected by the engine's checks. Fix these problems and "
                        + "answer again completely:\n- " + String.join("\n- ", validation.problems());
                logger.warn("Healing proposal rejected (round " + (round + 1) + "): " + validation.problems());
                continue;
            }
            try {
                modifier.apply(validation.changes());
                applied = validation.changes();
            } catch (CodeModificationException e) {
                problems = "\nYour previous proposal could not be applied: " + e.getMessage() + "\nAnswer again completely.";
                logger.warn("Healing proposal could not be applied: " + e.getMessage());
            }
        }
        String diagnosis = diagnosisOf(proposal);
        if (applied.isEmpty()) {
            return new HealingResult(HealingStatus.NO_VALID_CHANGES, diagnosis, reasoning,
                    "no valid change after a repair round: " + problems.strip().replace('\n', ' '), List.of());
        }

        CompilationResult compilation = runner.compile();
        if (!compilation.success()) {
            revert(request);
            String errors = compilation.errors().stream().limit(3)
                    .map(e -> e.file().getFileName() + ":" + e.line() + " " + e.message()).collect(Collectors.joining("; "));
            return new HealingResult(HealingStatus.COMPILATION_FAILED, diagnosis, reasoning,
                    "the changes did not compile and were reverted: " + errors, List.of());
        }
        saveLearnedRule(request, proposal);
        return new HealingResult(HealingStatus.APPLIED, diagnosis, reasoning, summaryOf(proposal), applied);
    }

    private Optional<HealingResult> noCodeResult(HealingRootCause cause, HealingProposal proposal, Optional<String> reasoning) {
        HealingStatus status = switch (cause) {
            case TEST_DATA -> HealingStatus.DATA_EXHAUSTED;
            case SHARED_FRAMEWORK -> HealingStatus.SHARED_FRAMEWORK;
            case ENVIRONMENT -> HealingStatus.ENVIRONMENT;
            default -> null;
        };
        if (status == null) {
            return Optional.empty();
        }
        logger.info("Healing stops: root cause " + cause + " is not fixable in the test code");
        return Optional.of(new HealingResult(status, diagnosisOf(proposal), reasoning, summaryOf(proposal), List.of()));
    }

    /** Back to the baseline commit, then the batches of the earlier attempts again. */
    private void revert(HealingRequest request) {
        git.restoreFiles(git.changedFilesSince(request.baseline()), request.baseline());
        for (List<CodeChange> batch : request.appliedBatches()) {
            modifier.apply(batch);
        }
    }

    private void saveLearnedRule(HealingRequest request, HealingProposal proposal) {
        HealingProposal.LearnedRule rule = proposal.learnedRule();
        if (rule == null || rule.id() == null || rule.id().isBlank() || rule.description() == null
                || rule.description().isBlank()) {
            return;
        }
        String id = rule.id().strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        cache.saveLearnedRule(new IosLearnedRule(id, rule.description().strip(), Optional.of(request.test()), clock.instant()));
        logger.info("Learned iOS rule '" + id + "': " + rule.description().strip());
    }

    private String investigate(HealingRequest request, String dossier, Optional<ImageAttachment> screenshot) {
        AgentLoopConfig loopConfig = new AgentLoopConfig(INVESTIGATION_STEPS, INVESTIGATION_STEPS_WITHOUT_PROGRESS,
                INVESTIGATION_TOKENS, config.agentLoop().toolCallTimeout(), config.agentLoop().maxToolResultChars(),
                config.agentLoop().allowedTools());
        try {
            McpClientPort ios = mcps.forPlatform(Platform.IOS);
            ios.restartApp();
            AgentTask task = new AgentTask(LlmTier.TIER_1, MigrationStage.STAGE_4_SELF_HEALING,
                    "heal-investigate:" + request.test().key(),
                    PromptTemplates.render("healing-investigation.system", Map.of()),
                    PromptTemplates.render("healing-investigation.user", Map.of("dossier", dossier)), true,
                    screenshot.map(List::of).orElse(List.of()));
            AgentRunResult run = new AgentLoop(llm, loopConfig, logger).run(task,
                    new AgentToolbox(ios, loopConfig, cleaner, telemetry));
            return run.finalText().isBlank() ? "(the live check ended without findings: " + run.outcome() + ")"
                    : run.finalText();
        } catch (McpException e) {
            logger.warn("Live iOS check unavailable: " + e.getMessage());
            return "(the live iOS check was not possible: " + e.getMessage() + ")";
        }
    }

    private LlmRequest<HealingProposal> proposalRequest(HealingRequest request, String dossier, String findings,
                                                         Set<String> allowed, String problems,
                                                         Optional<ImageAttachment> screenshot) {
        String system = PromptTemplates.render("healing.system", Map.of(
                "helpers", FrameworkContract.HELPER_SIGNATURES.stream().map(s -> "  - " + s).collect(Collectors.joining("\n")),
                "forms", IosLocatorValidator.ALLOWED_FORMS));
        String user = PromptTemplates.render("healing.user", Map.of(
                "dossier", dossier + "\n\n## Findings of the live iOS check\n" + findings,
                "allowed", String.join(", ", allowed),
                "problems", problems));
        UserMessage message = new UserMessage(user, screenshot.map(List::of).orElse(List.of()));
        return LlmRequest.structured(LlmTier.TIER_1, MigrationStage.STAGE_4_SELF_HEALING,
                "heal:" + request.test().key() + "#" + request.attemptNumber() + (problems.isEmpty() ? "" : ":repair"),
                List.of(new SystemMessage(system), message), HealingProposal.class).withReasoning(true);
    }

    private String dossier(HealingRequest request, List<MethodRef> callTree, String failureXml) {
        TestRunResult run = request.failedRun();
        StringBuilder text = new StringBuilder("## Failure of ").append(request.test().key()).append(" on iOS\n");
        run.failure().ifPresent(failure -> text.append("Message: ").append(failure.message()).append("\nStack trace:\n```\n")
                .append(cut(failure.stackTrace(), SOURCE_LIMIT)).append("\n```\n"));
        text.append("Passes of this test in a row before this failure: ").append(request.passesBeforeFailure())
                .append(request.passesBeforeFailure() > 0 ? " (so check whether test data is used up)\n" : "\n");
        text.append("\n## Screen where it failed (compressed XML)\n")
                .append(failureXml.isEmpty() ? "(not available)\n" : "```xml\n" + cut(failureXml, XML_LIMIT) + "\n```\n");
        text.append("\n## Test\n```java\n").append(cut(analyzer.testSource(request.test()).orElse("// source unavailable"), SOURCE_LIMIT))
                .append("\n```\n");
        String setup = String.join("\n\n", analyzer.setupMethodSources(request.test().classFqcn()));
        if (!setup.isBlank()) {
            text.append("\n## Setup code\n```java\n").append(cut(setup, SOURCE_LIMIT)).append("\n```\n");
        }
        text.append("\n## Methods the test calls\n");
        for (MethodRef method : callTree) {
            text.append("`").append(method.key()).append("`\n```java\n")
                    .append(cut(analyzer.methodSource(method).orElse("// source unavailable"), SOURCE_LIMIT)).append("\n```\n");
        }
        text.append(storedSnapshots(callTree));
        List<IosLearnedRule> rules = cache.findLearnedRules();
        if (!rules.isEmpty()) {
            text.append("\n## What is already known about the iOS app\n");
            rules.forEach(rule -> text.append("- ").append(rule.description()).append('\n'));
        }
        if (!request.previousAttempts().isEmpty()) {
            text.append("\n## Earlier attempts on this test (their changes are still applied unless noted)\n");
            for (HealingAttempt attempt : request.previousAttempts()) {
                text.append(attempt.attemptNumber()).append(". ").append(attempt.diagnosis()).append(" -> ")
                        .append(attempt.changeSummary()).append(" [").append(attempt.outcome()).append("]\n");
            }
        }
        return text.toString();
    }

    /** The stored DEFAULT snapshots of the screens the test passes through (Android reference and iOS). */
    private String storedSnapshots(List<MethodRef> callTree) {
        StringBuilder text = new StringBuilder();
        for (ScreenId screen : screensOf(callTree)) {
            ScreenStateKey key = ScreenStateKey.of(screen, ScreenState.DEFAULT);
            for (Platform platform : Platform.values()) {
                snapshots.find(key, platform).ifPresent(snapshot -> text.append("\n## Stored ").append(platform)
                        .append(" snapshot ").append(key).append("\n```xml\n").append(cut(snapshot.cleanXml(), XML_LIMIT / 2))
                        .append("\n```\n"));
            }
        }
        return text.toString();
    }

    private List<XmlElement> storedIosElements(List<MethodRef> callTree) {
        List<XmlElement> elements = new ArrayList<>();
        for (ScreenId screen : screensOf(callTree)) {
            snapshots.findKeys(screen, Platform.IOS).stream()
                    .sorted(Comparator.comparing((ScreenStateKey k) -> k.state() != ScreenState.DEFAULT))
                    .forEach(key -> snapshots.find(key, Platform.IOS)
                            .ifPresent(snapshot -> elements.addAll(XmlElement.parse(snapshot.cleanXml()))));
        }
        return elements;
    }

    private Set<ScreenId> screensOf(List<MethodRef> callTree) {
        Set<ScreenId> screens = new LinkedHashSet<>();
        for (MethodRef method : callTree) {
            try {
                screens.add(new ScreenId(method.simpleClassName()));
            } catch (IllegalArgumentException ignored) {
                // a class name that is not a file-system safe screen id has no stored snapshots
            }
        }
        return screens;
    }

    private Optional<FailureScreen> cleanFailureXml(String rawXml) {
        try {
            var cleaned = cleaner.clean(new RawPageSource(Platform.IOS, rawXml, clock.instant()));
            telemetry.recordCleaning(cleaned);
            return Optional.of(new FailureScreen(cleaned.cleanedXml(), XmlElement.parse(cleaned.cleanedXml())));
        } catch (McpException | IllegalArgumentException e) {
            logger.warn("The saved failure screen XML is unusable: " + e.getMessage());
            return Optional.empty();
        }
    }

    private Set<String> allowedClasses(HealingRequest request, List<MethodRef> callTree) {
        Set<String> classes = new LinkedHashSet<>();
        classes.add(request.test().classFqcn());
        callTree.forEach(method -> classes.add(method.classFqcn()));
        classes.removeIf(config.framework()::isFrameworkBaseClass);
        return classes;
    }

    private Map<FieldRef, LocatorField> locatorFields(List<MethodRef> callTree) {
        Map<FieldRef, LocatorField> fields = new LinkedHashMap<>();
        callTree.forEach(method -> analyzer.findLocatorFields(method).forEach(f -> fields.putIfAbsent(f.field(), f)));
        return fields;
    }

    private static String diagnosisOf(HealingProposal proposal) {
        return proposal == null || proposal.diagnosis() == null || proposal.diagnosis().isBlank()
                ? "(the model gave no diagnosis)" : proposal.diagnosis().strip();
    }

    private static String summaryOf(HealingProposal proposal) {
        return proposal == null || proposal.changeSummary() == null || proposal.changeSummary().isBlank()
                ? "(no summary)" : proposal.changeSummary().strip();
    }

    private static String cut(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "\n... cut";
    }

    /** The screen where the test failed, cleaned: as text for the prompt and as elements for locator checks. */
    private record FailureScreen(String xml, List<XmlElement> elements) {
    }
}
