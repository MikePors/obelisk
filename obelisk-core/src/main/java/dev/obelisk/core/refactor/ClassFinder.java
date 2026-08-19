package dev.obelisk.core.refactor;

import dev.obelisk.guard.Check;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import dev.obelisk.core.ProjectContext;
import dev.obelisk.core.RefactorException;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the type (class, interface, enum, record, or annotation) matching a
 * simple name across a project's source roots. Shared by every refactor that
 * takes a {@code --class} argument (see {@link RenameMethodRefactor},
 * {@link RenameClassRefactor}).
 */
final class ClassFinder {

    private ClassFinder() {
    }

    /**
     * Types can be nested, and a common inner-type name (e.g. {@code Builder},
     * {@code Config}) elsewhere in the project shouldn't make an unrelated
     * top-level class named {@code className} ambiguous -- so top-level
     * matches take priority, and nested types are only considered if there is
     * no top-level match.
     */
    static TypeDeclaration<?> findClass(ProjectContext ctx, String className) {
        List<TypeDeclaration<?>> allMatches = new ArrayList<>();
        for (CompilationUnit cu : ctx.unitsByFile().values()) {
            cu.findAll(TypeDeclaration.class).stream()
                    .filter(t -> t.getNameAsString().equals(className))
                    .forEach(allMatches::add);
        }
        if (allMatches.isEmpty()) {
            throw new RefactorException(Check.CLASS_NOT_FOUND, "No class/interface/enum/record named '" + className
                    + "' found under project source roots");
        }

        List<TypeDeclaration<?>> topLevelMatches = allMatches.stream()
                .filter(ClassFinder::isTopLevelType)
                .toList();
        List<TypeDeclaration<?>> candidates = topLevelMatches.isEmpty() ? allMatches : topLevelMatches;

        if (candidates.size() > 1) {
            String locations = candidates.stream()
                    .map(t -> t.getFullyQualifiedName().orElse(t.getNameAsString()))
                    .distinct()
                    .sorted()
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            throw new RefactorException(Check.CLASS_NAME_AMBIGUOUS, "Class name '" + className + "' is ambiguous (" + candidates.size()
                    + " matches found: " + locations
                    + "). Fully-qualified class disambiguation is not yet supported.");
        }
        return candidates.get(0);
    }

    private static boolean isTopLevelType(TypeDeclaration<?> type) {
        return type.getParentNode().filter(p -> p instanceof CompilationUnit).isPresent();
    }
}
