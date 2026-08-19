package dev.obelisk.core.refactor;

import dev.obelisk.guard.Check;
import dev.obelisk.guard.Guard;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Renames a class/interface/enum/record/annotation declaration, plus every
 * reference obelisk can resolve back to it: type usages (fields, params,
 * return types, generics, casts, {@code instanceof}, {@code new Foo()}),
 * constructors, matching imports, annotation usages (if the target is itself
 * an annotation type), and static-member qualifiers (e.g. {@code Foo.BAR}).
 * If the target is a top-level type whose source file is named after it, the
 * file itself is renamed to match.
 *
 * V1 known limitations (fail loudly / document, rather than guess):
 *  - the class must be uniquely identified by simple name across the project
 *    (see {@link ClassFinder})
 *  - references only in comments, Javadoc, or string literals are not
 *    renamed (same limitation as rename-method)
 *  - a static import of the class itself as a nested member (e.g.
 *    {@code import static Outer.Inner;}) is not renamed
 */
public final class RenameClassRefactor {

    private RenameClassRefactor() {
    }

    public static RefactorResult run(ProjectContext ctx, String className, String newName, boolean apply) {
        validateIdentifier(newName);

        List<String> warnings = new ArrayList<>();

        TypeDeclaration<?> targetClass = ClassFinder.findClass(ctx, className);
        String targetQualifiedName = targetClass.getFullyQualifiedName()
                .orElseThrow(() -> new RefactorException(Check.INVALID_IDENTIFIER, "Could not determine the fully-qualified name of '"
                        + className + "'"));

        rejectDuplicateTypeName(ctx, targetClass, newName);

        // Resolve every matching reference against the ORIGINAL (unmodified)
        // AST first, and defer every mutation to a single batch applied
        // afterward -- same two-pass approach as RenameMethodRefactor, for the
        // same reason: renaming the declaration up front would make later
        // resolution of other references to it fail.
        List<ClassOrInterfaceType> typesToRename = new ArrayList<>();
        List<AnnotationExpr> annotationsToRename = new ArrayList<>();
        List<SimpleName> staticQualifiersToRename = new ArrayList<>();
        Set<ImportDeclaration> importsToRename = new LinkedHashSet<>();

        for (CompilationUnit cu : ctx.unitsByFile().values()) {
            for (ClassOrInterfaceType type : cu.findAll(ClassOrInterfaceType.class)) {
                if (!type.getNameAsString().equals(className)) {
                    continue;
                }
                try {
                    ResolvedType resolved = type.resolve();
                    if (resolved.isReferenceType()
                            && resolved.asReferenceType().getQualifiedName().equals(targetQualifiedName)) {
                        typesToRename.add(type);
                    }
                } catch (RuntimeException e) {
                    warnings.add("Could not resolve type reference '" + type + "' in " + fileOf(ctx, cu)
                            + " (left unchanged): " + e.getMessage());
                }
            }

            for (AnnotationExpr annotation : cu.findAll(AnnotationExpr.class)) {
                String simpleName = simpleNameOf(annotation.getNameAsString());
                if (!simpleName.equals(className)) {
                    continue;
                }
                try {
                    if (annotation.resolve().getQualifiedName().equals(targetQualifiedName)) {
                        annotationsToRename.add(annotation);
                    }
                } catch (RuntimeException e) {
                    warnings.add("Could not resolve annotation usage '" + annotation + "' in " + fileOf(ctx, cu)
                            + " (left unchanged): " + e.getMessage());
                }
            }

            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                call.getScope().ifPresent(scope -> {
                    Optional<SimpleName> qualifier = staticQualifierCandidate(scope, className);
                    if (qualifier.isEmpty()) {
                        return;
                    }
                    if (!tryResolve(call, warnings, ctx, cu)) {
                        return;
                    }
                    maybeAddStaticQualifier(scope, qualifier.get(), cu, className, targetQualifiedName,
                            staticQualifiersToRename);
                });
            }
            for (FieldAccessExpr access : cu.findAll(FieldAccessExpr.class)) {
                Expression scope = access.getScope();
                Optional<SimpleName> qualifier = staticQualifierCandidate(scope, className);
                if (qualifier.isEmpty()) {
                    continue;
                }
                if (!tryResolve(access, warnings, ctx, cu)) {
                    continue;
                }
                maybeAddStaticQualifier(scope, qualifier.get(), cu, className, targetQualifiedName,
                        staticQualifiersToRename);
            }

            for (ImportDeclaration imp : cu.getImports()) {
                String name = imp.getNameAsString();
                if (!imp.isStatic()) {
                    if (!imp.isAsterisk() && name.equals(targetQualifiedName)) {
                        importsToRename.add(imp);
                    }
                    continue;
                }
                if (imp.isAsterisk()) {
                    // import static com.example.Foo.*; -- the qualifier IS the
                    // whole target type, not a member of it.
                    if (name.equals(targetQualifiedName)) {
                        importsToRename.add(imp);
                    }
                    continue;
                }
                int lastDot = name.lastIndexOf('.');
                if (lastDot >= 0 && name.substring(0, lastDot).equals(targetQualifiedName)) {
                    importsToRename.add(imp);
                }
            }
        }

        // Constructors (and record compact constructors) always share their
        // enclosing class's simple name -- they're not discoverable via
        // ClassOrInterfaceType resolution the way ordinary references are, so
        // they're collected directly from the target's own members.
        List<Node> constructorsToRename = new ArrayList<>();
        for (BodyDeclaration<?> member : targetClass.getMembers()) {
            if (member instanceof ConstructorDeclaration ctor && ctor.getNameAsString().equals(className)) {
                constructorsToRename.add(ctor);
            } else if (member instanceof CompactConstructorDeclaration compact
                    && compact.getNameAsString().equals(className)) {
                constructorsToRename.add(compact);
            }
        }

        rejectNewNameAlreadyBoundAtReference(ctx, targetClass, className, newName, typesToRename,
                annotationsToRename, staticQualifiersToRename, importsToRename);

        Map<Path, String> originalContents = new LinkedHashMap<>();
        originalContents.computeIfAbsent(fileOf(ctx, targetClass.findCompilationUnit().orElseThrow()),
                RenameClassRefactor::readOriginal);
        for (ClassOrInterfaceType type : typesToRename) {
            originalContents.computeIfAbsent(fileOf(ctx, type.findCompilationUnit().orElseThrow()),
                    RenameClassRefactor::readOriginal);
        }
        for (AnnotationExpr annotation : annotationsToRename) {
            originalContents.computeIfAbsent(fileOf(ctx, annotation.findCompilationUnit().orElseThrow()),
                    RenameClassRefactor::readOriginal);
        }
        for (SimpleName qualifier : staticQualifiersToRename) {
            originalContents.computeIfAbsent(fileOf(ctx, qualifier.findCompilationUnit().orElseThrow()),
                    RenameClassRefactor::readOriginal);
        }
        for (ImportDeclaration imp : importsToRename) {
            originalContents.computeIfAbsent(fileOf(ctx, imp.findCompilationUnit().orElseThrow()),
                    RenameClassRefactor::readOriginal);
        }

        // Apply every mutation together.
        targetClass.getName().setIdentifier(newName);
        for (Node ctor : constructorsToRename) {
            setConstructorName(ctor, newName);
        }
        renameTypeReferences(typesToRename, newName);
        for (AnnotationExpr annotation : annotationsToRename) {
            // AnnotationExpr#setName(...) replaces the whole Name node, which
            // this JavaParser version's LexicalPreservingPrinter fails to
            // apply for a MarkerAnnotationExpr (confirmed via minimal repro:
            // throws UnsupportedOperationException("removed CsmToken(...) vs
            // ChildTextElement{...}") even for a trivially valid rename).
            // Mutating the existing SimpleName in place instead works fine --
            // same pattern used everywhere else in this codebase.
            annotation.getName().setIdentifier(newName);
        }
        for (SimpleName qualifier : staticQualifiersToRename) {
            qualifier.setIdentifier(newName);
        }
        String newQualifiedName = targetQualifiedName.substring(0, targetQualifiedName.length() - className.length())
                + newName;
        for (ImportDeclaration imp : importsToRename) {
            if (imp.isStatic() && !imp.isAsterisk()) {
                String name = imp.getNameAsString();
                int lastDot = name.lastIndexOf('.');
                imp.setName(qualifiedName(newQualifiedName + name.substring(lastDot)));
            } else {
                // Static asterisk import of the target itself, or a plain
                // (non-static) import -- both cases the whole qualifier is
                // the target's own FQN.
                imp.setName(qualifiedName(newQualifiedName));
            }
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

        Map<Path, Path> renamedFiles = new LinkedHashMap<>();
        Path targetFile = fileOf(ctx, targetClass.findCompilationUnit().orElseThrow());
        if (isTopLevelType(targetClass) && isSoleTopLevelType(targetClass) && fileNameMatches(targetFile, className)) {
            Path newFile = targetFile.resolveSibling(newName + ".java");
            if (Files.exists(newFile)) {
                // Moving onto an existing file would silently destroy
                // whatever is there today (confirmed via repro: an unrelated
                // file that just happens to already be named e.g.
                // Gadget.java gets clobbered with REPLACE_EXISTING, with no
                // warning, and if that file is ALSO one of the ones getting
                // content changes in this same rename, ordering decides which
                // write wins and the other is lost outright -- including,
                // in the worst case, the renamed class's own new content).
                rejectExistingTargetFile(newFile, className, newName);
            }
            renamedFiles.put(targetFile, newFile);
        } else if (isTopLevelType(targetClass)) {
            warnings.add("'" + className + "' is a top-level type but its file, " + targetFile
                    + ", contains more than one top-level type or isn't named after it -- leaving the file "
                    + "as-is (rename it manually if needed).");
        }

        if (apply && (!updatedContents.isEmpty() || !renamedFiles.isEmpty())) {
            writeAll(updatedContents, renamedFiles);
        }

        return new RefactorResult(apply, changedFiles, diffs, renamedFiles, warnings);
    }

    private static void validateIdentifier(String name) {
        if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
            throw new RefactorException(Check.INVALID_IDENTIFIER, "'" + name + "' is not a valid Java type name");
        }
    }

    /**
     * Refuses when {@code newName} ALREADY names a type at some site that
     * references the class being renamed.
     *
     * <p>{@link #rejectDuplicateTypeName} only looks for a SIBLING
     * declaration (same package, or same enclosing type). That misses every
     * way a simple name can already be taken at a REFERENCE site, which is
     * scope-sensitive and differs file by file: a single-type import, an
     * enclosing type parameter, a {@code java.lang} type, or an on-demand
     * import.
     *
     * <p>Confirmed by repro, and silent: {@code p.Gadget} and {@code
     * q.Widget} both exist, and {@code p.Client} imports {@code q.Widget}
     * while calling both. Renaming {@code p.Gadget} to {@code Widget}
     * succeeds -- there is no sibling {@code Widget} in {@code p} -- and the
     * rewritten {@code Widget.tag()} now binds to {@code q.Widget}, because
     * a single-type import outranks a same-package type. Both calls end up
     * at the same class and the file still compiles. A second repro,
     * renaming onto an enclosing type parameter {@code T}, breaks the build
     * instead.
     */
    @Guard(Check.REJECT_NEW_NAME_ALREADY_BOUND_AT_REFERENCE)
    private static void rejectNewNameAlreadyBoundAtReference(ProjectContext ctx, TypeDeclaration<?> targetClass,
                                                               String className, String newName,
                                                               List<ClassOrInterfaceType> typesToRename,
                                                               List<AnnotationExpr> annotationsToRename,
                                                               List<SimpleName> staticQualifiersToRename,
                                                               Set<ImportDeclaration> importsToRename) {
        List<Node> sites = new ArrayList<>();
        sites.add(targetClass);
        sites.addAll(typesToRename);
        sites.addAll(annotationsToRename);
        sites.addAll(staticQualifiersToRename);
        // An IMPORT is a site that claims the simple name too, and this
        // refactor rewrites it -- confirmed by repro that omitting it emitted
        // `import p.Widget; import q.Widget;` in a file that imported both,
        // which javac rejects as a duplicate single-type import.
        sites.addAll(importsToRename);
        for (Node site : sites) {
            NameBindingChecker.typeBindingAt(ctx.typeSolver(), site, newName).ifPresent(bound -> {
                throw new RefactorException(Check.REJECT_NEW_NAME_ALREADY_BOUND_AT_REFERENCE, "Cannot rename '" + className + "' to '" + newName + "': that name "
                        + "already means " + bound + " at a place that references '" + className + "' ("
                        + fileOf(ctx, site.findCompilationUnit().orElseThrow()) + "). Renaming would silently "
                        + "rebind that reference to the wrong type, or fail to compile. Not supported in this "
                        + "version.");
            });
        }
    }

    /**
     * Refuses to rename into a name that would collide with a sibling type
     * already declared at the same nesting level -- Java disallows two
     * top-level types with the same simple name in one package (checked
     * across every file in the project that shares the target's package, NOT
     * just its own file -- distinct top-level types are almost always
     * declared one-per-file), or two member types with the same simple name
     * in one enclosing type.
     */
    /**
     * Refuses when the file this rename would move the class into already
     * exists. Extracted from an inline {@code throw} so the mutation script
     * can reach it -- and worth measuring more than most, since the failure
     * it prevents is DATA LOSS: the move uses {@code REPLACE_EXISTING}, so
     * without this an unrelated file is clobbered silently.
     */
    @Guard(Check.REJECT_EXISTING_TARGET_FILE)
    private static void rejectExistingTargetFile(Path newFile, String className, String newName) {
        throw new RefactorException(Check.REJECT_EXISTING_TARGET_FILE, "Cannot rename '" + className + "' to '" + newName + "': " + newFile
                + " already exists. Remove or rename it first.");
    }

    @Guard(Check.REJECT_DUPLICATE_TYPE_NAME)
    private static void rejectDuplicateTypeName(ProjectContext ctx, TypeDeclaration<?> targetClass, String newName) {
        if (targetClass.getNameAsString().equals(newName)) {
            throw new RefactorException(Check.REJECT_DUPLICATE_TYPE_NAME, "'" + newName + "' is already the name of '"
                    + targetClass.getNameAsString() + "'");
        }
        List<TypeDeclaration<?>> siblings;
        if (isTopLevelType(targetClass)) {
            String targetPackage = targetClass.findCompilationUnit()
                    .flatMap(CompilationUnit::getPackageDeclaration)
                    .map(p -> p.getNameAsString())
                    .orElse("");
            siblings = new ArrayList<>();
            for (CompilationUnit cu : ctx.unitsByFile().values()) {
                String cuPackage = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
                if (cuPackage.equals(targetPackage)) {
                    siblings.addAll(cu.getTypes());
                }
            }
        } else {
            siblings = targetClass.findAncestor(TypeDeclaration.class)
                    .map(RenameClassRefactor::memberTypesOf)
                    .orElse(List.of());
        }
        for (TypeDeclaration<?> sibling : siblings) {
            if (sibling != targetClass && sibling.getNameAsString().equals(newName)) {
                throw new RefactorException(Check.REJECT_DUPLICATE_TYPE_NAME, "Cannot rename '" + targetClass.getNameAsString() + "' to '" + newName
                        + "': a sibling type named '" + newName + "' already exists.");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<TypeDeclaration<?>> memberTypesOf(TypeDeclaration<?> owner) {
        List<TypeDeclaration<?>> result = new ArrayList<>();
        for (BodyDeclaration<?> member : owner.getMembers()) {
            if (member instanceof TypeDeclaration<?> type) {
                result.add(type);
            }
        }
        return result;
    }

    /**
     * A static member access's scope ({@code Foo.bar()}, {@code Foo.BAR},
     * {@code Outer.Inner.BAR}, {@code pkg.Foo.BAR}) is a chain of
     * {@link FieldAccessExpr}s bottoming out in a {@link NameExpr} -- the
     * class's own simple name shows up as either that root {@link NameExpr}
     * (unqualified access) or an inner {@link FieldAccessExpr}'s name
     * (qualified/nested access), never anywhere else in the chain. Returns
     * that node's {@link SimpleName} if {@code scope} is one of those two
     * shapes and its text matches {@code className}; otherwise empty (not a
     * candidate at all, regardless of what it resolves to).
     */
    private static Optional<SimpleName> staticQualifierCandidate(Expression scope, String className) {
        if (scope.isNameExpr() && scope.asNameExpr().getNameAsString().equals(className)) {
            return Optional.of(scope.asNameExpr().getName());
        }
        if (scope.isFieldAccessExpr() && scope.asFieldAccessExpr().getNameAsString().equals(className)) {
            return Optional.of(scope.asFieldAccessExpr().getName());
        }
        return Optional.empty();
    }

    /**
     * Confirms a {@link #staticQualifierCandidate} really is a reference to
     * the target class (as opposed to, say, a local variable/field literally
     * named the same as the class, or an unrelated same-simple-name class
     * reached through an explicit import) before adding it to the rename
     * list. Deliberately does NOT require the member being accessed to be
     * DECLARED directly on the target class -- an inherited static member
     * (accessed via the subclass being renamed) or an enum constant (whose
     * resolved declaration doesn't expose a {@code declaringType()} at all)
     * both still need their qualifier renamed, and the caller has already
     * confirmed the overall expression resolves to SOMETHING via
     * {@link #tryResolve}, so what's left to rule out is just "is this
     * qualifier text actually OUR class in this file's scope":
     *  - it must not itself resolve as a value (rules out a same-named
     *    variable/field shadowing the class)
     *  - this file must not have an explicit single-type import of a
     *    DIFFERENT class with the same simple name (rules out the rare case
     *    where {@code className} is ambiguous project-wide except for
     *    {@link ClassFinder}'s top-level-wins tiebreak, and this particular
     *    file explicitly imports the other, non-winning one)
     */
    private static void maybeAddStaticQualifier(Expression scope, SimpleName qualifier, CompilationUnit cu,
                                                  String className, String targetQualifiedName,
                                                  List<SimpleName> staticQualifiersToRename) {
        if (resolvesAsValue(scope) || hasConflictingImport(cu, className, targetQualifiedName)) {
            return;
        }
        staticQualifiersToRename.add(qualifier);
    }

    private static boolean resolvesAsValue(Expression scope) {
        try {
            if (scope.isNameExpr()) {
                scope.asNameExpr().resolve();
                return true;
            }
            if (scope.isFieldAccessExpr()) {
                scope.asFieldAccessExpr().resolve();
                return true;
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    private static boolean hasConflictingImport(CompilationUnit cu, String simpleName, String targetQualifiedName) {
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isStatic() || imp.isAsterisk()) {
                continue;
            }
            String name = imp.getNameAsString();
            String importedSimple = simpleNameOf(name);
            if (importedSimple.equals(simpleName) && !name.equals(targetQualifiedName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean tryResolve(MethodCallExpr call, List<String> warnings, ProjectContext ctx,
                                       CompilationUnit cu) {
        try {
            call.resolve();
            return true;
        } catch (RuntimeException e) {
            warnings.add("Could not resolve call '" + call + "' in " + fileOf(ctx, cu)
                    + " while checking whether its scope is a static-qualifier reference to '" + call.getNameAsString()
                    + "' (left unchanged): " + e.getMessage());
            return false;
        }
    }

    private static boolean tryResolve(FieldAccessExpr access, List<String> warnings, ProjectContext ctx,
                                       CompilationUnit cu) {
        try {
            access.resolve();
            return true;
        } catch (RuntimeException e) {
            warnings.add("Could not resolve field access '" + access + "' in " + fileOf(ctx, cu)
                    + " while checking whether its scope is a static-qualifier reference (left unchanged): "
                    + e.getMessage());
            return false;
        }
    }

    /**
     * Renames every matched type reference, EXCEPT that mutating a
     * {@link ClassOrInterfaceType} in place is silently not picked up by
     * {@link LexicalPreservingPrinter} when it (or an ancestor type node,
     * e.g. a generic type argument) lives inside a {@link VariableDeclarator}
     * -- confirmed by minimal repro: identical in-place {@code setName} calls
     * are reflected in the printed output for a field/param/return type, an
     * {@code extends} clause, or a cast, but NOT for a local variable or
     * field's own declared type (including nested generic arguments like
     * {@code List<Widget>}), even though the mutated AST node itself reports
     * the new name correctly when inspected directly -- the printer's
     * change-tracking just doesn't observe that particular position. Worked
     * around by cloning the VariableDeclarator's whole type subtree, renaming
     * the clone, and replacing the type wholesale via {@code setType} (a
     * top-level child replacement, which IS observed correctly) rather than
     * mutating a node buried inside it.
     */
    private static void renameTypeReferences(List<ClassOrInterfaceType> typesToRename, String newName) {
        Map<VariableDeclarator, List<ClassOrInterfaceType>> byDeclarator = new LinkedHashMap<>();
        List<ClassOrInterfaceType> direct = new ArrayList<>();
        for (ClassOrInterfaceType type : typesToRename) {
            Optional<VariableDeclarator> owner = owningVariableDeclarator(type);
            if (owner.isPresent()) {
                byDeclarator.computeIfAbsent(owner.get(), k -> new ArrayList<>()).add(type);
            } else {
                direct.add(type);
            }
        }
        for (ClassOrInterfaceType type : direct) {
            type.setName(newName);
        }
        for (Map.Entry<VariableDeclarator, List<ClassOrInterfaceType>> entry : byDeclarator.entrySet()) {
            VariableDeclarator vd = entry.getKey();
            Map<ClassOrInterfaceType, Boolean> toRename = new IdentityHashMap<>();
            for (ClassOrInterfaceType type : entry.getValue()) {
                toRename.put(type, Boolean.TRUE);
            }
            List<ClassOrInterfaceType> originalNodes = vd.getType().findAll(ClassOrInterfaceType.class);
            Type clonedType = vd.getType().clone();
            List<ClassOrInterfaceType> clonedNodes = clonedType.findAll(ClassOrInterfaceType.class);
            for (int i = 0; i < originalNodes.size(); i++) {
                if (toRename.containsKey(originalNodes.get(i))) {
                    clonedNodes.get(i).setName(newName);
                }
            }
            vd.setType(clonedType);
        }
    }

    /**
     * Walks up from {@code type} through Type-typed ancestors only (generic
     * type arguments, array/wildcard wrapping) -- if that walk lands on a
     * {@link VariableDeclarator}, {@code type} is part of its declared type;
     * if it hits anything else first (e.g. an initializer expression, which
     * can itself contain unrelated type references like a cast), it isn't.
     */
    private static Optional<VariableDeclarator> owningVariableDeclarator(ClassOrInterfaceType type) {
        Node current = type;
        Optional<Node> parent = current.getParentNode();
        while (parent.isPresent() && parent.get() instanceof Type) {
            current = parent.get();
            parent = current.getParentNode();
        }
        if (parent.isPresent() && parent.get() instanceof VariableDeclarator vd) {
            return Optional.of(vd);
        }
        return Optional.empty();
    }

    private static void setConstructorName(Node node, String newName) {
        if (node instanceof ConstructorDeclaration ctor) {
            ctor.getName().setIdentifier(newName);
        } else if (node instanceof CompactConstructorDeclaration compact) {
            compact.getName().setIdentifier(newName);
        }
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

    private static String simpleNameOf(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot < 0 ? qualifiedName : qualifiedName.substring(lastDot + 1);
    }

    private static boolean isTopLevelType(TypeDeclaration<?> type) {
        return type.getParentNode().filter(p -> p instanceof CompilationUnit).isPresent();
    }

    private static boolean isSoleTopLevelType(TypeDeclaration<?> type) {
        return type.findCompilationUnit().map(CompilationUnit::getTypes).map(types -> types.size() == 1)
                .orElse(false);
    }

    private static boolean fileNameMatches(Path file, String className) {
        String fileName = file.getFileName().toString();
        return fileName.equals(className + ".java");
    }

    private static Path fileOf(ProjectContext ctx, CompilationUnit cu) {
        return ctx.unitsByFile().entrySet().stream()
                .filter(e -> e.getValue() == cu)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new RefactorException(Check.INTERNAL_ERROR, "Internal error: compilation unit has no known source file"));
    }

    private static String readOriginal(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes every changed file's content to a sibling temp file first (same
     * atomic-batch approach as {@link RenameMethodRefactor#writeAll}), then
     * moves each temp file over its real target -- for a renamed file, the
     * move target is the NEW path, and the old path is deleted afterward
     * (its content, if it had any non-rename edits too, was already written
     * to the temp file keyed by the OLD path in {@code updatedContents} and
     * gets moved to the new path here rather than being written twice).
     */
    private static void writeAll(Map<Path, String> updatedContents, Map<Path, Path> renamedFiles) {
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
                Path originalTarget = entry.getKey();
                Path temp = entry.getValue();
                Path finalTarget = renamedFiles.getOrDefault(originalTarget, originalTarget);
                Files.move(temp, finalTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                if (!finalTarget.equals(originalTarget)) {
                    // The temp file's content landed at the NEW path above --
                    // the OLD path still has its pre-rename content sitting on
                    // disk and must be removed, or the rename would silently
                    // leave a stale duplicate behind instead of actually
                    // moving the file (confirmed via repro: without this,
                    // renaming Config -> RootConfig left BOTH Config.java,
                    // untouched, and RootConfig.java on disk).
                    Files.deleteIfExists(originalTarget);
                }
            }
            for (Map.Entry<Path, Path> entry : renamedFiles.entrySet()) {
                if (!updatedContents.containsKey(entry.getKey())) {
                    // The file is only being renamed, with no textual changes
                    // of its own (e.g. a class whose body has no self-references).
                    Files.move(entry.getKey(), entry.getValue(), StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                }
            }
        } catch (IOException e) {
            for (Path temp : tempFiles.values()) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
            throw new RefactorException(Check.WRITE_FAILED, "Failed to write changes: " + e.getMessage(), e);
        }
    }
}
