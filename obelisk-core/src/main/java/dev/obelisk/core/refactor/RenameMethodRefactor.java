package dev.obelisk.core.refactor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
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
import java.util.Optional;
import java.util.Set;

/**
 * Renames a method declared directly on a given class/interface/enum/record,
 * plus every call site, method reference, and static import obelisk can
 * resolve back to that exact declaration.
 *
 * Also renames every overriding declaration found elsewhere in the project
 * (subclasses, implementing classes, anonymous classes, enum constant
 * bodies), plus their own call sites/references, so the whole override
 * family stays in sync -- see {@link #findOverrides}.
 *
 * V1 known limitations (fail loudly rather than guess):
 *  - the class must be uniquely identified by simple name across the project
 *    (top-level types take priority over nested types of the same name --
 *    see {@link #findClass})
 *  - overloaded methods with the target name must be disambiguated by the
 *    caller (not yet supported) -- refuses to guess which overload
 *  - override propagation is downward only: {@code --class} must name the
 *    root declaration. If the specified method itself overrides something
 *    further up the hierarchy, obelisk refuses and names the root to target
 *    instead, rather than silently renaming only part of the family.
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

        rejectNonRootTarget(resolvedTarget, oldName, targetClass);

        List<String> warnings = new ArrayList<>();

        // Find every declaration elsewhere in the project that overrides this
        // one, so the whole family gets renamed together instead of leaving
        // subclasses out of sync with the interface/superclass they implement.
        List<MethodDeclaration> overrides = findOverrides(ctx, ownerQualifiedName, oldName, targetMethod,
                resolvedTarget, warnings);
        List<MethodDeclaration> familyDeclarations = new ArrayList<>();
        familyDeclarations.add(targetMethod);
        familyDeclarations.addAll(overrides);

        Set<String> familySignatures = new LinkedHashSet<>();
        familySignatures.add(targetSignature);
        for (MethodDeclaration override : overrides) {
            familySignatures.add(signatureOf(override.resolve()));
        }

        String paramsSuffix = targetSignature.substring(targetSignature.indexOf('('));
        for (MethodDeclaration member : familyDeclarations) {
            member.findAncestor(TypeDeclaration.class).ifPresent(owner ->
                    rejectDuplicateSignature(castTypeDeclaration(owner), newName, paramsSuffix, oldName));
        }

        // Resolve every matching call site / method reference against the
        // ORIGINAL (unmodified) AST first, and defer every mutation (including
        // method references) to a single batch applied afterward. Renaming
        // anything up front would shadow resolution for other matches still
        // referencing the old name (e.g. a self-call inside the same class),
        // so nothing is mutated until this whole pass completes.
        List<MethodCallExpr> callsToRename = new ArrayList<>();
        List<MethodReferenceExpr> refsToRename = new ArrayList<>();

        for (CompilationUnit cu : ctx.unitsByFile().values()) {
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                if (!call.getNameAsString().equals(oldName)) {
                    continue;
                }
                ResolvedMethodDeclaration resolved = tryResolve(call, warnings, ctx, cu);
                if (resolved == null || !familySignatures.contains(signatureOf(resolved))) {
                    continue;
                }
                callsToRename.add(call);
                if (call.getScope().isEmpty()) {
                    // An unqualified call: Java's lookup order tries the
                    // enclosing class hierarchy (self-calls, inherited members)
                    // *before* single-static-imports. If the enclosing type
                    // already declares a method named newName with the SAME
                    // parameter list this call needs, renaming would silently
                    // get shadowed by that member instead of reaching the
                    // intended target -- refuse rather than produce code that
                    // quietly means something else. (A newName method with a
                    // DIFFERENT parameter list is just a legal new overload and
                    // is not refused.)
                    rejectShadowingCollision(call, resolved, newName, oldName);
                }
            }

            for (MethodReferenceExpr ref : cu.findAll(MethodReferenceExpr.class)) {
                if (!ref.getIdentifier().equals(oldName)) {
                    continue;
                }
                try {
                    ResolvedMethodDeclaration resolved = ref.resolve();
                    if (familySignatures.contains(signatureOf(resolved))) {
                        refsToRename.add(ref);
                    }
                } catch (RuntimeException e) {
                    warnings.add("Could not resolve method reference '" + ref + "' in "
                            + fileOf(ctx, cu) + " (left unchanged): " + e.getMessage());
                }
            }
        }

        // Static imports are handled as their own pass, independent of which
        // calls matched: a candidate is only renamed if its qualifier actually
        // resolves to the root type or one of its subtypes (so an unrelated
        // static import that merely shares the old simple name -- e.g. a
        // different class's same-named method -- is never touched).
        Set<ImportDeclaration> staticImportsToRename = findStaticImportsToRename(ctx, ownerQualifiedName, oldName);

        Map<Path, String> originalContents = new LinkedHashMap<>();
        for (MethodDeclaration member : familyDeclarations) {
            originalContents.computeIfAbsent(fileOf(ctx, member.findCompilationUnit().orElseThrow()),
                    RenameMethodRefactor::readOriginal);
        }
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
        for (MethodDeclaration member : familyDeclarations) {
            member.getName().setIdentifier(newName);
        }
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
    private static void rejectDuplicateSignature(TypeDeclaration<?> owner, String newName,
                                                   String paramsSuffix, String oldName) {
        for (MethodDeclaration candidate : owner.getMethods()) {
            if (!candidate.getNameAsString().equals(newName)) {
                continue;
            }
            try {
                ResolvedMethodDeclaration resolved = candidate.resolve();
                String candidateSuffix = signatureOf(resolved).substring(signatureOf(resolved).indexOf('('));
                if (candidateSuffix.equals(paramsSuffix)) {
                    throw new RefactorException("Cannot rename '" + oldName + "' to '" + newName + "': '"
                            + owner.getNameAsString() + "' already declares " + newName + paramsSuffix);
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
     * declares a method named {@code newName} with the SAME parameter list
     * this call needs -- Java resolves an unqualified call against the
     * enclosing class hierarchy before a single-static-import of the same
     * simple name, so renaming into a name/signature the enclosing class
     * already has would silently redirect the call to that unrelated member
     * (observed: renaming a statically-imported method into a name the
     * importing class already declares causes silent self-recursion instead
     * of calling the intended target). A colliding NAME with a DIFFERENT
     * parameter list is just a legal new overload and is intentionally not
     * refused -- Java resolves it correctly by arity/type, no ambiguity.
     *
     * <p>This is a pure AST/resolved-parameter check, not a re-resolve of the
     * mutated call: this library's resolver was found to be unreliable when
     * asked to re-resolve a node that has already been mutated in place (even
     * for definitely-correct renames), so verification here deliberately
     * avoids relying on it.
     */
    private static void rejectShadowingCollision(MethodCallExpr call, ResolvedMethodDeclaration resolvedCall,
                                                   String newName, String oldName) {
        List<ResolvedType> callParams = resolvedCall.formalParameterTypes();
        Optional<TypeDeclaration<?>> ancestor = call.findAncestor(TypeDeclaration.class)
                .map(RenameMethodRefactor::castTypeDeclaration);
        ancestor.ifPresent(enclosing -> {
            for (MethodDeclaration candidate : enclosing.getMethods()) {
                if (!candidate.getNameAsString().equals(newName)) {
                    continue;
                }
                try {
                    if (paramsMatch(candidate.resolve().formalParameterTypes(), callParams)) {
                        throw new RefactorException("Cannot rename '" + oldName + "' to '" + newName + "': '"
                                + enclosing.getNameAsString() + "' (containing an unqualified call to '" + oldName
                                + "' at " + call.getBegin().map(Object::toString).orElse("?")
                                + ") already declares its own '" + newName
                                + "' method with the same parameter list, which would silently take priority "
                                + "over the intended call after the rename.");
                    }
                } catch (RefactorException e) {
                    throw e;
                } catch (RuntimeException e) {
                    // Can't resolve the existing candidate -- not this call's problem.
                }
            }
        });
    }

    /**
     * Refuses to rename a method that itself overrides a declaration further
     * up the hierarchy: obelisk's override propagation only goes downward
     * from the specified type, so starting from a non-root override would
     * silently rename only part of the family, leaving the interface/
     * superclass method and any sibling overrides out of sync (confirmed to
     * otherwise produce a stale {@code @Override} and non-matching sibling
     * declarations with no warning at all).
     */
    private static void rejectNonRootTarget(ResolvedMethodDeclaration resolvedTarget, String oldName,
                                             TypeDeclaration<?> targetClass) {
        List<ResolvedType> targetParams = resolvedTarget.formalParameterTypes();
        for (ResolvedReferenceType ancestor : resolvedTarget.declaringType().getAllAncestors()) {
            for (MethodUsage ancestorMethod : ancestor.getDeclaredMethods()) {
                if (ancestorMethod.getName().equals(oldName)
                        && overrideParamsMatch(ancestor, ancestorMethod, targetParams)) {
                    throw new RefactorException("'" + targetClass.getNameAsString() + "." + oldName
                            + "' overrides '" + ancestor.getQualifiedName() + "." + oldName
                            + "'. Point --class at '" + ancestor.getQualifiedName() + "' to rename the whole "
                            + "override family; renaming from a non-root override is refused to avoid leaving "
                            + "the interface/superclass method and sibling overrides out of sync.");
                }
            }
        }
    }

    /**
     * Finds every method elsewhere in the project that overrides the target:
     * resolves to a declaring type that has {@code ownerQualifiedName} among
     * its ancestors, with a matching name and a parameter list that matches
     * the ancestor method's parameter list AS SEEN THROUGH that specific
     * ancestor -- see {@link #overrideParamsMatch}, which manually applies
     * the ancestor's type-argument substitution (the library's own
     * {@link ResolvedReferenceType#getDeclaredMethods()} does NOT do this).
     * Not a raw text comparison, so a generic method like
     * {@code interface Repo<T> { T get(); }} implemented as
     * {@code UserRepo implements Repo<User>} correctly matches
     * {@code User get()} against the substituted {@code T -> User}.
     *
     * <p>Scans every {@link MethodDeclaration} in the project directly
     * (rather than only ones reachable via a named {@link TypeDeclaration}),
     * so overrides declared in an anonymous class body or an enum constant's
     * class body are found too -- {@code candidate.resolve()} works from AST
     * position regardless of whether the enclosing type has a name.
     *
     * <p>Unresolvable candidates are skipped with a warning rather than
     * failing the whole rename.
     */
    private static List<MethodDeclaration> findOverrides(ProjectContext ctx, String ownerQualifiedName,
                                                           String oldName, MethodDeclaration targetMethod,
                                                           ResolvedMethodDeclaration resolvedTarget,
                                                           List<String> warnings) {
        List<MethodDeclaration> overrides = new ArrayList<>();
        for (Map.Entry<Path, CompilationUnit> entry : ctx.unitsByFile().entrySet()) {
            for (MethodDeclaration candidate : entry.getValue().findAll(MethodDeclaration.class)) {
                if (candidate == targetMethod || !candidate.getNameAsString().equals(oldName)) {
                    continue;
                }
                try {
                    ResolvedMethodDeclaration resolvedCandidate = candidate.resolve();
                    ResolvedReferenceTypeDeclaration declaringType = resolvedCandidate.declaringType();
                    if (declaringType.getQualifiedName().equals(ownerQualifiedName)) {
                        // An enum constant body's override of an abstract enum
                        // method resolves declaringType() to the enum itself
                        // (not a distinct synthetic type the way `new X() {}`
                        // gets one), so it looks identical to an ordinary
                        // sibling overload declared directly on the root type.
                        // Distinguish by AST position: only treat it as the
                        // root declaration's sibling (i.e. not an override) if
                        // it ISN'T inside an enum constant's class body. Enums
                        // can't be generic, so no substitution is needed here.
                        boolean isEnumConstantOverride = candidate
                                .findAncestor(com.github.javaparser.ast.body.EnumConstantDeclaration.class)
                                .isPresent();
                        if (isEnumConstantOverride
                                && paramsMatch(resolvedCandidate.formalParameterTypes(),
                                        resolvedTarget.formalParameterTypes())) {
                            overrides.add(candidate);
                        }
                        continue;
                    }
                    List<ResolvedType> candidateParams = resolvedCandidate.formalParameterTypes();
                    boolean overridesTarget = false;
                    for (ResolvedReferenceType ancestor : declaringType.getAllAncestors()) {
                        if (!ancestor.getQualifiedName().equals(ownerQualifiedName)) {
                            continue;
                        }
                        for (MethodUsage ancestorMethod : ancestor.getDeclaredMethods()) {
                            if (ancestorMethod.getName().equals(oldName)
                                    && overrideParamsMatch(ancestor, ancestorMethod, candidateParams)) {
                                overridesTarget = true;
                                break;
                            }
                        }
                        if (overridesTarget) {
                            break;
                        }
                    }
                    if (overridesTarget) {
                        overrides.add(candidate);
                    }
                } catch (RuntimeException e) {
                    warnings.add("Could not check '" + candidate.getNameAsString() + "' in " + entry.getKey()
                            + " for overrides (skipped): " + e.getMessage());
                }
            }
        }
        return overrides;
    }

    /**
     * Finds static imports whose qualifier actually resolves to the root
     * declaring type or one of its subtypes (so an import of the target
     * reached via a subclass name is still caught), rather than matching by
     * simple import-name text alone -- which would also rewrite an unrelated
     * static import of a same-named method on a different, unrelated class.
     */
    private static Set<ImportDeclaration> findStaticImportsToRename(ProjectContext ctx, String ownerQualifiedName,
                                                                      String oldName) {
        Set<ImportDeclaration> result = new LinkedHashSet<>();
        for (CompilationUnit cu : ctx.unitsByFile().values()) {
            for (ImportDeclaration imp : cu.getImports()) {
                if (!imp.isStatic() || imp.isAsterisk()) {
                    continue;
                }
                String qualifiedName = imp.getNameAsString();
                int lastDot = qualifiedName.lastIndexOf('.');
                if (lastDot < 0 || !qualifiedName.substring(lastDot + 1).equals(oldName)) {
                    continue;
                }
                String qualifier = qualifiedName.substring(0, lastDot);
                SymbolReference<ResolvedReferenceTypeDeclaration> resolved = ctx.typeSolver().tryToSolveType(qualifier);
                if (!resolved.isSolved()) {
                    continue;
                }
                ResolvedReferenceTypeDeclaration decl = resolved.getCorrespondingDeclaration();
                boolean matchesFamily = decl.getQualifiedName().equals(ownerQualifiedName)
                        || decl.getAllAncestors().stream().anyMatch(a -> a.getQualifiedName().equals(ownerQualifiedName));
                if (matchesFamily) {
                    result.add(imp);
                }
            }
        }
        return result;
    }

    private static boolean paramsMatch(List<ResolvedType> a, List<ResolvedType> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).describe().equals(b.get(i).describe())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Like {@link #paramsMatch}, but for comparing an ancestor's method
     * (as returned by {@link ResolvedReferenceType#getDeclaredMethods()},
     * which does NOT substitute the ancestor's own type arguments into its
     * methods' parameter types -- confirmed empirically: for
     * {@code UserRepo implements Repo<User>}, the ancestor {@code Repo<User>}
     * still reports its {@code save} method's parameter as the raw type
     * variable {@code T}, not {@code User}) against a candidate's already-
     * concrete parameter types. Each ancestor-method type variable is looked
     * up in the ancestor's own {@link ResolvedReferenceType#typeParametersMap()}
     * before comparing.
     */
    private static boolean overrideParamsMatch(ResolvedReferenceType ancestor, MethodUsage ancestorMethod,
                                                List<ResolvedType> candidateParams) {
        List<ResolvedType> ancestorParams = ancestorMethod.getParamTypes();
        if (ancestorParams.size() != candidateParams.size()) {
            return false;
        }
        for (int i = 0; i < ancestorParams.size(); i++) {
            ResolvedType substituted = substitute(ancestor, ancestorParams.get(i));
            if (!substituted.describe().equals(candidateParams.get(i).describe())) {
                return false;
            }
        }
        return true;
    }

    private static ResolvedType substitute(ResolvedReferenceType ancestor, ResolvedType type) {
        if (type.isTypeVariable()) {
            try {
                ResolvedType value = ancestor.typeParametersMap().getValue(type.asTypeVariable().asTypeParameter());
                if (value != null) {
                    return value;
                }
            } catch (RuntimeException ignored) {
                // fall through and compare unsubstituted rather than fail the whole rename
            }
        }
        return type;
    }

    @SuppressWarnings("unchecked")
    private static TypeDeclaration<?> castTypeDeclaration(TypeDeclaration raw) {
        return (TypeDeclaration<?>) raw;
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
     * near-instant rename/move itself, not the content write. The temp
     * filename includes a random suffix so two obelisk invocations touching
     * the same file concurrently don't collide.
     */
    private static void writeAll(Map<Path, String> updatedContents) {
        Map<Path, Path> tempFiles = new LinkedHashMap<>();
        try {
            for (Map.Entry<Path, String> entry : updatedContents.entrySet()) {
                Path target = entry.getKey();
                Path temp = target.resolveSibling(target.getFileName() + ".obelisk-tmp-"
                        + java.util.UUID.randomUUID());
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
