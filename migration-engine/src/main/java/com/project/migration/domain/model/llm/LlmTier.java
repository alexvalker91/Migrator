package com.project.migration.domain.model.llm;

/**
 * Cost/capability tier of the LLM used for a task. Concrete deployments are configured per tier
 * ({@code TierModelConfig}) and never referenced from domain or application code.
 */
public enum LlmTier {
    /** Vision + reasoning: complex platform logic adaptation, self-healing diagnosis. */
    TIER_1,
    /** Mid-size model: Dual-MCP navigation, standard locator mapping. */
    TIER_2,
    /** Cheap model: delta analysis, report summaries. */
    TIER_3
}
