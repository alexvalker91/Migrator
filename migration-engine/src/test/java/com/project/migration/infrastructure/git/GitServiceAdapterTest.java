package com.project.migration.infrastructure.git;

import com.project.migration.domain.exception.GitOperationException;
import com.project.migration.domain.model.vcs.CommitId;
import com.project.migration.infrastructure.process.CommandExecutor;
import com.project.migration.infrastructure.process.ProcessCommandExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Against real git repositories in temp directories.
 */
class GitServiceAdapterTest {

    @TempDir
    Path work;

    private final CommandExecutor executor = new ProcessCommandExecutor();
    private Path repo;
    private GitServiceAdapter git;

    @BeforeEach
    void setUp() throws IOException {
        repo = Files.createDirectory(work.resolve("repo"));
        sh(repo, "git", "init", "-q", "-b", "main");
        sh(repo, "git", "config", "user.email", "engine@example.com");
        sh(repo, "git", "config", "user.name", "Engine Test");
        sh(repo, "git", "config", "commit.gpgsign", "false");
        write("a.txt", "one\n");
        write("b.txt", "two\n");
        write(".gitignore", "ignored/\n");
        sh(repo, "git", "add", "-A");
        sh(repo, "git", "commit", "-q", "-m", "initial");
        git = new GitServiceAdapter(repo);
    }

    @Test
    void createsTheMigrationBranchFromHeadAndChecksItOutAgainLater() {
        CommitId start = git.head();

        git.createOrCheckoutBranch("migrate/ios-CardsTest");

        assertThat(git.currentBranch()).isEqualTo("migrate/ios-CardsTest");
        assertThat(git.head()).isEqualTo(start);
        sh(repo, "git", "checkout", "-q", "main");
        git.createOrCheckoutBranch("migrate/ios-CardsTest");
        assertThat(git.currentBranch()).isEqualTo("migrate/ios-CardsTest");
    }

    @Test
    void checksOutABranchThatOnlyExistsOnTheRemoteAndTracksIt() throws IOException {
        Path remote = bareRemote();
        sh(repo, "git", "checkout", "-q", "-b", "migrate/ios-LoginTest");
        write("a.txt", "remote work\n");
        sh(repo, "git", "commit", "-q", "-am", "work");
        sh(repo, "git", "push", "-q", "-u", "origin", "migrate/ios-LoginTest");
        sh(repo, "git", "checkout", "-q", "main");
        sh(repo, "git", "branch", "-q", "-D", "migrate/ios-LoginTest");

        git.createOrCheckoutBranch("migrate/ios-LoginTest");

        assertThat(git.currentBranch()).isEqualTo("migrate/ios-LoginTest");
        assertThat(read("a.txt")).isEqualTo("remote work\n");
        assertThat(remote).exists();
    }

    @Test
    void refusesBranchesOutsideTheMigrationNamespaceAndChangesOnThem() throws IOException {
        assertThatThrownBy(() -> git.createOrCheckoutBranch("feature/x")).isInstanceOf(GitOperationException.class)
                .hasMessageContaining("migrate/ios-");
        write("a.txt", "changed\n");

        assertThatThrownBy(() -> git.commitAll("on main")).isInstanceOf(GitOperationException.class)
                .hasMessageContaining("Refusing to change history on branch 'main'");
        assertThatThrownBy(() -> git.restoreFiles(List.of(Path.of("a.txt")), git.head())).isInstanceOf(GitOperationException.class);
        assertThatThrownBy(() -> git.push("main")).isInstanceOf(GitOperationException.class);
        assertThat(read("a.txt")).isEqualTo("changed\n");
    }

    @Test
    void commitAllCommitsEverythingExceptIgnoredFilesAndReturnsHeadWhenNothingChanged() throws IOException {
        git.createOrCheckoutBranch("migrate/ios-CardsTest");
        CommitId before = git.head();
        assertThat(git.commitAll("nothing to do")).isEqualTo(before);

        write("a.txt", "changed\n");
        write("new/c.txt", "new\n");
        write("ignored/d.txt", "ignored\n");
        CommitId after = git.commitAll("codegen: CardsTest");

        assertThat(after).isNotEqualTo(before);
        assertThat(sh(repo, "git", "log", "-1", "--format=%s")).isEqualTo("codegen: CardsTest");
        assertThat(sh(repo, "git", "show", "--name-only", "--format=", "HEAD")).contains("a.txt", "new/c.txt")
                .doesNotContain("ignored");
        assertThat(git.uncommittedChanges()).isEmpty();
    }

    @Test
    void uncommittedChangesListsModifiedUntrackedAndRenamedFilesButNotIgnoredOnes() throws IOException {
        write("a.txt", "changed\n");
        write("dir/new.txt", "new\n");
        write("ignored/x.txt", "x\n");
        sh(repo, "git", "mv", "b.txt", "renamed.txt");

        assertThat(git.uncommittedChanges()).containsExactlyInAnyOrder(Path.of("a.txt"), Path.of("dir/new.txt"),
                Path.of("renamed.txt"));
    }

    @Test
    void changedFilesSinceIncludesCommittedAndUncommittedAndUntrackedChanges() throws IOException {
        git.createOrCheckoutBranch("migrate/ios-CardsTest");
        CommitId base = git.head();
        assertThat(git.changedFilesSince(base)).isEmpty();
        write("a.txt", "committed\n");
        git.commitAll("step");
        write("b.txt", "uncommitted\n");
        write("c.txt", "untracked\n");

        assertThat(git.changedFilesSince(base)).containsExactlyInAnyOrder(Path.of("a.txt"), Path.of("b.txt"), Path.of("c.txt"));
        assertThat(git.changedFilesSince(git.head())).containsExactlyInAnyOrder(Path.of("b.txt"), Path.of("c.txt"));
    }

    @Test
    void restoreFilesRollsBackOnlyTheGivenFilesAndRemovesFilesTheCommitDidNotHave() throws IOException {
        git.createOrCheckoutBranch("migrate/ios-CardsTest");
        CommitId base = git.head();
        write("a.txt", "bad edit\n");
        write("b.txt", "keep my edit\n");
        write("generated/new.txt", "new\n");
        sh(repo, "git", "add", "a.txt");

        git.restoreFiles(List.of(Path.of("a.txt"), Path.of("generated/new.txt")), base);

        assertThat(read("a.txt")).isEqualTo("one\n");
        assertThat(read("b.txt")).isEqualTo("keep my edit\n");
        assertThat(repo.resolve("generated/new.txt")).doesNotExist();
        assertThat(git.uncommittedChanges()).containsExactly(Path.of("b.txt"));
    }

    @Test
    void pushPublishesTheMigrationBranchAndANonFastForwardPushIsRejectedNotForced() throws IOException {
        Path remote = bareRemote();
        git.createOrCheckoutBranch("migrate/ios-CardsTest");
        write("a.txt", "work\n");
        git.commitAll("work");

        git.push("migrate/ios-CardsTest");

        assertThat(sh(remote, "git", "rev-parse", "migrate/ios-CardsTest")).isEqualTo(git.head().value());

        Path other = clone(remote);
        sh(other, "git", "checkout", "-q", "migrate/ios-CardsTest");
        Files.writeString(other.resolve("a.txt"), "someone else\n");
        sh(other, "git", "commit", "-q", "-am", "other");
        sh(other, "git", "push", "-q", "origin", "migrate/ios-CardsTest");
        write("a.txt", "diverged\n");
        git.commitAll("diverged");

        assertThatThrownBy(() -> git.push("migrate/ios-CardsTest")).isInstanceOf(GitOperationException.class)
                .hasMessageContaining("git push -u origin migrate/ios-CardsTest failed");
        assertThat(sh(remote, "git", "log", "-1", "--format=%s", "migrate/ios-CardsTest")).isEqualTo("other");
    }

    @Test
    void failuresCarryTheCommandAndTheGitOutput() {
        CommandExecutor failing = (command, dir, timeout) -> new CommandExecutor.CommandResult(128, "fatal: not a git repository", false,
                Duration.ofMillis(5));

        assertThatThrownBy(() -> new GitServiceAdapter(repo, failing).head()).isInstanceOf(GitOperationException.class)
                .hasMessageContaining("git rev-parse HEAD failed (exit 128)").hasMessageContaining("not a git repository");
        CommandExecutor timedOut = (command, dir, timeout) -> new CommandExecutor.CommandResult(-1, "", true, timeout);
        assertThatThrownBy(() -> new GitServiceAdapter(repo, timedOut).currentBranch()).hasMessageContaining("timed out");
    }

    private Path bareRemote() {
        Path remote = work.resolve("remote.git");
        sh(work, "git", "init", "-q", "--bare", "-b", "main", remote.toString());
        sh(repo, "git", "remote", "add", "origin", remote.toString());
        return remote;
    }

    private Path clone(Path remote) {
        Path clone = work.resolve("other");
        sh(work, "git", "clone", "-q", remote.toString(), clone.toString());
        sh(clone, "git", "config", "user.email", "other@example.com");
        sh(clone, "git", "config", "user.name", "Other");
        sh(clone, "git", "config", "commit.gpgsign", "false");
        return clone;
    }

    private void write(String file, String content) throws IOException {
        Path target = repo.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private String read(String file) throws IOException {
        return Files.readString(repo.resolve(file));
    }

    private String sh(Path dir, String... command) {
        CommandExecutor.CommandResult result = executor.run(List.of(command), dir, Duration.ofSeconds(30));
        assertThat(result.succeeded()).as(String.join(" ", command) + " -> " + result.output()).isTrue();
        return result.output().strip();
    }
}
