package dev.obelisk.core.refactor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import dev.obelisk.core.DiffUtil;
import dev.obelisk.core.ProjectContext;
import dev.obelisk.core.RefactorException;

import javax.lang.model.SourceVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renames a method declared directly on a given class/interface/enum/record,
 * plus every call site, method reference, and static import obelisk can
 * resolve back to that exact declaration.
 *
 * V1 known limitations (fail loudly rather than guess):
 *  - the class must be uniquely identified by simple name across the project
 *    (top-level types take priority over nested types of the same name --
 *    see {@link #findClass})
 *  - overloaded methods with the target name must be disambiguated by the
 *    caller (not yet supported) -- refuses to guess which overload
 *  - overriding declarations in subclasses/interfaces are not renamed
 */
public final class RenameMethodRefactor {

    private RenameMethodRefactor() {
    }

    public static RefactorResult run(ProjectContext ctx, String className, String oldName, String newName,
                                      boolean apply) {
        validateIdentifier(newName);

        TypeDeclaration<?> targetClass = findClass(ctx, className);
        MethodDeclaration targetMethod = findMethod(targetClass, oldName);
        ResolvedMethodDeclaration resolvedTarget = resolveTarget(targetMethod, targetClass, oldName);
        String targetSignature = signatureOf(resolvedTarget);
        String ownerQualifiedName = resolvedTarget.declaringType().getQualifiedName();
        String paramsSuffix = targetSignature.substring(targetSignature.indexOf('('));

        rejectDuplicateSignature(targetClass, newName, paramsSuffix, oldName);

        List<String> warnings = new ArrayList<>();

        // Resolve every matching call site / method reference / static import
        // against the ORIGINAL (unmodified) AST first, and defer every mutation
        // (including method references) to a single batch applied afterward.
        // Renaming anything up front would shadow resolution for other matches
        // still referencing the old name (e.g. a self-call inside the same
        // class), so nothing is mutated until this whole pass completes.
        List<MethodCallExpr> callsToRename = new ArrayList<>();
        List<MethodReferenceExpr> refsToRename = new ArrayList<>();
        Set<ImportDeclaration> staticImportsToRename = new LinkedHashSet<>();

        for (CompilationUnit cu : ctx.unitsByFile().values()) {
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                if (!call.getNameAsString().equals(oldName)) {
                    continue;
                }
                ResolvedMethodDeclaration resolved = tryResolve(call, warnings, ctx, cu);
                if (resolved == null || !signatureOf(resolved).equals(targetSignature)) {
                    continue;
                }
                callsToRename.add(call);
                if (call.getScope().isEmpty()) {
                    // An unqualified call: Java's lookup order tries the
                    // enclosing class hierarchy (self-calls, inherited members)
                    // *before* single-static-imports. If the enclosing type
                    // already declares its own method named newName, renaming
                    // this call would silently get shadowed by that member
                    // instead of reaching the intended target -- refuse rather
                    // than produce code that quietly means something else.
                    // (Confirmed failure mode: renaming a statically-imported
                    // method into a name the importing class already declares.)
                    rejectShadowingCollision(call, newName, oldName);

                    // A bare, unqualified call resolving to a method not reached
                    // via "this" must have come in through a single-static-import
                    // of that exact member -- rename any matching static import
                    // in this file too, however it's qualified (including via a
                    // subclass name).
                    for (ImportDeclaration imp : cu.getImports()) {
                        if (imp.isStatic() && !imp.isAsterisk() && simpleNameOf(imp.getNameAsString()).equals(oldName)) {
                            staticImportsToRename.add(imp);
                        }
                    }
                }
            }

            for (MethodReferenceExpr ref : cu.findAll(MethodReferenceExpr.class)) {
                if (!ref.getIdentifier().equals(oldName)) {
                    continue;
                }
                try {
                    ResolvedMethodDeclaration resolved = ref.resolve();
                    if (signatureOf(resolved).equals(targetSignature)) {
                        refsToRename.add(ref);
                    }
                } catch (RuntimeException e) {
                    warnings.add("Could not resolve method reference '" + ref + "' in "
                            + fileOf(ctx, cu) + " (left unchanged): " + e.getMessage());
                }
            }
        }

        Map<Path, String> originalContents = new LinkedHashMap<>();
        originalContents.computeIfAbsent(fileOf(ctx, targetMethod.findCompilationUnit().orElseThrow()),
                RenameMethodRefactor::readOriginal);
        for (MethodCallExpr call : callsToRename) {
            originalContents.computeIfAbsent(fileOf(ctx, call.findCompilationUnit().orElseThrow()),
                    RenameMethodRefactor::readOriginal);
        }
        for (MethodReferenceExpr ref : refsToRename) {
            originalContents.computeIfAbsent(fileOf(ctx, ref.findCompilationUnit().orElseThrow()),
                    RenameMethodRefactor::readOriginal);
        }
        for (ImportDeclaration imp : staticImportsToRename) {
            originalContents.computeIfAbsent(fileOf(ctx, imp.findCompilationUnit().orElseThrow()),
                    RenameMethodRefactor::readOriginal);
        }

        // Apply every mutation together.
        targetMethod.getName().setIdentifier(newName);
        for (MethodCallExpr call : callsToRename) {
            call.getName().setIdentifier(newName);
        }
        for (MethodReferenceExpr ref : refsToRename) {
            ref.setIdentifier(newName);
        }
        for (ImportDeclaration imp : staticImportsToRename) {
            // Always normalize to the declaring type's FQN, even if the import
            // originally reached the method through a subclass name.
            imp.setName(qualifiedName(ownerQualifiedName + "." + newName));
        }

        List<Path> changedFiles = new ArrayList<>();
        Map<Path, String> diffs = new LinkedHashMap<>();
        Map<Path, String> updatedContents = new LinkedHashMap<>();
        for (Map.Entry<Path, String> entry : originalContents.entrySet()) {
            Path file = entry.getKey();
            String original = entry.getValue();
            CompilationUnit cu = ctx.unitsByFile().get(file);
            String updated = LexicalPreservingPrinter.print(cu);
            if (!updated.equals(original)) {
                changedFiles.add(file);
                diffs.put(file, DiffUtil.unifiedDiff(file, original, updated));
                updatedContents.put(file, updated);
            }
        }

        if (apply && !updatedContents.isEmpty()) {
            writeAll(updatedContents);
        }

        return new RefactorResult(apply, changedFiles, diffs, warnings);
    }

    private static void validateIdentifier(String name) {
        if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
            throw new RefactorException("'" + name + "' is not a valid Java method name");
        }
    }

    /**
     * Refuses to rename into a name that would collide with another method
     * already declared directly on the same type with the same parameter
     * list -- that would produce two methods with identical signatures.
     */
    private static void rejectDuplicateSignature(TypeDeclaration<?> targetClass, String newName,
                                                   String paramsSuffix, String oldName) {
        for (MethodDeclaration candidate : targetClass.getMethods()) {
            if (!candidate.getNameAsString().equals(newName)) {
                continue;
            }
            try {
                ResolvedMethodDeclaration resolved = candidate.resolve();
                String candidateSuffix = signatureOf(resolved).substring(signatureOf(resolved).indexOf('('));
                if (candidateSuffix.equals(paramsSuffix)) {
                    throw new RefactorException("Cannot rename '" + oldName + "' to '" + newName + "': '"
                            + targetClass.getNameAsString() + "' already declares " + newName + paramsSuffix);
                }
            } catch (RefactorException e) {
                throw e;
            } catch (RuntimeException e) {
                // Can't resolve the existing candidate -- not the rename target's
                // problem, ignore it for this check.
            }
        }
    }

    /**
     * Refuses to rename an unqualified call if its enclosing type already
     * declares a method named {@code newName} -- Java resolves an unqualified
     * call against the enclosing class hierarchy before a single-static-import
     * of the same simple name, so renaming into a name the enclosing class
     * already has would silently redirect the call to that unrelated member
     * (observed: renaming a statically-imported method into a name the
     * importing class already declares causes silent self-recursion instead of
     * calling the intended target).
     *
     * <p>This is a pure AST check, not a re-resolve of the mutated call: this
     * library's resolver was found to be unreliable when asked to re-resolve a
     * node that has already been mutated in place (even for definitely-correct
     * renames), so verification here deliberately avoids relying on it.
     */
    private static void rejectShadowingCollision(MethodCallExpr call, String newName, String oldName) {
        // findAncestor(Class) only accepts a raw Class token (TypeDeclaration is
        // itself generic), so the result comes back raw; cast once here rather
        // than scattering unchecked raw-type usages below.
        @SuppressWarnings("unchecked")
        java.util.Optional<TypeDeclaration<?>> ancestor =
                (java.util.Optional<TypeDeclaration<?>>) (java.util.Optional<?>) call.findAncestor(TypeDeclaration.class);
        ancestor.ifPresent(enclosing -> {
            boolean collides = enclosing.getMethods().stream()
                    .anyMatch(m -> m.getNameAsString().equals(newName));
            if (collides) {
                throw new RefactorException("Cannot rename '" + oldName + "' to '" + newName + "': '"
                        + enclosing.getNameAsString() + "' (containing an unqualified call to '" + oldName
                        + "' at " + call.getBegin().map(Object::toString).orElse("?")
                        + ") already declares its own '" + newName
                        + "' method, which would silently take priority over the intended call after the rename.");
            }
        });
    }

    /**
     * Finds the type (class, interface, enum, or record) declaring the method
     * to rename. Types can be nested, and a common inner-type name (e.g.
     * {@code Builder}, {@code Config}) elsewhere in the project shouldn't make
     * an unrelated top-level class named {@code className} ambiguous -- so
     * top-level matches take priority, and nested types are only considered
     * if there is no top-level match.
     */
    private static TypeDeclaration<?> findClass(ProjectContext ctx, String className) {
        List<TypeDeclaration<?>> allMatches = new ArrayList<>();
        for (CompilationUnit cu : ctx.unitsByFile().values()) {
            cu.findAll(TypeDeclaration.class).stream()
                    .filter(t -> t.getNameAsString().equals(className))
                    .forEach(allMatches::add);
        }
        if (allMatches.isEmpty()) {
            throw new RefactorException("No class/interface/enum/record named '" + className
                    + "' found under project source roots");
        }

        List<TypeDeclaration<?>> topLevelMatches = allMatches.stream()
                .filter(RenameMethodRefactor::isTopLevelType)
                .toList();
        List<TypeDeclaration<?>> candidates = topLevelMatches.isEmpty() ? allMatches : topLevelMatches;

        if (candidates.size() > 1) {
            String locations = candidates.stream()
                    .map(t -> t.getFullyQualifiedName().orElse(t.getNameAsString()))
                    .distinct()
                    .sorted()
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            throw new RefactorException("Class name '" + className + "' is ambiguous (" + candidates.size()
                    + " matches found: " + locations
                    + "). Fully-qualified class disambiguation is not yet supported.");
        }
        return candidates.get(0);
    }

    private static boolean isTopLevelType(TypeDeclaration<?> type) {
        return type.getParentNode().filter(p -> p instanceof CompilationUnit).isPresent();
    }

    private static MethodDeclaration findMethod(TypeDeclaration<?> targetClass, String methodName) {
        List<MethodDeclaration> matches = targetClass.getMethods().stream()
                .filter(m -> m.getNameAsString().equals(methodName))
                .toList();
        if (matches.isEmpty()) {
            throw new RefactorException(
                    "No method named '" + methodName + "' declared directly on '" + targetClass.getNameAsString() + "'");
        }
        if (matches.size() > 1) {
            throw new RefactorException("Method name '" + methodName + "' is overloaded ("
                    + matches.size() + " overloads) on '" + targetClass.getNameAsString()
                    + "'. Disambiguating by parameter list is not yet supported -- refusing to guess.");
        }
        return matches.get(0);
    }

    private static ResolvedMethodDeclaration resolveTarget(MethodDeclaration method, TypeDeclaration<?> owner,
                                                             String originalName) {
        try {
            return method.resolve();
        } catch (RuntimeException e) {
            throw new RefactorException("Could not resolve target method '" + originalName + "' on '"
                    + owner.getNameAsString() + "': " + e.getMessage(), e);
        }
    }

    private static String signatureOf(ResolvedMethodDeclaration decl) {
        return decl.getQualifiedSignature();
    }

    private static String simpleNameOf(String dottedName) {
        int lastDot = dottedName.lastIndexOf('.');
        return lastDot < 0 ? dottedName : dottedName.substring(lastDot + 1);
    }

    /** Builds a proper qualifier-chain {@link Name} rather than one dotted identifier. */
    private static Name qualifiedName(String dotted) {
        String[] parts = dotted.split("\\.");
        Name name = new Name(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            name = new Name(name, parts[i]);
        }
        return name;
    }

    private static ResolvedMethodDeclaration tryResolve(MethodCallExpr call, List<String> warnings,
                                                          ProjectContext ctx, CompilationUnit cu) {
        try {
            return call.resolve();
        } catch (RuntimeException e) {
            warnings.add("Could not resolve call '" + call + "' in " + fileOf(ctx, cu)
                    + " (left unchanged): " + e.getMessage());
            return null;
        }
    }

    private static Path fileOf(ProjectContext ctx, CompilationUnit cu) {
        return ctx.unitsByFile().entrySet().stream()
                .filter(e -> e.getValue() == cu)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new RefactorException("Internal error: compilation unit has no known source file"));
    }

    private static String readOriginal(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes every changed file's content to a sibling temp file first, and
     * only once ALL of them have been written successfully, moves each temp
     * file over its real target. This keeps a mid-write failure (disk full,
     * permission denied on file N of M) from ever leaving a source file with
     * truncated or partial content; the only remaining risk window is the
     * near-instant rename/move itself, not the content write.
     */
    private static void writeAll(Map<Path, String> updatedContents) {
        Map<Path, Path> tempFiles = new LinkedHashMap<>();
        try {
            for (Map.Entry<Path, String> entry : updatedContents.entrySet()) {
                Path target = entry.getKey();
                Path temp = target.resolveSibling(target.getFileName() + ".obelisk-tmp");
                Files.writeString(temp, entry.getValue());
                tempFiles.put(target, temp);
            }
            for (Map.Entry<Path, Path> entry : tempFiles.entrySet()) {
                Files.move(entry.getValue(), entry.getKey(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            for (Path temp : tempFiles.values()) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
            throw new RefactorException("Failed to write changes: " + e.getMessage(), e);
        }
    }
}
