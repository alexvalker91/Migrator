package com.project.migration.testsupport;

import com.project.migration.domain.model.Platform;
import com.project.migration.domain.port.McpClientPort;
import com.project.migration.domain.port.McpClientsPort;

import java.util.EnumMap;
import java.util.Map;

/**
 * Both platforms' in-memory sessions.
 */
public final class FakeMcps implements McpClientsPort {

    public final FakeMcp android;
    public final FakeMcp ios;
    private final Map<Platform, FakeMcp> byPlatform = new EnumMap<>(Platform.class);

    public FakeMcps(String... toolNames) {
        android = new FakeMcp(Platform.ANDROID, toolNames);
        ios = new FakeMcp(Platform.IOS, toolNames);
        byPlatform.put(Platform.ANDROID, android);
        byPlatform.put(Platform.IOS, ios);
    }

    @Override
    public McpClientPort forPlatform(Platform platform) {
        return byPlatform.get(platform);
    }

    @Override
    public void close() {
        android.close();
        ios.close();
    }
}
