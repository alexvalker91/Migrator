package com.project.migration.domain.port;

import com.project.migration.domain.model.agent.AndroidWalkthrough;

/**
 * Keeps the recorded Android walkthroughs of a class run ({@code migration_reports/<TestClass>/walkthroughs/})
 * for diagnosis of failed or odd iOS passes.
 */
public interface WalkthroughSinkPort {

    /** Durable when it returns; a second walkthrough of the same test replaces the first. */
    void save(String testClassFqcn, AndroidWalkthrough walkthrough);
}
