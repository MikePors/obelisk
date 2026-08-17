package dev.obelisk.core.refactor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
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
 * Renames a field declared directly on a given class/interface/enum/record,
 * plus every read/write obelisk can resolve back to it: unqualified
 * references, {@code this.field}/{@code super.field}, and qualified access
 * through an instance or (for static fields) the class name.
 *
 * V1 known limitations (fail loudly / document, rather than guess):
 *  - the class must be uniquely identified by simple name across the project
 *    (see {@link ClassFinder})
 *  - record components are not fields in JavaParser's model (they're
 *    constructor-like parameters that happen to also generate an implicit
 *    field/accessor) -- renaming one isn't supported here and fails with
 *    "no field found", same as pointing at any other nonexistent field
 *  - references only in comments, Javadoc, or string literals are not
 *    renamed (same limitation as rename-method/rename-class)
 *  - a subclass field that HIDES (not overrides -- fields don't override)
 *    this one is a distinct field and is deliberately left untouched
 */
public final class RenameFieldRefactor {

    private RenameFieldRefactor() {
    }

    public static RefactorResult run(ProjectContext ctx, String className, String oldName, String newName,
                                      boolean apply) {
        validateIdentifier(newName);

        List<String> warnings = new ArrayList<>();

        TypeDeclaration<?> targetClass = ClassFinder.findClass(ctx, className);
        VariableDeclarator targetField = findField(targetClass, oldName);
        ResolvedValueDeclaration resolvedTarget = resolveTarget(targetField, targetClass, oldName);
        if (!(resolvedTarget instanceof ResolvedFieldDeclaration resolvedField)) {
            throw new RefactorException("'" + oldName + "' on '" + targetClass.getNameAsString()
                    + "' did not resolve to a field declaration");
        }
        String ownerQualifiedName = resolvedField.declaringType().getQualifiedName();

        rejectDuplicateFieldName(targetClass, targetField, newName, oldName);

        // Resolve every matching reference against the ORIGINAL (unmodified)
        // AST first, and defer every mutation to a single batch applied
        // afterward -- same two-pass discipline as rename-method/rename-class.
        List<NameExpr> unqualifiedToRename = new ArrayList<>();
        List<FieldAccessExpr> qualifiedToRename = new ArrayList<>();

        for (CompilationUnit cu : ctx.unitsByFile().values()) {
            for (NameExpr name : cu.findAll(NameExpr.class)) {
                if (!name.getNameAsString().equals(oldName)) {
                    continue;
                }
                ResolvedValueDeclaration resolved = tryResolve(name, warnings, ctx, cu);
                if (resolved instanceof ResolvedFieldDeclaration field && matchesTarget(field, ownerQualifiedName, oldName)) {
                    unqualifiedToRename.add(name);
                    rejectShadowingCollision(ctx, name, newName, oldName);
                }
            }
            for (FieldAccessExpr access : cu.findAll(FieldAccessExpr.class)) {
                if (!access.getNameAsString().equals(oldName)) {
                    continue;
                }
                try {
                    ResolvedValueDeclaration resolved = access.resolve();
                    if (resolved instanceof ResolvedFieldDeclaration field
                            && matchesTarget(field, ownerQualifiedName, oldName)) {
                        qualifiedToRename.add(access);
                    }
                } catch (RuntimeException e) {
                    warnings.add("Could not resolve field access '" + access + "' in " + fileOf(ctx, cu)
                            + " (left unchanged): " + e.getMessage());
                }
            }
        }

        // Static imports are handled as their own pass, independent of which
        // reads/writes matched: a candidate is only renamed if its qualifier
        // actually resolves to the root declaring type or one of its
        // subtypes (so an import of the field reached via a subclass name is
        // still caught, and an unrelated static import that merely shares
        // the old simple name is never touched) -- same approach as
        // rename-method's static-import handling.
        Set<ImportDeclaration> staticImportsToRename = findStaticFieldImportsToRename(ctx, ownerQualifiedName, oldName);

        Map<Path, String> originalContents = new LinkedHashMap<>();
        originalContents.computeIfAbsent(fileOf(ctx, targetField.findCompilationUnit().orElseThrow()),
                RenameFieldRefactor::readOriginal);
        for (NameExpr name : unqualifiedToRename) {
            originalContents.computeIfAbsent(fileOf(ctx, name.findCompilationUnit().orElseThrow()),
                    RenameFieldRefactor::readOriginal);
        }
        for (FieldAccessExpr access : qualifiedToRename) {
            originalContents.computeIfAbsent(fileOf(ctx, access.findCompilationUnit().orElseThrow()),
                    RenameFieldRefactor::readOriginal);
        }
        for (ImportDeclaration imp : staticImportsToRename) {
            originalContents.computeIfAbsent(fileOf(ctx, imp.findCompilationUnit().orElseThrow()),
                    RenameFieldRefactor::readOriginal);
        }

        // Apply every mutation together.
        targetField.getName().setIdentifier(newName);
        for (NameExpr name : unqualifiedToRename) {
            name.setName(newName);
        }
        for (FieldAccessExpr access : qualifiedToRename) {
            access.getName().setIdentifier(newName);
        }
        for (ImportDeclaration imp : staticImportsToRename) {
            // Always normalize to the declaring type's FQN, even if the
            // import originally reached the field through a subclass name.
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

        return new RefactorResult(apply, changedFiles, diffs, Map.of(), warnings);
    }

    private static void validateIdentifier(String name) {
        if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
            throw new RefactorException("'" + name + "' is not a valid Java field name");
        }
    }

    /**
     * Finds the field declared directly on {@code targetClass} -- fields
     * aren't overloaded, so there's no {@code --params}-style disambiguation
     * to do, just a direct name lookup across the type's own
     * {@link FieldDeclaration}s (each of which may declare more than one
     * variable, e.g. {@code private int a, b;}).
     */
    private static VariableDeclarator findField(TypeDeclaration<?> targetClass, String fieldName) {
        for (BodyDeclaration<?> member : targetClass.getMembers()) {
            if (member instanceof FieldDeclaration fieldDecl) {
                for (VariableDeclarator variable : fieldDecl.getVariables()) {
                    if (variable.getNameAsString().equals(fieldName)) {
                        return variable;
                    }
                }
            }
        }
        throw new RefactorException("No field named '" + fieldName + "' declared directly on '"
                + targetClass.getNameAsString() + "'");
    }

    private static ResolvedValueDeclaration resolveTarget(VariableDeclarator field, TypeDeclaration<?> owner,
                                                            String originalName) {
        try {
            return field.resolve();
        } catch (RuntimeException e) {
            throw new RefactorException("Could not resolve target field '" + originalName + "' on '"
                    + owner.getNameAsString() + "': " + e.getMessage(), e);
        }
    }

    private static boolean matchesTarget(ResolvedFieldDeclaration field, String ownerQualifiedName, String oldName) {
        return field.getName().equals(oldName) && field.declaringType().getQualifiedName().equals(ownerQualifiedName);
    }

    /**
     * Finds static imports whose qualifier actually resolves to the root
     * declaring type or one of its subtypes (so an import of the target
     * reached via a subclass name is still caught), rather than matching by
     * simple import-name text alone.
     */
    private static Set<ImportDeclaration> findStaticFieldImportsToRename(ProjectContext ctx,
                                                                           String ownerQualifiedName, String oldName) {
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

    /** Builds a proper qualifier-chain {@link Name} rather than one dotted identifier. */
    private static Name qualifiedName(String dotted) {
        String[] parts = dotted.split("\\.");
        Name name = new Name(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            name = new Name(name, parts[i]);
        }
        return name;
    }

    /**
     * Refuses to rename into a name that would collide with another field
     * already declared directly on the same type -- Java disallows two
     * fields with the same name in one type.
     */
    private static void rejectDuplicateFieldName(TypeDeclaration<?> targetClass, VariableDeclarator targetField,
                                                   String newName, String oldName) {
        for (BodyDeclaration<?> member : targetClass.getMembers()) {
            if (member instanceof FieldDeclaration fieldDecl) {
                for (VariableDeclarator variable : fieldDecl.getVariables()) {
                    if (variable != targetField && variable.getNameAsString().equals(newName)) {
                        throw new RefactorException("Cannot rename '" + oldName + "' to '" + newName + "': '"
                                + targetClass.getNameAsString() + "' already declares a field named '" + newName + "'");
                    }
                }
            }
        }
    }

    /**
     * Refuses to rename an unqualified field reference if renaming would let
     * it be silently shadowed by something else Java would resolve to first
     * -- an unqualified name is looked up against local variables/
     * parameters/pattern bindings in scope BEFORE falling back to a field,
     * and if the field's OWN declaring type is reached via inheritance, the
     * closest type in the hierarchy that declares its own {@code newName}
     * field wins over the (further) target. Checks both:
     *
     * <p>1. Every local variable, parameter, or pattern-match binding
     * ({@code instanceof T name}, switch/record patterns) declared in ANY
     * enclosing method/constructor/initializer/lambda body -- not just the
     * nearest one. A lambda or anonymous class has no AST type-declaration
     * node wrapping its body, so simply walking parent pointers upward
     * naturally passes straight through those into whatever ordinary method
     * encloses them, capturing that method's locals correctly (confirmed via
     * repro: a field read inside a lambda/anonymous-class method can be
     * silently redirected to an outer local of the target new name that an
     * earlier "nearest enclosing scope only" version of this check missed
     * entirely). This deliberately does not try to distinguish a genuine
     * member/nested class boundary (whose methods do NOT capture an outer
     * method's locals) from a capturing one -- over-refusing in that rarer
     * case is the safe direction, consistent with this codebase's
     * "fail loudly rather than guess" philosophy.
     *
     * <p>2. Every type between the reference's own enclosing type and the
     * field's declaring type (inclusive of both ends) for one that already
     * declares its own {@code newName} field -- such a field would hide the
     * renamed one for any unqualified reference below it in the hierarchy,
     * exactly like the target class's own siblings check in
     * {@link #rejectDuplicateFieldName}, but for subclasses that merely
     * INHERIT the field being renamed rather than declaring it themselves.
     */
    private static void rejectShadowingCollision(ProjectContext ctx, NameExpr name, String newName, String oldName) {
        for (Node scopeRoot : enclosingScopeRoots(name)) {
            boolean collides = scopeRoot.findAll(VariableDeclarator.class).stream()
                    .anyMatch(vd -> vd.getNameAsString().equals(newName))
                    || scopeRoot.findAll(Parameter.class).stream()
                    .anyMatch(p -> p.getNameAsString().equals(newName))
                    || scopeRoot.findAll(TypePatternExpr.class).stream()
                    .anyMatch(p -> p.getNameAsString().equals(newName));
            if (collides) {
                throw new RefactorException("Cannot rename '" + oldName + "' to '" + newName + "': the method/"
                        + "constructor/initializer/lambda containing an unqualified reference to '" + oldName
                        + "' at " + name.getBegin().map(Object::toString).orElse("?") + " already has a local "
                        + "variable, parameter, or pattern binding named '" + newName
                        + "', which would silently shadow the field after rename.");
            }
        }

        name.findAncestor(TypeDeclaration.class).ifPresent(enclosingRaw -> {
            TypeDeclaration<?> enclosing = castTypeDeclaration(enclosingRaw);
            if (declaresOwnField(enclosing, newName)) {
                throw hidingCollision(enclosing, newName, oldName, name);
            }
            try {
                for (ResolvedReferenceType ancestor : enclosing.resolve().getAllAncestors()) {
                    Optional<TypeDeclaration<?>> ancestorAst = findTypeDeclaration(ctx, ancestor.getQualifiedName());
                    if (ancestorAst.isPresent() && declaresOwnField(ancestorAst.get(), newName)) {
                        throw hidingCollision(ancestorAst.get(), newName, oldName, name);
                    }
                }
            } catch (RefactorException e) {
                throw e;
            } catch (RuntimeException e) {
                // Can't resolve the hierarchy above the reference's own
                // enclosing type -- not fatal, that type's own fields were
                // already checked above.
            }
        });
    }

    private static RefactorException hidingCollision(TypeDeclaration<?> hidingType, String newName, String oldName,
                                                       NameExpr name) {
        return new RefactorException("Cannot rename '" + oldName + "' to '" + newName + "': '"
                + hidingType.getNameAsString() + "' already declares its own '" + newName
                + "' field, which would silently hide the renamed field for the unqualified reference at "
                + name.getBegin().map(Object::toString).orElse("?") + ".");
    }

    private static boolean declaresOwnField(TypeDeclaration<?> type, String fieldName) {
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof FieldDeclaration fieldDecl) {
                for (VariableDeclarator variable : fieldDecl.getVariables()) {
                    if (variable.getNameAsString().equals(fieldName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Optional<TypeDeclaration<?>> findTypeDeclaration(ProjectContext ctx, String qualifiedName) {
        for (CompilationUnit cu : ctx.unitsByFile().values()) {
            for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
                if (type.getFullyQualifiedName().filter(qualifiedName::equals).isPresent()) {
                    return Optional.of(type);
                }
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static TypeDeclaration<?> castTypeDeclaration(TypeDeclaration raw) {
        return (TypeDeclaration<?>) raw;
    }

    /**
     * Every enclosing method/constructor/initializer/lambda body that scopes
     * local names for {@code node} -- ALL of them, walking outward past each
     * one found rather than stopping at the nearest, so a lambda/anonymous
     * class's captured outer locals aren't missed (see
     * {@link #rejectShadowingCollision}).
     */
    private static List<Node> enclosingScopeRoots(Node node) {
        List<Node> roots = new ArrayList<>();
        Node current = node;
        Optional<Node> next = nextEnclosingScopeRoot(current);
        while (next.isPresent()) {
            roots.add(next.get());
            current = next.get();
            next = nextEnclosingScopeRoot(current);
        }
        return roots;
    }

    private static Optional<Node> nextEnclosingScopeRoot(Node from) {
        Optional<Node> parent = from.getParentNode();
        while (parent.isPresent()) {
            Node p = parent.get();
            if (p instanceof CallableDeclaration || p instanceof InitializerDeclaration || p instanceof LambdaExpr) {
                return Optional.of(p);
            }
            parent = p.getParentNode();
        }
        return Optional.empty();
    }

    private static ResolvedValueDeclaration tryResolve(NameExpr name, List<String> warnings, ProjectContext ctx,
                                                         CompilationUnit cu) {
        try {
            return name.resolve();
        } catch (RuntimeException e) {
            // The caller only invokes this for a NameExpr whose text ALREADY
            // equals oldName, so a resolution failure here isn't "this is
            // unrelated" noise (that's ruled out by resolving successfully
            // to something that ISN'T our field, which isn't a failure at
            // all) -- it's a genuine "couldn't tell if this was our field",
            // same as the sibling FieldAccessExpr loop above, so warn the
            // same way rather than silently leaving a possible real
            // reference unrenamed with no trace.
            warnings.add("Could not resolve '" + name + "' in " + fileOf(ctx, cu)
                    + " while checking whether it's a reference to the renamed field (left unchanged): "
                    + e.getMessage());
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
