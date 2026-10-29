package com.project.migration.application.healing;

import java.util.List;

/**
 * Structured answer of the Tier-1 model in self-healing. Validated by {@link HealingProposalValidator} before
 * anything is applied.
 *
 * @param diagnosis    what failed and why, for the report
 * @param changeSummary one line on what the changes do
 * @param changes      empty when the cause is not in the code (data, environment, shared framework)
 * @param learnedRule  something about the iOS app worth remembering for later tests, if any
 */
public record HealingProposal(
        String diagnosis,
        HealingRootCause rootCause,
        String changeSummary,
        List<Change> changes,
        LearnedRule learnedRule) {

    /**
     * One change; the fields that do not apply to its kind stay empty.
     * <ul>
     * <li>REPLACE_METHOD_BODY: targetClass, method, parameterTypes, newBody</li>
     * <li>ADD_IOS_LOCATOR: targetClass, field, strategy (accessibility | iOSNsPredicate | iOSClassChain), value</li>
     * <li>INSERT_CALL_BEFORE: targetClass, method, parameterTypes, anchorStatement, statement</li>
     * <li>ADD_IMPORT: targetClass, importName</li>
     * </ul>
     */
    public record Change(
            ChangeKind kind,
            String targetClass,
            String method,
            List<String> parameterTypes,
            String field,
            String strategy,
            String value,
            String anchorStatement,
            String statement,
            String newBody,
            String importName) {
    }

    public record LearnedRule(String id, String description) {
    }
}
