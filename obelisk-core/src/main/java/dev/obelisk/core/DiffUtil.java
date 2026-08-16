package dev.obelisk.core;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;

import java.nio.file.Path;
import java.util.List;

public final class DiffUtil {

    private DiffUtil() {
    }

    public static String unifiedDiff(Path file, String original, String updated) {
        List<String> originalLines = original.lines().toList();
        List<String> updatedLines = updated.lines().toList();
        Patch<String> patch = DiffUtils.diff(originalLines, updatedLines);
        String name = file.toString();
        List<String> diffLines = UnifiedDiffUtils.generateUnifiedDiff(name, name, originalLines, patch, 3);
        return String.join("\n", diffLines);
    }
}
