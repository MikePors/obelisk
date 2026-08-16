package dev.obelisk.core.refactor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import dev.obelisk.core.DiffUtil;
import dev.obelisk.core.ProjectContext;
import dev.obelisk.core.RefactorException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        TypeDeclaration<?> targetClass = findClass(ctx, className);
        MethodDeclaration targetMethod = findMethod(targetClass, oldName);
        ResolvedMethodDeclaration resolvedTarget = resolveTarget(targetMethod, targetClass, oldName);
        String targetSignature = signatureOf(resolvedTarget);
        String ownerQualifiedName = resolvedTarget.declaringType().getQualifiedName();

        Map<Path, String> originalContents = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        // Resolve every matching call site / method reference / static import
        // against the ORIGINAL (unmodified) AST first. Renaming the declaration
        // up front would shadow resolution for other call sites still
        // referencing the old name (e.g. a self-call inside the same class), so
        // all renames are collected here and applied together afterward.
        List<SimpleName> namesToRename = new ArrayList<>();
        namesToRename.add(targetMethod.getName());
        List<ImportDeclaration> staticImportsToRename = new ArrayList<>();

        for (Map.Entry<Path, CompilationUnit> entry : ctx.unitsByFile().entrySet()) {
            Path file = entry.getKey();
            CompilationUnit cu = entry.getValue();

            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                if (!call.getNameAsString().equals(oldName)) {
                    continue;
                }
                ResolvedMethodDeclaration resolved = tryResolve(call, warnings, file);
                if (resolved != null && signatureOf(resolved).equals(targetSignature)) {
                    namesToRename.add(call.getName());
                }
            }

            for (MethodReferenceExpr ref : cu.findAll(MethodReferenceExpr.class)) {
                if (!ref.getIdentifier().equals(oldName)) {
                    continue;
                }
                try {
                    ResolvedMethodDeclaration resolved = ref.resolve();
                    if (signatureOf(resolved).equals(targetSignature)) {
                        originalContents.computeIfAbsent(file, RenameMethodRefactor::readOriginal);
                        ref.setIdentifier(newName);
                    }
                } catch (RuntimeException e) {
                    warnings.add("Could not resolve method reference '" + ref + "' in " + file
                            + " (left unchanged): " + e.getMessage());
                }
            }

            for (ImportDeclaration imp : cu.getImports()) {
                if (!imp.isStatic() || imp.isAsterisk()) {
                    continue;
                }
                String qualifiedName = imp.getNameAsString();
                int lastDot = qualifiedName.lastIndexOf('.');
                if (lastDot < 0) {
                    continue;
                }
                String qualifier = qualifiedName.substring(0, lastDot);
                String simple = qualifiedName.substring(lastDot + 1);
                if (simple.equals(oldName) && qualifier.equals(ownerQualifiedName)) {
                    staticImportsToRename.add(imp);
                }
            }
        }

        for (SimpleName name : namesToRename) {
            Path file = fileOf(ctx, name.findCompilationUnit().orElseThrow());
            originalContents.computeIfAbsent(file, RenameMethodRefactor::readOriginal);
        }
        for (ImportDeclaration imp : staticImportsToRename) {
            Path file = fileOf(ctx, imp.findCompilationUnit().orElseThrow());
            originalContents.computeIfAbsent(file, RenameMethodRefactor::readOriginal);
        }

        for (SimpleName name : namesToRename) {
            name.setIdentifier(newName);
        }
        for (ImportDeclaration imp : staticImportsToRename) {
            imp.setName(new Name(ownerQualifiedName + "." + newName));
        }

        List<Path> changedFiles = new ArrayList<>();
        Map<Path, String> diffs = new LinkedHashMap<>();
        for (Map.Entry<Path, String> entry : originalContents.entrySet()) {
            Path file = entry.getKey();
            String original = entry.getValue();
            CompilationUnit cu = ctx.unitsByFile().get(file);
            String updated = LexicalPreservingPrinter.print(cu);
            if (!updated.equals(original)) {
                changedFiles.add(file);
                diffs.put(file, DiffUtil.unifiedDiff(file, original, updated));
                if (apply) {
                    writeFile(file, updated);
                }
            }
        }

        return new RefactorResult(apply, changedFiles, diffs, warnings);
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

    private static ResolvedMethodDeclaration tryResolve(MethodCallExpr call, List<String> warnings, Path file) {
        try {
            return call.resolve();
        } catch (RuntimeException e) {
            warnings.add("Could not resolve call '" + call + "' in " + file + " (left unchanged): " + e.getMessage());
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

    private static void writeFile(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
