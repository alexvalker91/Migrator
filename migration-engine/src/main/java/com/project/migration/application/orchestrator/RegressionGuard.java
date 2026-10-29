package com.project.migration.application.orchestrator;

/**
 * Android regression guard as the orchestrator sees it.
 */
public interface RegressionGuard {

    RegressionResult check(String testClassFqcn);
}
