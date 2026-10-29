package com.project.migration.domain.model.mcp;

import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.Platform;

import java.time.Instant;

/**
 * Uncleaned {@code pageSource} XML as returned by an MCP server. Must go through
 * {@code XmlPageSourceCleaner} before it is stored or sent to an LLM.
 */
public record RawPageSource(Platform platform, String xml, Instant capturedAt) {

    public RawPageSource {
        Checks.notNull(platform, "platform");
        Checks.notBlank(xml, "xml");
        Checks.notNull(capturedAt, "capturedAt");
    }

    @Override
    public String toString() {
        return "RawPageSource[platform=" + platform + ", length=" + xml.length() + ", capturedAt=" + capturedAt + "]";
    }
}
