package com.project.migration.testsupport;

import com.project.migration.domain.exception.GitOperationException;
import com.project.migration.domain.model.vcs.CommitId;
import com.project.migration.domain.port.GitServicePort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A git stand-in that remembers the content of every {@code .java} file at each commit, so reverts and "what
 * changed since" behave like git on the real files of a temp project.
 */
public final class SnapshotGit implements GitServicePort {

    public record Commit(CommitId id, String message, Map<Path, String> files) {
    }

    private final Path root;
    public final List<Commit> commits = new ArrayList<>();
    public final List<String> pushed = new ArrayList<>();
    public final List<String> branchesCreated = new ArrayList<>();
    public final List<Collection<Path>> restores = new ArrayList<>();
    public String branch = "main";
    public Set<Path> extraUncommitted = new HashSet<>();

    public SnapshotGit(Path root) {
        this.root = root;
        commit("initial");
    }

    @Override
    public String currentBranch() {
        return branch;
    }

    @Override
    public void createOrCheckoutBranch(String branchName) {
        branchesCreated.add(branchName);
        branch = branchName;
    }

    @Override
    public CommitId head() {
        return commits.getLast().id();
    }

    @Override
    public Set<Path> uncommittedChanges() {
        Set<Path> changed = new HashSet<>(changedFilesSince(head()));
        changed.addAll(extraUncommitted);
        return changed;
    }

    @Override
    public CommitId commitAll(String message) {
        if (changedFilesSince(head()).isEmpty() && !message.equals("initial")) {
            return head();
        }
        return commit(message);
    }

    @Override
    public Set<Path> changedFilesSince(CommitId commit) {
        Map<Path, String> then = find(commit).files();
        Map<Path, String> now = read();
        Set<Path> changed = new HashSet<>();
        now.forEach((file, content) -> {
            if (!content.equals(then.get(file))) {
                changed.add(file);
            }
        });
        then.keySet().stream().filter(file -> !now.containsKey(file)).forEach(changed::add);
        return changed;
    }

    @Override
    public void restoreFiles(Collection<Path> paths, CommitId commit) {
        restores.add(List.copyOf(paths));
        Map<Path, String> then = find(commit).files();
        for (Path path : paths) {
            try {
                if (then.containsKey(path)) {
                    Files.writeString(root.resolve(path), then.get(path), StandardCharsets.UTF_8);
                } else {
                    Files.deleteIfExists(root.resolve(path));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public void push(String branchName) {
        pushed.add(branchName);
    }

    public List<String> messages() {
        return commits.stream().map(Commit::message).toList();
    }

    private CommitId commit(String message) {
        CommitId id = new CommitId(String.format("%07x", 0x1000000 + commits.size()));
        commits.add(new Commit(id, message, read()));
        return id;
    }

    private Commit find(CommitId id) {
        return commits.stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new GitOperationException("unknown commit " + id));
    }

    private Map<Path, String> read() {
        Map<Path, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                files.put(root.relativize(file), Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new LinkedHashMap<>(files);
    }
}
