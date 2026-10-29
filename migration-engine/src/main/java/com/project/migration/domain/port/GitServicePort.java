package com.project.migration.domain.port;

import com.project.migration.domain.exception.GitOperationException;
import com.project.migration.domain.model.vcs.CommitId;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

/**
 * Git operations on the target project's repository. Paths are relative to the project root. All methods throw
 * {@link GitOperationException} on failure.
 */
public interface GitServicePort {

    String currentBranch();

    /** Creates {@code branchName} from the current HEAD and checks it out; checks it out if it already exists. */
    void createOrCheckoutBranch(String branchName);

    CommitId head();

    /**
     * Stages all changes (respecting {@code .gitignore}) and commits.
     *
     * @return the new commit, or the current HEAD when there was nothing to commit
     */
    CommitId commitAll(String message);

    /** Files changed since {@code commit}, including uncommitted working-tree changes. */
    Set<Path> changedFilesSince(CommitId commit);

    /** {@code git restore --source=<commit> --staged --worktree -- <paths>}: rolls back only these files. */
    void restoreFiles(Collection<Path> paths, CommitId commit);

    void push(String branchName);
}
