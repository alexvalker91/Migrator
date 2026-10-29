package com.project.migration.application.healing;

import com.project.migration.application.codegen.IosLocatorValidator;
import com.project.migration.application.codegen.XmlElement;
import com.project.migration.domain.model.code.AddImport;
import com.project.migration.domain.model.code.AddIosLocator;
import com.project.migration.domain.model.code.BodyInspection;
import com.project.migration.domain.model.code.CodeChange;
import com.project.migration.domain.model.code.FieldRef;
import com.project.migration.domain.model.code.InsertCallBefore;
import com.project.migration.domain.model.code.IosLocatorStrategy;
import com.project.migration.domain.model.code.LocatorField;
import com.project.migration.domain.model.code.MethodRef;
import com.project.migration.domain.model.code.ReplaceMethodBody;
import com.project.migration.domain.port.CodeAnalyzerPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a {@link HealingProposal} before any file is touched. A proposal is accepted whole or not at all:
 * every change must target a class of the failing test's call tree (never a shared framework class), name
 * something that exists, and pass the same checks as Stage 3 (body inspection, locator check against the
 * iOS screen). The problems it returns go back to the model for one repair round.
 */
final class HealingProposalValidator {

    private static final Pattern IMPORT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");

    /** @param changes valid changes; empty whenever {@code problems} is not */
    record Validation(List<CodeChange> changes, List<String> problems) {
        boolean isValid() {
            return problems.isEmpty() && !changes.isEmpty();
        }
    }

    private final CodeAnalyzerPort analyzer;
    private final Set<String> allowedClasses;
    private final Map<FieldRef, LocatorField> locatorFields;
    private final List<XmlElement> iosScreen;

    /**
     * @param allowedClasses classes of the call tree and the test class itself
     * @param locatorFields  locator fields the call tree uses
     * @param iosScreen      cleaned iOS screen to check locators against; empty = no check possible
     */
    HealingProposalValidator(CodeAnalyzerPort analyzer, Set<String> allowedClasses,
                             Map<FieldRef, LocatorField> locatorFields, List<XmlElement> iosScreen) {
        this.analyzer = analyzer;
        this.allowedClasses = Set.copyOf(allowedClasses);
        this.locatorFields = Map.copyOf(locatorFields);
        this.iosScreen = List.copyOf(iosScreen);
    }

    Validation validate(HealingProposal proposal) {
        List<HealingProposal.Change> proposed = proposal.changes() == null ? List.of() : proposal.changes();
        if (proposed.isEmpty()) {
            return new Validation(List.of(), List.of("no change was proposed although the cause is in the code"));
        }
        List<CodeChange> changes = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (HealingProposal.Change change : proposed) {
            check(change, changes, problems);
        }
        return problems.isEmpty() ? new Validation(changes, List.of()) : new Validation(List.of(), problems);
    }

    private void check(HealingProposal.Change change, List<CodeChange> changes, List<String> problems) {
        if (change.kind() == null) {
            problems.add("a change has no kind");
            return;
        }
        if (change.targetClass() == null || !allowedClasses.contains(change.targetClass())) {
            problems.add(change.kind() + ": class '" + change.targetClass() + "' may not be changed; allowed: "
                    + allowedClasses + " (shared framework classes are never allowed)");
            return;
        }
        switch (change.kind()) {
            case REPLACE_METHOD_BODY -> replaceBody(change, changes, problems);
            case ADD_IOS_LOCATOR -> addLocator(change, changes, problems);
            case INSERT_CALL_BEFORE -> insertCall(change, changes, problems);
            case ADD_IMPORT -> addImport(change, changes, problems);
        }
    }

    private void replaceBody(HealingProposal.Change change, List<CodeChange> changes, List<String> problems) {
        Optional<MethodRef> method = existingMethod(change, problems);
        if (method.isEmpty() || blank(change.newBody())) {
            if (method.isPresent()) {
                problems.add("REPLACE_METHOD_BODY " + method.get().key() + ": newBody is empty");
            }
            return;
        }
        BodyInspection inspection = analyzer.inspectBody(method.get(), change.newBody());
        if (!inspection.isAcceptable()) {
            problems.add("REPLACE_METHOD_BODY " + method.get().key() + ": " + inspection.problems());
            return;
        }
        changes.add(new ReplaceMethodBody(method.get(), change.newBody()));
    }

    private void addLocator(HealingProposal.Change change, List<CodeChange> changes, List<String> problems) {
        FieldRef field = new FieldRef(change.targetClass(), String.valueOf(change.field()));
        LocatorField known = locatorFields.get(field);
        if (known == null) {
            problems.add("ADD_IOS_LOCATOR: " + field.key() + " is not a locator field used by this test");
            return;
        }
        Optional<IosLocatorStrategy> strategy = strategyOf(change.strategy());
        if (strategy.isEmpty() || blank(change.value())) {
            problems.add("ADD_IOS_LOCATOR " + field.key() + ": strategy must be accessibility, iOSNsPredicate or "
                    + "iOSClassChain and value must not be empty");
            return;
        }
        if (!iosScreen.isEmpty()) {
            IosLocatorValidator.Verdict verdict = IosLocatorValidator.validate(iosScreen, strategy.get(), change.value(),
                    known.collection());
            if (!verdict.isValid()) {
                problems.add("ADD_IOS_LOCATOR " + field.key() + ": " + strategy.get().annotationAttribute() + " \""
                        + change.value() + "\" " + verdict.problem().orElse(""));
                return;
            }
        }
        changes.add(new AddIosLocator(field, strategy.get(), change.value()));
    }

    private void insertCall(HealingProposal.Change change, List<CodeChange> changes, List<String> problems) {
        Optional<MethodRef> method = existingMethod(change, problems);
        if (method.isEmpty()) {
            return;
        }
        if (blank(change.anchorStatement()) || blank(change.statement())) {
            problems.add("INSERT_CALL_BEFORE " + method.get().key() + ": anchorStatement and statement are required");
            return;
        }
        changes.add(new InsertCallBefore(method.get(), change.anchorStatement(), change.statement()));
    }

    private void addImport(HealingProposal.Change change, List<CodeChange> changes, List<String> problems) {
        if (blank(change.importName()) || !IMPORT_NAME.matcher(change.importName().strip()).matches()) {
            problems.add("ADD_IMPORT: '" + change.importName() + "' is not a fully qualified class name");
            return;
        }
        changes.add(new AddImport(change.targetClass(), change.importName().strip(), false));
    }

    private Optional<MethodRef> existingMethod(HealingProposal.Change change, List<String> problems) {
        if (blank(change.method())) {
            problems.add(change.kind() + ": method name is required");
            return Optional.empty();
        }
        MethodRef method = new MethodRef(change.targetClass(), change.method().strip(),
                change.parameterTypes() == null ? List.of() : change.parameterTypes());
        if (analyzer.methodSource(method).isEmpty()) {
            problems.add(change.kind() + ": method " + method.key() + " does not exist (give the parameter types as "
                    + "written in the source)");
            return Optional.empty();
        }
        return Optional.of(method);
    }

    private static Optional<IosLocatorStrategy> strategyOf(String text) {
        if (text == null) {
            return Optional.empty();
        }
        for (IosLocatorStrategy strategy : IosLocatorStrategy.values()) {
            if (strategy.annotationAttribute().equalsIgnoreCase(text.strip())
                    || strategy.name().equals(text.strip().toUpperCase(Locale.ROOT))) {
                return Optional.of(strategy);
            }
        }
        return Optional.empty();
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
