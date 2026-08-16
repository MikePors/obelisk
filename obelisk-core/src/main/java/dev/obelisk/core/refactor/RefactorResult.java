package dev.obelisk.core.refactor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Outcome of running a refactor: which files changed, their unified diffs,
 * whether the changes were written to disk, and any non-fatal warnings
 * (e.g. call sites obelisk couldn't resolve and therefore left untouched).
 */
public record RefactorResult(
        boolean applied,
        List<Path> changedFiles,
        Map<Path, String> diffs,
        List<String> warnings
) {
    public boolean isEmpty() {
        return changedFiles.isEmpty();
    }
}
