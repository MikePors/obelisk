package dev.obelisk.core.refactor;

import dev.obelisk.guard.Check;
import dev.obelisk.guard.Guard;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
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
import java.util.IdentityHashMap;
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
 *    caller via {@code paramsFilter} ({@code --params} on the CLI) -- refuses
 *    to guess which overload if it's still ambiguous (see {@link #findMethod})
 *  - override propagation is downward only: {@code --class} must name the
 *    root declaration. If the specified method itself overrides something
 *    further up the hierarchy, obelisk refuses and names the root to target
 *    instead, rather than silently renaming only part of the family.
 */
public final class RenameMethodRefactor {

    private RenameMethodRefactor() {
    }

    public static RefactorResult run(ProjectContext ctx, String className, String oldName, String newName,
                                      String paramsFilter, boolean apply) {
        validateIdentifier(newName);

        List<String> warnings = new ArrayList<>();

        TypeDeclaration<?> targetClass = ClassFinder.findClass(ctx, className);
        MethodDeclaration targetMethod = findMethod(targetClass, oldName, paramsFilter, warnings);
        ResolvedMethodDeclaration resolvedTarget = resolveTarget(targetMethod, targetClass, oldName);
        String targetSignature = signatureOf(resolvedTarget);
        String ownerQualifiedName = resolvedTarget.declaringType().getQualifiedName();

        rejectNonRootTarget(resolvedTarget, oldName, targetClass);

        // Find every declaration elsewhere in the project that overrides this
        // one, so the whole family gets renamed together instead of leaving
        // subclasses out of sync with the interface/superclass they implement.
        List<MethodDeclaration> overrides = findOverrides(ctx, ownerQualifiedName, oldName, targetMethod,
                resolvedTarget, targetSignature, warnings);
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
            member.findAncestor(TypeDeclaration.class).ifPresent(owner -> {
                rejectDuplicateSignature(castTypeDeclaration(owner), newName, paramsSuffix, oldName);
            });
        }
        rejectNewNameAlreadyVisible(familyDeclarations, oldName, newName);
        rejectNewNameDeclaredBySubtype(ctx, ownerQualifiedName, familySignatures, oldName, newName);

        // Resolve every matching call site / method reference against the
        // ORIGINAL (unmodified) AST first, and defer every mutation (including
        // method references) to a single batch applied afterward. Renaming
        // anything up front would shadow resolution for other matches still
        // referencing the old name (e.g. a self-call inside the same class),
        // so nothing is mutated until this whole pass completes.
        List<MethodCallExpr> callsToRename = new ArrayList<>();
        // IDENTITY map: JavaParser's Node.equals is structural, so two
        // identical-looking calls would collide in a HashMap and one site's
        // repair would be applied to another (the same trap that made
        // rename-field over-qualify every reference).
        Map<MethodCallExpr, String> callsNeedingQualification = new IdentityHashMap<>();
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
                if (call.getScope().isEmpty() && isShadowedAfterRename(call, resolved, newName)) {
                    // An unqualified call whose new name the enclosing scope
                    // already means something by. That is a BINDING hazard,
                    // so it is REPAIRED by qualifying the call rather than
                    // refused -- `Util.report(x)` cannot be captured by an
                    // inherited or anonymous-class method the way a bare
                    // `report(x)` can. Only a static target is repairable;
                    // rejectShadowingCollision refuses the rest.
                    rejectShadowingCollision(call, resolved, newName, oldName);
                    callsNeedingQualification.put(call, resolved.declaringType().getQualifiedName());
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
        // REPAIR: qualify the calls a same-named member would have captured.
        for (Map.Entry<MethodCallExpr, String> entry : callsNeedingQualification.entrySet()) {
            qualifyShadowedCall(entry.getKey(), entry.getValue());
        }
        for (MethodReferenceExpr ref : refsToRename) {
            ref.setIdentifier(newName);
        }
        for (ImportDeclaration imp : staticImportsToRename) {
            // Always normalize to the declaring type's FQN, even if the import
            // originally reached the method through a subclass name.
            imp.setName(qualifiedName(ownerQualifiedName + "." + newName));
        }
        verifyQualifiedCalls(ctx, callsNeedingQualification, oldName, newName);

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
            throw new RefactorException(Check.INVALID_IDENTIFIER, "'" + name + "' is not a valid Java method name");
        }
    }

    /**
     * Refuses to rename into a name that would collide with another method
     * already declared directly on the same type with the same parameter
     * list -- that would produce two methods with identical signatures.
     */
    @Guard(Check.REJECT_DUPLICATE_SIGNATURE)
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
                    throw new RefactorException(Check.REJECT_DUPLICATE_SIGNATURE, "Cannot rename '" + oldName + "' to '" + newName + "': '"
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
    /**
     * Would this unqualified call be captured by something named {@code
     * newName} once the rename lands? Reports a fact; it refuses nothing, so
     * per the naming convention it is not a {@code reject*}.
     *
     * <p>Three ways that happens, all confirmed by repro: an ANONYMOUS class
     * body between the call and its enclosing type declaring its own {@code
     * newName}; an INHERITED method of that name visible on the enclosing
     * type (Java resolves the enclosing class hierarchy before static
     * imports); or the enclosing type declaring one directly with a matching
     * parameter list.
     */
    private static boolean isShadowedAfterRename(MethodCallExpr call, ResolvedMethodDeclaration resolvedCall,
                                                   String newName) {
        for (Node up = call; up != null; up = up.getParentNode().orElse(null)) {
            if (up instanceof TypeDeclaration) {
                break;
            }
            if (up instanceof com.github.javaparser.ast.expr.ObjectCreationExpr creation
                    && creation.getAnonymousClassBody()
                            .map(body -> body.stream().anyMatch(m -> m instanceof MethodDeclaration method
                                    && method.getNameAsString().equals(newName)))
                            .orElse(false)) {
                return true;
            }
        }
        Optional<TypeDeclaration<?>> ancestor = call.findAncestor(TypeDeclaration.class)
                .map(RenameMethodRefactor::castTypeDeclaration);
        if (ancestor.isEmpty()) {
            return false;
        }
        TypeDeclaration<?> enclosing = ancestor.get();
        try {
            if (NameBindingChecker.visibleMethodOn(enclosing.resolve(), enclosing, newName).isPresent()) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // Fall through to the narrower declared-methods check.
        }
        List<ResolvedType> callParams;
        try {
            callParams = resolvedCall.formalParameterTypes();
        } catch (RuntimeException e) {
            return false;
        }
        for (MethodDeclaration candidate : enclosing.getMethods()) {
            if (!candidate.getNameAsString().equals(newName)) {
                continue;
            }
            try {
                if (paramsMatch(candidate.resolve().formalParameterTypes(), callParams)) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Unresolvable candidate -- not this call's problem.
            }
        }
        return false;
    }

    /**
     * Qualifies a call that would otherwise be captured, so it keeps
     * reaching the method it reaches today.
     *
     * <p>A freshly CONSTRUCTED scope node, not a clone -- {@code
     * LexicalPreservingPrinter} prints stale pre-mutation text for cloned
     * nodes (documented at length on {@code InlineMethodRefactor.planInline}).
     */
    private static void qualifyShadowedCall(MethodCallExpr call, String declaringTypeQualifiedName) {
        // The FULLY-qualified name, not the simple one. A call reached via a
        // static member import has no type import for its owner, so `Util.x`
        // does not resolve there at all -- caught by verifyQualifiedCalls on
        // the first repair it guarded, before anything was written.
        String[] parts = declaringTypeQualifiedName.split("\\.");
        Expression scope = new NameExpr(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            scope = new FieldAccessExpr(scope, parts[i]);
        }
        call.setScope(scope);
    }

    /**
     * Proves each repaired call's new qualifier names a type that really
     * does declare the renamed method.
     *
     * <p><b>Why not the stronger check.</b> The natural verification --
     * re-resolve the rewritten call and confirm it still reaches the same
     * method, as rename-field's repair does -- is not available here, and
     * the reason is worth stating precisely because the obvious explanation
     * is wrong.
     *
     * <p>It is NOT that JavaParser dislikes a package-qualified scope.
     * Qualifying with the simple name and adding a type import was tried,
     * and failed identically. The actual blocker is that {@code
     * JavaParserTypeSolver} reads target types <b>from disk</b>, and at
     * verification time nothing has been written yet -- so resolving {@code
     * Util.report(x)} loads the on-disk {@code Util}, which still declares
     * {@code log}. Confirmed by probe.
     *
     * <p>That is why rename-field's repair CAN verify end to end and this
     * one cannot: {@code this.count} resolves within the single compilation
     * unit being mutated, which is in memory. A repair whose target lives in
     * a DIFFERENT file is unverifiable by resolution, whatever the qualifier
     * looks like.
     *
     * <p>So this verifies the half that is checkable: the qualifier resolves
     * to a real type, and that type declares a method of the new name. The
     * other half -- that the emitted call compiles and reaches it -- is
     * covered by tests, which run the real javac via
     * {@code TestProject.assertCompiles()} and compare program output before
     * and after. That is weaker than the in-process verification the other
     * repairs get, and is recorded here rather than glossed over.
     */
    @Guard(Check.VERIFY_QUALIFIED_CALLS)
    private static void verifyQualifiedCalls(ProjectContext ctx, Map<MethodCallExpr, String> repaired,
                                               String oldName, String newName) {
        for (Map.Entry<MethodCallExpr, String> entry : repaired.entrySet()) {
            String qualifiedType = entry.getValue();
            // Against the IN-MEMORY units, not ctx.typeSolver(): the solver
            // reads source from disk, which at this point still holds the
            // pre-rename text, so it would report the method missing every
            // time. Caught by this check failing on its own first repair.
            Optional<TypeDeclaration<?>> declaration = ctx.unitsByFile().values().stream()
                    .flatMap(cu -> cu.findAll(TypeDeclaration.class).stream())
                    .filter(t -> t.getFullyQualifiedName().filter(qualifiedType::equals).isPresent())
                    .map(RenameMethodRefactor::castTypeDeclaration)
                    .findFirst();
            if (declaration.isEmpty()) {
                throw new RefactorException(Check.VERIFY_QUALIFIED_CALLS, "Refusing to rename '" + oldName
                        + "' to '" + newName + "': the call at "
                        + entry.getKey().getBegin().map(Object::toString).orElse("?") + " was qualified with '"
                        + qualifiedType + "', which is not a type in this project. Nothing has been written.");
            }
            boolean declaresIt = declaration.get().getMethods().stream()
                    .anyMatch(m -> m.getNameAsString().equals(newName));
            if (!declaresIt) {
                throw new RefactorException(Check.VERIFY_QUALIFIED_CALLS, "Refusing to rename '" + oldName
                        + "' to '" + newName + "': the call at "
                        + entry.getKey().getBegin().map(Object::toString).orElse("?") + " was qualified with '"
                        + qualifiedType + "', which declares no method named '" + newName
                        + "'. Nothing has been written.");
            }
        }
    }

    /**
     * Refuses a shadowed call that CANNOT be repaired by qualifying it.
     *
     * <p>A STATIC target is always qualifiable with its declaring type, and
     * is repaired rather than refused. An INSTANCE target is not: the right
     * receiver depends on whether the target is declared on the enclosing
     * type ({@code this.}), inherited and now hidden ({@code super.}), or
     * reached from an inner class ({@code Outer.this.}) -- and {@code super}
     * does not work for statics or through interfaces at all. Rather than
     * guess, refuse, exactly as rename-field refuses hierarchy hiding for
     * the same reason.
     */
    @Guard(Check.REJECT_SHADOWING_COLLISION)
    private static void rejectShadowingCollision(MethodCallExpr call, ResolvedMethodDeclaration resolvedCall,
                                                   String newName, String oldName) {
        if (resolvedCall.isStatic()) {
            return;
        }
        throw new RefactorException(Check.REJECT_SHADOWING_COLLISION, "Cannot rename '" + oldName + "' to '"
                + newName + "': the unqualified call at " + call.getBegin().map(Object::toString).orElse("?")
                + " would be captured by something already named '" + newName + "' in that scope, and it calls an "
                + "INSTANCE method, which this refactor cannot safely qualify -- the correct receiver depends on "
                + "whether the target is declared, inherited, or reached from an inner class. Not supported in "
                + "this version.");
    }

    /**
     * Refuses when a method named {@code newName} is ALREADY visible --
     * declared or inherited -- on any type owning a member of the rename
     * family.
     *
     * <p>{@link #rejectDuplicateSignature} only refuses an EXACT signature
     * clash, on the stated theory that "a colliding NAME with a DIFFERENT
     * parameter list is just a legal new overload... Java resolves it
     * correctly by arity/type, no ambiguity." That is false, and was the
     * most severe finding of the review: signature EQUALITY is being used as
     * a stand-in for overload APPLICABILITY (JLS 15.12.2), the same
     * syntactic-proxy-for-semantics mistake this codebase kept making
     * elsewhere.
     *
     * <p>Confirmed by repro, and silent: {@code class A { String
     * foo(Object o); String bar(String s); }} with a call {@code
     * a.foo("hello")}. Renaming {@code foo} to {@code bar} rewrites the call
     * to {@code a.bar("hello")}, which compiles and now reaches a COMPLETELY
     * DIFFERENT method body, because {@code bar(String)} is more specific
     * for a {@code String} argument than {@code bar(Object)}.
     *
     * <p>Also catches the downward half of the accidental-override hazard:
     * renaming {@code Child.hello} to {@code greet} when {@code Base}
     * declares {@code greet} makes it an override, so a call through a
     * {@code Base}-typed reference silently dispatches to {@code Child}'s
     * body instead.
     *
     * <p>Deliberately refuses on a bare NAME match rather than modelling
     * applicability, which would mean reimplementing overload resolution.
     * Over-refusal is the direction this codebase always chooses.
     */
    @Guard(Check.REJECT_NEW_NAME_ALREADY_VISIBLE)
    private static void rejectNewNameAlreadyVisible(List<MethodDeclaration> familyDeclarations,
                                                      String oldName, String newName) {
        for (MethodDeclaration member : familyDeclarations) {
            ResolvedReferenceTypeDeclaration owner;
            try {
                owner = member.resolve().declaringType();
            } catch (RuntimeException e) {
                continue;
            }
            // Every family member is named oldName while this searches for
            // newName, so no family signature can match -- no exclusion needed.
            NameBindingChecker.visibleMethodOn(owner, member, newName).ifPresent(bound -> {
                    throw new RefactorException(Check.REJECT_NEW_NAME_ALREADY_VISIBLE, "Cannot rename '" + oldName + "' to '" + newName + "': "
                            + bound + " is already visible on '" + owner.getQualifiedName() + "'. Renaming would "
                            + "either make a call site silently select that other method instead (overload "
                            + "resolution picks the most specific applicable one, not the one you meant), or turn "
                            + "this declaration into an accidental override of it. Not supported in this version.");
            });
        }
    }

    /**
     * The upward half of the accidental-override hazard: a SUBTYPE somewhere
     * in the project already declares {@code newName}.
     *
     * <p>{@link #rejectNewNameAlreadyVisible} walks each owner's ANCESTORS,
     * which cannot see this. Confirmed by repro: {@code Base.hello()}
     * renamed to {@code greet} while {@code Child extends Base} already
     * declares {@code greet()}. The call {@code b.hello()} becomes {@code
     * b.greet()} and silently dispatches to {@code Child.greet} -- Child's
     * unrelated method became an override of Base's. Compiles cleanly.
     */
    @Guard(Check.REJECT_NEW_NAME_DECLARED_BY_SUBTYPE)
    private static void rejectNewNameDeclaredBySubtype(ProjectContext ctx, String ownerQualifiedName,
                                                         Set<String> familySignatures, String oldName,
                                                         String newName) {
        // Scans every MethodDeclaration directly rather than walking named
        // TypeDeclarations, for exactly the reason findOverrides documents:
        // an ANONYMOUS class body hangs off an ObjectCreationExpr and an ENUM
        // CONSTANT body off an EnumConstantDeclaration, neither of which is a
        // TypeDeclaration -- so a TypeDeclaration-based sweep silently misses
        // both. Confirmed by repro: `Base b = new Base() { String greet() {
        // ... } };` let `Base.hello` be renamed to `greet`, and the call
        // `b.hello()` silently started dispatching to the anonymous class's
        // method. `candidate.resolve()` works from AST position regardless of
        // whether the enclosing type has a name.
        for (CompilationUnit cu : ctx.unitsByFile().values()) {
            for (MethodDeclaration candidate : cu.findAll(MethodDeclaration.class)) {
                if (!candidate.getNameAsString().equals(newName)) {
                    continue;
                }
                try {
                    ResolvedMethodDeclaration resolvedCandidate = candidate.resolve();
                    if (familySignatures.contains(resolvedCandidate.getQualifiedSignature())) {
                        continue;
                    }
                    ResolvedReferenceTypeDeclaration declaringType = resolvedCandidate.declaringType();
                    // Via NameBindingChecker.ancestorsOf, not getAllAncestors():
                    // the latter returns an empty list for a LOCAL class, so a
                    // local subclass declaring the new name slipped through
                    // (confirmed by repro) even after this scan was broadened
                    // to reach anonymous and enum-constant bodies.
                    boolean isSubtype = NameBindingChecker.ancestorsOf(declaringType, candidate).stream()
                            .anyMatch(a -> a.getQualifiedName().equals(ownerQualifiedName));
                    if (!isSubtype) {
                        continue;
                    }
                    throw new RefactorException(Check.REJECT_NEW_NAME_DECLARED_BY_SUBTYPE, "Cannot rename '" + oldName + "' to '" + newName + "': '"
                            + declaringType.getQualifiedName() + "' is a subtype of '" + ownerQualifiedName
                            + "' and already declares '" + resolvedCandidate.getQualifiedSignature() + "'. Renaming "
                            + "would silently turn that unrelated method into an override, so calls made "
                            + "through a supertype reference would start dispatching to it. Not supported "
                            + "in this version.");
                } catch (RefactorException e) {
                    throw e;
                } catch (RuntimeException e) {
                    // Unresolvable candidate -- can't confirm it's a subtype's method.
                }
            }
        }
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
    @Guard(Check.REJECT_NON_ROOT_TARGET)
    private static void rejectNonRootTarget(ResolvedMethodDeclaration resolvedTarget, String oldName,
                                             TypeDeclaration<?> targetClass) {
        List<ResolvedType> targetParams = resolvedTarget.formalParameterTypes();
        // ancestorsOf, not getAllAncestors(): a LOCAL class reports no
        // ancestors, so an override declared by one would not be detected
        // and only part of the family would be renamed.
        for (ResolvedReferenceType ancestor
                : NameBindingChecker.ancestorsOf(resolvedTarget.declaringType(), targetClass)) {
            for (MethodUsage ancestorMethod : ancestor.getDeclaredMethods()) {
                if (ancestorMethod.getName().equals(oldName)
                        && overrideParamsMatch(ancestor, ancestorMethod, targetParams)) {
                    throw new RefactorException(Check.REJECT_NON_ROOT_TARGET, "'" + targetClass.getNameAsString() + "." + oldName
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
                                                           String targetSignature, List<String> warnings) {
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
                    for (ResolvedReferenceType ancestor : ancestorsOf(declaringType, candidate)) {
                        if (!ancestor.getQualifiedName().equals(ownerQualifiedName)) {
                            continue;
                        }
                        for (MethodUsage ancestorMethod : ancestor.getDeclaredMethods()) {
                            // getDeclaredMethods() returns every method named
                            // oldName on the ancestor -- if the root class has
                            // multiple overloads (--params picked one of them),
                            // this must also confirm ancestorMethod IS the
                            // specific overload we're renaming (resolvedTarget),
                            // not just any same-named one the candidate happens
                            // to match. Without this check, renaming ONE
                            // overload would sweep in a subclass's override of a
                            // completely different, unselected overload.
                            if (ancestorMethod.getName().equals(oldName)
                                    && ancestorMethod.getDeclaration().getQualifiedSignature().equals(targetSignature)
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
     * Wraps {@link ResolvedReferenceTypeDeclaration#getAllAncestors()} with a
     * fallback for local classes (a class declared as a statement inside a
     * method body): confirmed empirically that this library's ancestor
     * resolution -- both the transitive {@code getAllAncestors()} and the
     * direct, non-transitive {@code getAncestors()} -- returns an empty list
     * for a local class even when it has an explicit {@code extends}/
     * {@code implements} clause naming a real supertype. When that happens
     * (empty result, but the AST node genuinely declares a supertype), this
     * resolves the {@code extends}/{@code implements} type references
     * directly from the AST instead and unions in their own ancestors (which
     * resolve correctly, since the supertype itself is an ordinary named
     * type, not a local one -- only ancestor computation *starting from* a
     * local class as the query root is affected).
     */
    private static List<ResolvedReferenceType> ancestorsOf(ResolvedReferenceTypeDeclaration declaringType,
                                                             MethodDeclaration candidate) {
        List<ResolvedReferenceType> ancestors = declaringType.getAllAncestors();
        if (!ancestors.isEmpty()) {
            return ancestors;
        }
        Optional<ClassOrInterfaceDeclaration> enclosing = candidate.findAncestor(ClassOrInterfaceDeclaration.class);
        if (enclosing.isEmpty()) {
            return ancestors;
        }
        List<ClassOrInterfaceType> supertypeRefs = new ArrayList<>();
        supertypeRefs.addAll(enclosing.get().getExtendedTypes());
        supertypeRefs.addAll(enclosing.get().getImplementedTypes());
        List<ResolvedReferenceType> manual = new ArrayList<>();
        for (ClassOrInterfaceType ref : supertypeRefs) {
            try {
                ResolvedReferenceType resolved = ref.resolve().asReferenceType();
                manual.add(resolved);
                manual.addAll(resolved.getAllAncestors());
            } catch (RuntimeException ignored) {
                // best-effort -- an unresolvable supertype reference just
                // isn't included, same as the library's own behavior
            }
        }
        return manual;
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
     * Finds the method to rename, disambiguating overloads via
     * {@code paramsFilter} (the CLI's {@code --params}, e.g. {@code "String,int"})
     * when more than one method on the class shares {@code methodName}.
     * {@code paramsFilter == null} means the caller didn't supply one, which
     * is only acceptable when there's exactly one method with that name.
     */
    private static MethodDeclaration findMethod(TypeDeclaration<?> targetClass, String methodName,
                                                  String paramsFilter, List<String> warnings) {
        List<MethodDeclaration> matches = targetClass.getMethods().stream()
                .filter(m -> m.getNameAsString().equals(methodName))
                .toList();
        if (matches.isEmpty()) {
            throw new RefactorException(Check.METHOD_NOT_FOUND, 
                    "No method named '" + methodName + "' declared directly on '" + targetClass.getNameAsString() + "'");
        }
        if (matches.size() == 1) {
            MethodDeclaration only = matches.get(0);
            if (paramsFilter != null) {
                // Not ambiguous, so --params isn't needed to pick a method --
                // but still worth validating: a mismatched value here is most
                // likely a typo or a stale copy-paste, and the caller should
                // know it had no effect rather than being renamed silently.
                List<String> wanted = parseParamsFilter(paramsFilter);
                try {
                    if (!paramsFilterMatches(only.resolve().formalParameterTypes(), wanted)) {
                        warnings.add("--params '" + paramsFilter + "' does not match the only '" + methodName
                                + "' method on '" + targetClass.getNameAsString()
                                + "' -- proceeding anyway since the name isn't ambiguous.");
                    }
                } catch (RuntimeException ignored) {
                    // Couldn't resolve it to verify -- not fatal, the method itself was found fine.
                }
            }
            return only;
        }
        if (paramsFilter == null) {
            throw new RefactorException(Check.METHOD_NAME_OVERLOADED, "Method name '" + methodName + "' is overloaded (" + matches.size()
                    + " overloads) on '" + targetClass.getNameAsString() + "'. Disambiguate with --params, "
                    + "e.g.: " + describeOverloads(matches, methodName) + ".");
        }

        List<String> wanted = parseParamsFilter(paramsFilter);
        List<MethodDeclaration> filtered = new ArrayList<>();
        for (MethodDeclaration candidate : matches) {
            try {
                if (paramsFilterMatches(candidate.resolve().formalParameterTypes(), wanted)) {
                    filtered.add(candidate);
                }
            } catch (RuntimeException e) {
                warnings.add("Could not resolve overload '" + candidate.getNameAsString() + "' on '"
                        + targetClass.getNameAsString() + "' while matching --params (skipped -- it may have "
                        + "been the intended match): " + e.getMessage());
            }
        }
        if (filtered.isEmpty()) {
            throw new RefactorException(Check.NO_OVERLOAD_MATCHES_PARAMS, "No overload of '" + methodName + "' on '" + targetClass.getNameAsString()
                    + "' matches --params '" + paramsFilter + "'. Available overloads: "
                    + describeOverloads(matches, methodName) + ".");
        }
        if (filtered.size() > 1) {
            throw new RefactorException(Check.PARAMS_FILTER_AMBIGUOUS, "--params '" + paramsFilter + "' matches more than one overload of '"
                    + methodName + "' on '" + targetClass.getNameAsString() + "': "
                    + describeOverloads(filtered, methodName) + ". Use fully-qualified type names to disambiguate.");
        }
        return filtered.get(0);
    }

    /**
     * Splits a {@code --params} value on commas. A blank/empty overall value
     * means "zero-arg overload". Anything else with an empty segment (a
     * leading/trailing/doubled comma, e.g. {@code ","} or {@code "int,"}) is
     * rejected outright rather than silently misinterpreted -- {@code ","}
     * previously fell through {@code String.split} dropping trailing empty
     * strings and was misread as the zero-arg selector.
     */
    private static List<String> parseParamsFilter(String paramsFilter) {
        if (paramsFilter.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : paramsFilter.split(",", -1)) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                throw new RefactorException(Check.INVALID_PARAMS_FILTER, "Invalid --params '" + paramsFilter
                        + "': empty parameter type (use --params \"\" for a zero-arg overload, "
                        + "not a leading/trailing/doubled comma).");
            }
            tokens.add(trimmed);
        }
        return tokens;
    }

    /**
     * Matches each wanted token against a parameter's fully-qualified type
     * name, or its simple name with any generic type arguments stripped off
     * first (so {@code List} matches {@code java.util.List<java.lang.String>}
     * -- naively taking everything after the last '.' without stripping
     * generics first would land inside the type argument instead, e.g.
     * yielding {@code String>} for that same type).
     */
    private static boolean paramsFilterMatches(List<ResolvedType> candidateParams, List<String> wanted) {
        if (candidateParams.size() != wanted.size()) {
            return false;
        }
        for (int i = 0; i < wanted.size(); i++) {
            String describe = candidateParams.get(i).describe();
            String base = baseTypeName(describe);
            String simple = simpleNameOf(base);
            String token = wanted.get(i);
            if (!token.equals(describe) && !token.equals(base) && !token.equals(simple)) {
                return false;
            }
        }
        return true;
    }

    /** Strips any generic type arguments, e.g. {@code "java.util.List<java.lang.String>"} -> {@code "java.util.List"}. */
    private static String baseTypeName(String describe) {
        int genericStart = describe.indexOf('<');
        return genericStart < 0 ? describe : describe.substring(0, genericStart);
    }

    private static String simpleNameOf(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot < 0 ? qualifiedName : qualifiedName.substring(lastDot + 1);
    }

    /**
     * Lists each candidate's signature using the exact same parameter-type
     * strings {@link #paramsFilterMatches} compares against (each resolved
     * parameter's {@code describe()}), so a value copied from this message is
     * guaranteed to match -- {@code getQualifiedSignature()} was used
     * previously, but it renders varargs as {@code String...} while the
     * matcher only ever sees (and accepts) {@code String[]}.
     */
    private static String describeOverloads(List<MethodDeclaration> methods, String methodName) {
        List<String> descriptions = new ArrayList<>();
        for (MethodDeclaration m : methods) {
            try {
                List<String> paramDescriptions = new ArrayList<>();
                for (ResolvedType paramType : m.resolve().formalParameterTypes()) {
                    paramDescriptions.add(paramType.describe());
                }
                descriptions.add(methodName + "(" + String.join(", ", paramDescriptions) + ")");
            } catch (RuntimeException e) {
                descriptions.add(methodName + "(<unresolvable>)");
            }
        }
        return String.join(", ", descriptions);
    }

    private static ResolvedMethodDeclaration resolveTarget(MethodDeclaration method, TypeDeclaration<?> owner,
                                                             String originalName) {
        try {
            return method.resolve();
        } catch (RuntimeException e) {
            throw new RefactorException(Check.TARGET_METHOD_UNRESOLVABLE, "Could not resolve target method '" + originalName + "' on '"
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
            throw new RefactorException(Check.WRITE_FAILED, "Failed to write changes: " + e.getMessage(), e);
        }
    }
}
