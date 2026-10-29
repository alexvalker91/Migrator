package com.project.migration.infrastructure.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectLockTest {

    @TempDir
    Path migrationDir;

    @Test
    void theLockIsExclusiveWritesItsOwnerAndIsFreeAgainAfterClose() throws IOException {
        ProjectLock first = ProjectLock.acquire(migrationDir, "CardsTest,LoginTest");

        assertThat(Files.readString(migrationDir.resolve("engine.lock"))).contains("pid=" + ProcessHandle.current().pid())
                .contains("classes=CardsTest,LoginTest");
        assertThatThrownBy(() -> ProjectLock.acquire(migrationDir, "Other")).isInstanceOf(UsageException.class)
                .hasMessageContaining("already running in this process");

        first.close();
        first.close();
        try (ProjectLock second = ProjectLock.acquire(migrationDir, "Other")) {
            assertThat(Files.readString(migrationDir.resolve("engine.lock"))).contains("classes=Other");
        }
    }

    @Test
    void anotherProcessHoldingTheProjectIsNamedInTheRefusal() throws Exception {
        Process holder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("java.class.path"), LockHolderMain.class.getName(), migrationDir.toString())
                .redirectErrorStream(true).start();
        try {
            String first = new BufferedReader(new InputStreamReader(holder.getInputStream())).readLine();
            assertThat(first).isEqualTo("locked");

            assertThatThrownBy(() -> ProjectLock.acquire(migrationDir, "Mine")).isInstanceOf(UsageException.class)
                    .hasMessageContaining("another migration engine is running on this project")
                    .hasMessageContaining("pid=" + holder.pid()).hasMessageContaining("classes=HolderTest");
        } finally {
            holder.getOutputStream().close();
            holder.waitFor();
        }
        try (ProjectLock afterwards = ProjectLock.acquire(migrationDir, "Mine")) {
            assertThat(afterwards).isNotNull();
        }
    }
}
