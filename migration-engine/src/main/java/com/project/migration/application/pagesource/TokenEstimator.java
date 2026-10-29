package com.project.migration.application.pagesource;

/**
 * Local, model-independent token estimate (~4 characters per token for XML/English text). Used only for
 * savings telemetry; billed tokens come from the LLM responses.
 */
public final class TokenEstimator {

    static final int CHARS_PER_TOKEN = 4;

    public long estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }
}
