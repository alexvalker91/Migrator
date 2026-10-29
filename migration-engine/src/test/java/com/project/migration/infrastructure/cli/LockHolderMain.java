package com.project.migration.infrastructure.cli;

import java.nio.file.Path;

/**
 * Child process of {@link ProjectLockTest}: takes the lock, announces it and waits until its input is closed.
 */
public final class LockHolderMain {

    private LockHolderMain() {
    }

    public static void main(String[] args) throws Exception {
        try (ProjectLock ignored = ProjectLock.acquire(Path.of(args[0]), "HolderTest")) {
            System.out.println("locked");
            System.out.flush();
            System.in.read();
        }
    }
}
