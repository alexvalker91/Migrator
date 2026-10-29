package com.project.migration.infrastructure.git;

import com.project.migration.domain.exception.GitOperationException;
import com.project.migration.domain.model.Checks;
import com.project.migration.domain.model.vcs.BranchNames;
import com.project.migration.domain.model.vcs.CommitId;
import com.project.migration.domain.port.GitServicePort;
import com.project.migration.infrastructure.process.CommandExecutor;
import com.project.migration.infrastructure.process.CommandExecutor.CommandResult;
import com.project.migration.infrastructure.process.ProcessCommandExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link GitServicePort} on the system {@code git}: the user's configuration, SSH agent, commit signing and hooks
 * all apply (hooks are never skipped). The adapter is built so the engine cannot do damage: it commits, restores
 * and pushes only on {@code migrate/ios-*} branches, restores single files and never uses force, reset, clean or
 * branch deletion.
 */
public final class GitServiceAdapter implements GitServicePort {

    private static final Duration LOCAL_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration REMOTE_TIMEOUT = Duration.ofMinutes(5);
    private static final int OUTPUT_TAIL = 2_000;

    private final Path projectRoot;
    private final CommandExecutor executor;

    public GitServiceAdapter(Path projectRoot) {
        this(projectRoot, new ProcessCommandExecutor());
    }

    public GitServiceAdapter(Path projectRoot, CommandExecutor executor) {
        this.projectRoot = Checks.notNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        this.executor = Checks.notNull(executor, "executor");
    }

    @Override
    public String currentBranch() {
        return run(LOCAL_TIMEOUT, "rev-parse", "--abbrev-ref", "HEAD").output().strip();
    }

    @Override
    public void createOrCheckoutBranch(String branchName) {
        requireMigrationBranch(branchName);
        if (exists("refs/heads/" + branchName)) {
            run(LOCAL_TIMEOUT, "checkout", branchName);
        } else if (exists("refs/remotes/origin/" + branchName)) {
            run(LOCAL_TIMEOUT, "checkout", "-b", branchName, "--track", "origin/" + branchName);
        } else {
            run(LOCAL_TIMEOUT, "checkout", "-b", branchName);
        }
    }

    @Override
    public CommitId head() {
        return new CommitId(run(LOCAL_TIMEOUT, "rev-parse", "HEAD").output().strip());
    }

    @Override
    public Set<Path> uncommittedChanges() {
        String status = run(LOCAL_TIMEOUT, "status", "--porcelain=v1", "-z", "--untracked-files=all").output();
        Set<Path> changed = new LinkedHashSet<>();
        String[] entries = status.split("\0");
        for (int i = 0; i < entries.length; i++) {
            String entry = entries[i];
            if (entry.length() < 4) {
                continue;
            }
            changed.add(Path.of(entry.substring(3)));
            if (entry.charAt(0) == 'R' || entry.charAt(0) == 'C' || entry.charAt(1) == 'R' || entry.charAt(1) == 'C') {
                i++; // with -z a rename is followed by a separate entry holding the old path
            }
        }
        return changed;
    }

    @Override
    public CommitId commitAll(String message) {
        Checks.notBlank(message, "message");
        requireCurrentMigrationBranch();
        run(LOCAL_TIMEOUT, "add", "-A");
        CommandResult staged = executor.run(git("diff", "--cached", "--quiet"), projectRoot, LOCAL_TIMEOUT);
        if (staged.exitCode() == 0) {
            return head();
        }
        if (staged.exitCode() != 1) {
            throw failure(List.of("diff", "--cached", "--quiet"), staged);
        }
        run(LOCAL_TIMEOUT, "commit", "-q", "-m", message);
        return head();
    }

    @Override
    public Set<Path> changedFilesSince(CommitId commit) {
        Set<Path> changed = new LinkedHashSet<>();
        for (String file : run(LOCAL_TIMEOUT, "diff", "--name-only", "-z", commit.value(), "--").output().split("\0")) {
            if (!file.isBlank()) {
                changed.add(Path.of(file));
            }
        }
        for (String file : run(LOCAL_TIMEOUT, "ls-files", "--others", "--exclude-standard", "-z").output().split("\0")) {
            if (!file.isBlank()) {
                changed.add(Path.of(file));
            }
        }
        return changed;
    }

    @Override
    public void restoreFiles(Collection<Path> paths, CommitId commit) {
        requireCurrentMigrationBranch();
        List<String> tracked = new ArrayList<>();
        for (Path path : paths) {
            String relative = path.toString().replace('\\', '/');
            if (executor.run(git("cat-file", "-e", commit.value() + ":" + relative), projectRoot, LOCAL_TIMEOUT).exitCode() == 0) {
                tracked.add(relative);
            } else {
                dropNewFile(relative);
            }
        }
        if (!tracked.isEmpty()) {
            List<String> args = new ArrayList<>(List.of("restore", "--source=" + commit.value(), "--staged", "--worktree", "--"));
            args.addAll(tracked);
            run(LOCAL_TIMEOUT, args.toArray(String[]::new));
        }
    }

    @Override
    public void push(String branchName) {
        requireMigrationBranch(branchName);
        requireCurrentMigrationBranch();
        run(REMOTE_TIMEOUT, "push", "-u", "origin", branchName);
    }

    /** A file the commit does not have was created after it: unstage it and remove it. */
    private void dropNewFile(String relative) {
        executor.run(git("rm", "--cached", "-q", "--ignore-unmatch", "--", relative), projectRoot, LOCAL_TIMEOUT);
        try {
            Files.deleteIfExists(projectRoot.resolve(relative));
        } catch (IOException e) {
            throw new GitOperationException("Cannot remove " + relative + ": " + e.getMessage(), e);
        }
    }

    private boolean exists(String ref) {
        return executor.run(git("show-ref", "--verify", "--quiet", ref), projectRoot, LOCAL_TIMEOUT).exitCode() == 0;
    }

    private void requireCurrentMigrationBranch() {
        String branch = currentBranch();
        if (!branch.startsWith(BranchNames.PREFIX)) {
            throw new GitOperationException("Refusing to change history on branch '" + branch + "': the engine only works on "
                    + BranchNames.PREFIX + "* branches");
        }
    }

    private static void requireMigrationBranch(String branchName) {
        if (branchName == null || !branchName.startsWith(BranchNames.PREFIX)) {
            throw new GitOperationException("Refusing to use branch '" + branchName + "': the engine only works on "
                    + BranchNames.PREFIX + "* branches");
        }
    }

    private CommandResult run(Duration timeout, String... args) {
        CommandResult result = executor.run(git(args), projectRoot, timeout);
        if (!result.succeeded()) {
            throw failure(List.of(args), result);
        }
        return result;
    }

    private static GitOperationException failure(List<String> args, CommandResult result) {
        String output = result.output().strip();
        String tail = output.length() <= OUTPUT_TAIL ? output : "..." + output.substring(output.length() - OUTPUT_TAIL);
        return new GitOperationException("git " + String.join(" ", args) + " failed"
                + (result.timedOut() ? " (timed out)" : " (exit " + result.exitCode() + ")") + ": " + tail);
    }

    private static List<String> git(String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        return command;
    }
}
