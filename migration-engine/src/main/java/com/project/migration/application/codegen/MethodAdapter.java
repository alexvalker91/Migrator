package com.project.migration.application.codegen;

import com.project.migration.application.prompt.PromptTemplates;
import com.project.migration.domain.model.MigrationStage;
import com.project.migration.domain.model.Platform;
import com.project.migration.domain.model.analysis.ComplexityMarker;
import com.project.migration.domain.model.analysis.ComplexityMarkerType;
import com.project.migration.domain.model.code.AddImport;
import com.project.migration.domain.model.code.BodyInspection;
import com.project.migration.domain.model.code.CodeChange;
import com.project.migration.domain.model.code.FrameworkContract;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.ReplaceMethodBody;
import com.project.migration.domain.model.codegen.ManualAdaptation;
import com.project.migration.domain.model.llm.LlmRequest;
import com.project.migration.domain.model.llm.LlmTier;
import com.project.migration.domain.model.llm.SystemMessage;
import com.project.migration.domain.model.llm.UserMessage;
import com.project.migration.domain.model.screen.ScreenId;
import com.project.migration.domain.model.screen.ScreenState;
import com.project.migration.domain.model.screen.ScreenStateKey;
import com.project.migration.domain.port.CodeAnalyzerPort;
import com.project.migration.domain.port.LlmClientPort;
import com.project.migration.domain.port.MigrationLoggerPort;
import com.project.migration.domain.port.SnapshotRepositoryPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Stage 3.2: Tier-1 rewrite of methods that contain Android-specific logic (native scrolls, back key, keyboard,
 * notification shade, inline locators). A rewrite is accepted only when the analyzer confirms that the body
 * parses, no Android-specific construct remains and every call on {@code this} exists or is a documented helper.
 * A rejected rewrite gets one repair round; after that the method stays as it is and is reported.
 */
final class MethodAdapter {

    private static final Pattern IMPORT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");
    private static final int XML_LIMIT = 8_000;
    private static final Set<ComplexityMarkerType> NOT_ADAPTABLE = Set.of(ComplexityMarkerType.PLATFORM_SPECIFIC_FEATURE);

    private final CodeAnalyzerPort analyzer;
    private final SnapshotRepositoryPort snapshots;
    private final LlmClientPort llm;
    private final MigrationLoggerPort logger;

    MethodAdapter(CodeAnalyzerPort analyzer, SnapshotRepositoryPort snapshots, LlmClientPort llm,
                  MigrationLoggerPort logger) {
        this.analyzer = analyzer;
        this.snapshots = snapshots;
        this.llm = llm;
        this.logger = logger;
    }

    /** Whether the method has Android-specific logic that can be adapted (not just a platform-only feature). */
    boolean needsAdaptation(MethodRef method) {
        return analyzer.detectComplexityMarkers(method).stream().anyMatch(m -> !NOT_ADAPTABLE.contains(m.type()));
    }

    AdaptationOutcome adapt(List<MethodRef> methods, Map<String, ScreenId> screenOfClass) {
        List<CodeChange> changes = new ArrayList<>();
        List<MethodRef> adapted = new ArrayList<>();
        List<ManualAdaptation> manual = new ArrayList<>();
        for (MethodRef method : methods) {
            Set<ComplexityMarker> markers = analyzer.detectComplexityMarkers(method);
            Optional<String> source = analyzer.methodSource(method);
            if (source.isEmpty()) {
                manual.add(new ManualAdaptation(method, "source of the method is not available"));
                continue;
            }
            String xml = iosScreenXml(screenOfClass.get(method.classFqcn()));
            MethodRewrite rewrite = ask(method, markers, source.get(), xml, "");
            BodyInspection inspection = inspect(method, rewrite);
            if (!inspection.isAcceptable()) {
                logger.warn("Rewrite of " + method.key() + " rejected, one repair round: " + inspection.problems());
                rewrite = ask(method, markers, source.get(), xml, "\nYour previous rewrite was rejected by the "
                        + "engine's checks. Fix these problems and return the complete body again:\n"
                        + inspection.problems());
                inspection = inspect(method, rewrite);
            }
            if (!inspection.isAcceptable()) {
                manual.add(new ManualAdaptation(method, inspection.problems()));
                continue;
            }
            changes.add(new ReplaceMethodBody(method, rewrite.newBody()));
            validImports(rewrite).forEach(name -> changes.add(new AddImport(method.classFqcn(), name, false)));
            adapted.add(method);
        }
        return new AdaptationOutcome(changes, adapted, manual);
    }

    private BodyInspection inspect(MethodRef method, MethodRewrite rewrite) {
        if (rewrite.newBody() == null || rewrite.newBody().isBlank()) {
            return new BodyInspection(Optional.of("the answer holds no method body"), Set.of(), Set.of());
        }
        return analyzer.inspectBody(method, rewrite.newBody());
    }

    private static List<String> validImports(MethodRewrite rewrite) {
        return (rewrite.imports() == null ? List.<String>of() : rewrite.imports()).stream()
                .map(String::strip).filter(name -> IMPORT_NAME.matcher(name).matches()).distinct().toList();
    }

    private MethodRewrite ask(MethodRef method, Set<ComplexityMarker> markers, String source, String xml, String problems) {
        String system = PromptTemplates.render("method-adaptation.system", Map.of(
                "helpers", FrameworkContract.HELPER_SIGNATURES.stream().map(s -> "- " + s).collect(Collectors.joining("\n"))));
        String user = PromptTemplates.render("method-adaptation.user", Map.of(
                "class", method.classFqcn(),
                "method", method.methodName(),
                "markers", markers.stream().map(m -> "- " + m.type() + ": " + m.detail()).sorted()
                        .collect(Collectors.joining("\n")),
                "source", source,
                "iosXml", xml.isBlank() ? "(none)" : xml,
                "problems", problems));
        return llm.chat(LlmRequest.structured(LlmTier.TIER_1, MigrationStage.STAGE_3_CODEGEN,
                "method-adaptation:" + method.key() + (problems.isEmpty() ? "" : ":repair"),
                List.of(new SystemMessage(system), UserMessage.of(user)), MethodRewrite.class)
                .withReasoning(true)).requireContent();
    }

    /** DEFAULT iOS snapshot of the screen, or the first stored state; cut to a prompt-friendly size. */
    private String iosScreenXml(ScreenId screen) {
        if (screen == null) {
            return "";
        }
        Optional<ScreenStateKey> key = snapshots.findKeys(screen, Platform.IOS).stream()
                .min(java.util.Comparator.comparing((ScreenStateKey k) -> k.state() != ScreenState.DEFAULT)
                        .thenComparing(k -> k.state().ordinal()).thenComparing(ScreenStateKey::variant));
        String xml = key.flatMap(k -> snapshots.find(k, Platform.IOS)).map(snapshot -> snapshot.cleanXml()).orElse("");
        return xml.length() <= XML_LIMIT ? xml : xml.substring(0, XML_LIMIT) + "\n<!-- cut -->";
    }
}
