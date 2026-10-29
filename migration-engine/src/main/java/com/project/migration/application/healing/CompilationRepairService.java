package com.project.migration.application.healing;

import com.project.migration.application.prompt.PromptTemplates;
import com.project.migration.domain.exception.CodeModificationException;
import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.FrameworkContract;
import com.project.migration.domain.model.code.LocatorField;
import com.project.migration.domain.model.config.EngineConfig;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.llm.SystemMessage;
import com.project.migration.domain.model.llm.UserMessage;
import com.project.migration.domain.model.run.CompilationError;
import com.project.migration.domain.port.ArtifactStorePort;
import com.project.migration.domain.port.CodeAnalyzerPort;
import com.project.migration.domain.port.CodeModifierPort;
import com.project.migration.domain.port.LlmClientPort;
import com.project.migration.domain.port.MigrationLoggerPort;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stage 3.3 repair: when the generated code does not compile, Tier-1 gets the compiler errors and the current
 * source of the involved files and proposes minimal changes. They pass the same checks as every other change
 * (allowed classes only, existing methods, body inspection); a proposal that fails them gets one repeat in the
 * same round.
 */
public final class CompilationRepairService implements CompilationRepairer {

    private static final int FILE_LIMIT = 12_000;
    private static final int ROUNDS = 2;

    private final CodeAnalyzerPort analyzer;
    private final CodeModifierPort modifier;
    private final LlmClientPort llm;
    private final ArtifactStorePort artifacts;
    private final MigrationLoggerPort logger;
    private final EngineConfig config;

    public CompilationRepairService(CodeAnalyzerPort analyzer, CodeModifierPort modifier, LlmClientPort llm,
                                    ArtifactStorePort artifacts, MigrationLoggerPort logger, EngineConfig config) {
        this.analyzer = Checks.notNull(analyzer, "analyzer");
        this.modifier = Checks.notNull(modifier, "modifier");
        this.llm = Checks.notNull(llm, "llm");
        this.artifacts = Checks.notNull(artifacts, "artifacts");
        this.logger = Checks.notNull(logger, "logger");
        this.config = Checks.notNull(config, "config");
    }

    @Override
    public Optional<String> repair(List<CompilationError> errors, Set<Path> changedFiles) {
        Set<String> allowed = new LinkedHashSet<>();
        changedFiles.forEach(file -> classOf(file).ifPresent(allowed::add));
        errors.forEach(error -> classOf(error.file()).ifPresent(allowed::add));
        allowed.removeIf(config.framework()::isFrameworkBaseClass);
        if (allowed.isEmpty()) {
            logger.warn("No project class to repair for the compilation errors");
            return Optional.empty();
        }
        Map<FieldRef, LocatorField> fields = new LinkedHashMap<>();
        allowed.forEach(fqcn -> analyzer.listLocatorFields(fqcn).forEach(f -> fields.put(f.field(), f)));
        HealingProposalValidator validator = new HealingProposalValidator(analyzer, allowed, fields, List.of());

        String problems = "";
        for (int round = 0; round < ROUNDS; round++) {
            CompileFix fix = llm.chat(request(errors, allowed, problems)).requireContent();
            HealingProposal proposal = new HealingProposal(String.valueOf(fix.explanation()), HealingRootCause.OTHER,
                    String.valueOf(fix.explanation()), fix.changes(), null);
            HealingProposalValidator.Validation validation = validator.validate(proposal);
            if (!validation.isValid()) {
                problems = "\nYour previous answer was rejected by the engine's checks. Fix these problems and answer "
                        + "again completely:\n- " + String.join("\n- ", validation.problems());
                logger.warn("Compile fix rejected: " + validation.problems());
                continue;
            }
            try {
                modifier.apply(validation.changes());
                return Optional.of(String.valueOf(fix.explanation()).strip());
            } catch (CodeModificationException e) {
                problems = "\nYour previous answer could not be applied: " + e.getMessage() + "\nAnswer again completely.";
                logger.warn("Compile fix could not be applied: " + e.getMessage());
            }
        }
        return Optional.empty();
    }

    private LlmRequest<CompileFix> request(List<CompilationError> errors, Set<String> allowed, String problems) {
        String errorText = errors.stream().limit(30)
                .map(e -> e.file().getFileName() + ":" + e.line() + " " + e.message()).collect(Collectors.joining("\n"));
        String sources = allowed.stream().map(fqcn -> analyzer.sourceFileOf(fqcn)
                        .flatMap(artifacts::readText)
                        .map(text -> "`" + fqcn + "`\n```java\n" + (text.length() > FILE_LIMIT ? text.substring(0, FILE_LIMIT)
                                + "\n// ... cut" : text) + "\n```").orElse("`" + fqcn + "` (source unavailable)"))
                .collect(Collectors.joining("\n\n"));
        String system = PromptTemplates.render("compile-repair.system", Map.of("helpers",
                FrameworkContract.HELPER_SIGNATURES.stream().map(s -> "  - " + s).collect(Collectors.joining("\n"))));
        String user = PromptTemplates.render("compile-repair.user", Map.of("errors", errorText,
                "allowed", String.join(", ", allowed), "sources", sources, "problems", problems));
        return LlmRequest.structured(LlmTier.TIER_1, MigrationStage.STAGE_3_CODEGEN,
                "compile-repair" + (problems.isEmpty() ? "" : ":repeat"),
                List.of(new SystemMessage(system), UserMessage.of(user)), CompileFix.class).withReasoning(true);
    }

    /** FQCN of a source file: what follows the last {@code /java/} segment, without the extension. */
    static Optional<String> classOf(Path file) {
        String path = file.toString().replace('\\', '/');
        int marker = path.lastIndexOf("/java/");
        if (!path.endsWith(".java") || marker < 0) {
            return Optional.empty();
        }
        return Optional.of(path.substring(marker + "/java/".length(), path.length() - ".java".length()).replace('/', '.'));
    }
}
