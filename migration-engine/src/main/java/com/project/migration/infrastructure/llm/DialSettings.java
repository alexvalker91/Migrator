package com.project.migration.infrastructure.llm;

import java.util.Map;

/**
 * Connection to the EPAM AI DIAL gateway. Read from the environment only; the key is never logged or stored.
 *
 * @param host   {@code DIAL_HOST}, default {@code https://ai-proxy.lab.epam.com}
 * @param apiKey {@code DIAL_API_KEY}
 */
public record DialSettings(String host, String apiKey) {

    public static final String HOST_ENV = "DIAL_HOST";
    public static final String API_KEY_ENV = "DIAL_API_KEY";
    public static final String DEFAULT_HOST = "https://ai-proxy.lab.epam.com";

    public DialSettings {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("DIAL host must not be blank");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(API_KEY_ENV + " is not set");
        }
        host = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
    }

    public static DialSettings fromEnvironment() {
        return from(System.getenv());
    }

    static DialSettings from(Map<String, String> environment) {
        return new DialSettings(environment.getOrDefault(HOST_ENV, DEFAULT_HOST), environment.get(API_KEY_ENV));
    }

    /** {@code <host>/openai/deployments/<modelId>} */
    public String deploymentUrl(String modelId) {
        return host + "/openai/deployments/" + modelId;
    }

    @Override
    public String toString() {
        return "DialSettings[host=" + host + ", apiKey=***]";
    }
}
