package dev.obelisk.core.refactor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Outcome of running a refactor: which files changed, their unified diffs,
 * whether the changes were written to disk, any files renamed on disk (e.g.
 * a class rename that moves {@code Foo.java} to {@code Bar.java} -- empty
 * for refactors that never rename files), and any non-fatal warnings (e.g.
 * call sites obelisk couldn't resolve and therefore left untouched).
 */
public record RefactorResult(
        boolean applied,
        List<Path> changedFiles,
        Map<Path, String> diffs,
        Map<Path, Path> renamedFiles,
        List<String> warnings
) {
    public boolean isEmpty() {
        return changedFiles.isEmpty() && renamedFiles.isEmpty();
    }
}
