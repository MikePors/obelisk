package dev.obelisk.core.refactor;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserParameterDeclaration;
import dev.obelisk.core.DiffUtil;
import dev.obelisk.core.ProjectContext;
import dev.obelisk.core.RefactorException;

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
 * Inlines a method declared directly on a given class/interface/enum/record:
 * replaces every call site obelisk can resolve back to it with the method's
 * own body (substituting parameters with the call site's actual argument
 * expressions), removes any now-dangling static import of it, and finally
 * removes the method declaration itself.
 *
 * V1 known limitations (fail loudly rather than guess):
 *  - only supports a method whose ENTIRE body is a single {@code return
 *    <expr>;} statement -- inlining a multi-statement or void method would
 *    require splicing a whole statement sequence into the call site's
 *    surrounding statement, which (per the same LexicalPreservingPrinter
 *    limitation documented on {@link ExtractVariableRefactor}) this
 *    library's printer doesn't reliably format; a single expression can
 *    instead be substituted directly into the call's own AST position via
 *    node replacement, which prints correctly
 *  - beyond being a single {@code return <expr>;}, that expression must be
 *    a PURE expression over its own parameters: no method calls, no
 *    lambdas or method references, no assignments or {@code ++}/{@code --}
 *    anywhere in it (see {@link #rejectMutatingOrCallExpressions} and
 *    {@link #rejectDeferredEvaluationConstructs}). This is deliberately a
 *    much narrower, blunter restriction than a long list of individually-
 *    patched special cases: three separate rounds of review each found
 *    real, silently-wrong-output bugs in a version of this refactor that
 *    tried to allow method calls and lambdas in the body with case-by-case
 *    safety checks (an argument's evaluation reordering relative to the
 *    body's own side effects; a lambda in the body capturing a substituted
 *    argument by name instead of the thing it was bound to; a lambda
 *    deferring evaluation of a field read to whenever it actually runs
 *    instead of the original call's eager, once-only evaluation) -- pure
 *    textual substitution has no concept of capture-avoiding substitution
 *    or of verifying evaluation-order/timing is preserved, so rather than
 *    keep discovering new instances of those two bug categories, the
 *    surface that needs any such reasoning at all was removed. Combined
 *    with {@link #rejectFreeReferences} (no fields except one reached
 *    through a parameter whose FIELD *and* DECLARING TYPE -- and every type
 *    enclosing it -- are all public, per JLS 6.6.1's requirement that both
 *    the member and its declaring type be accessible; a public field on a
 *    package-private class was confirmed via repro to still break the
 *    build even though the call site never names the inaccessible type
 *    directly, no type references), what remains
 *    inlinable is parameters, literals, and PRIMITIVE-typed operators over
 *    them (arithmetic, comparisons, ternaries -- see {@link
 *    #rejectNonPrimitiveOperands}), plus field/array reads through a
 *    parameter -- narrow, but free of side effects, evaluation-order/
 *    capture risk, AND the two implicit conversions (string-concatenation
 *    {@code toString()}, unboxing) that have no AST node of their own and
 *    were found (in review) to still leak through a version of this
 *    restriction that only banned explicit {@code MethodCallExpr}s. (A
 *    {@code switch} expression in the return statement is refused too, but
 *    only because JavaParser's resolver can't compute its type at all here,
 *    not because it's inherently unsafe -- see {@link
 *    #rejectReturnTypeConversion}.)
 *  - refuses if the return expression doesn't reference any of the
 *    method's own parameters, or if a specific call's arguments are ALL
 *    compile-time CONSTANT EXPRESSIONS (JLS 15.29 -- literals, but also
 *    unary/binary/cast compositions of them, and a reference to a
 *    {@code final} variable/field whose OWN initializer is one, i.e. a
 *    "constant variable"; see {@link #isConstantExpression}, deliberately
 *    NOT a mere {@code instanceof LiteralExpr} check, which was confirmed
 *    via repro to miss all of those and let through the single most
 *    dangerous case found across every round of review: a {@code static
 *    final} field initialized from an inlined call compiled cleanly but
 *    silently changed, in the compiled bytecode, from a real field read to
 *    a true compile-time constant baked into every OTHER class's bytecode
 *    -- an ABI change invisible in the source diff and undetected by
 *    javac) -- either way, the substituted result is IDENTICAL every time
 *    and composed purely of constants/primitive-operators, which Java's
 *    compiler treats as a genuine compile-time constant expression even
 *    though the original method call never was -- also confirmed via repro
 *    to change loop/branch reachability analysis ({@code while
 *    (Flags.enabled())} compiling to an unconditional {@code while (true)})
 *  - for a call with MORE THAN ONE parameter, requires every argument to be
 *    in the narrow side-effect-free whitelist (a LOCAL/PARAMETER name --
 *    NOT a field, since reading a field can trigger that class's static
 *    initializer, see {@link #isSideEffectFree} -- a literal, or {@code
 *    this}) regardless of that specific parameter's own reference pattern
 *    -- confirmed via repro that a parameter referenced exactly once, in
 *    order (and therefore normally exempt from this check) still let an
 *    assignment in a SIBLING argument's own expression land between two
 *    reads of a DIFFERENT, duplicated parameter, changing the result; a
 *    single-parameter call has no sibling argument to interfere with, so
 *    keeps the narrower, more permissive check
 *  - refuses a {@code synchronized} method (the monitor acquisition is a
 *    declaration-level effect this refactor's body-only analysis can't see)
 *    or one with a {@code throws} clause (a call site's {@code catch} for a
 *    declared-but-never-actually-thrown exception would become unreachable)
 *  - only {@code static} or {@code private} methods -- inlining any other
 *    instance method is refused outright, even if it happens not to be
 *    overridden today: an instance method IS subject to virtual dispatch,
 *    and a call site typed as an ANCESTOR class/interface would still
 *    statically resolve to that ancestor's signature even when the actual
 *    call dispatches, at runtime, to THIS specific override -- such a call
 *    site wouldn't even be found by this refactor's exact-signature match,
 *    so inlining could silently change behavior for it. {@code static} and
 *    {@code private} methods are never subject to virtual dispatch at all
 *    (statics are resolved by qualifier type, not polymorphically; privates
 *    can't be overridden or inherited), so they're the only safe case here.
 *  - refuses a self-recursive method (a return expression that calls
 *    itself), a generic method (its own type parameters), and a varargs
 *    method
 *  - refuses if any call's argument substitution wouldn't preserve the
 *    original call's evaluation semantics for some parameter (evaluated
 *    exactly once, unconditionally, in declaration order) AND that
 *    argument isn't obviously side-effect-free -- covers duplicated,
 *    dropped, reordered, and conditionally/lazily (re-)evaluated arguments,
 *    any of which could silently change behavior if substituted naively
 *  - refuses a body that references anything besides its own parameters --
 *    a field or constant (by unqualified name), a sibling method called
 *    without a qualifier, an explicit {@code this}/{@code super} reference,
 *    or any type reference at all ({@code new}, a cast, {@code instanceof},
 *    {@code .class}, a generic type argument) -- spliced into a call site
 *    elsewhere, any of these would resolve against the CALL SITE's scope/
 *    imports/receiver instead of the method's own declaring class
 *  - refuses if the method's declared return type, or a parameter's
 *    declared type, requires an implicit conversion (primitive widening,
 *    or boxing/unboxing in EITHER direction) from its return expression's/
 *    the call's argument's own type -- substituting the bare, un-converted
 *    expression can silently change arithmetic results or which overload
 *    gets picked downstream. An earlier version of this check tried to
 *    tolerate plain primitive/wrapper boxing specifically on the parameter
 *    side (return-side boxing was always refused) on the theory that it
 *    was only unsafe if it escaped through the return value -- confirmed
 *    via repro that it is NOT safe in general, since a parameter's value
 *    passing straight through to the result (the common {@code return x;}
 *    shape) makes "escapes through the return value" the COMMON case, not
 *    a rare corner -- so both sides now require an exact type match,
 *    modulo only a generic-inference wildcard-capture artifact (e.g. a
 *    lambda argument in a stream pipeline) being unwrapped to its bound
 *    first, since that's a resolver-description quirk for the SAME type,
 *    not an actual conversion
 *  - refuses a body that writes to one of its own parameters (an
 *    assignment target, or the operand of {@code ++}/{@code --}) --
 *    substituting the call's argument expression into that write position
 *    would either mutate the caller's own variable or produce illegal Java
 *  - for a non-static (private) method, refuses any call site with an
 *    explicit receiver other than a bare {@code this} -- inlining would
 *    rebind the body's own unqualified references to the CALL SITE's
 *    instance instead of the original receiver. For a {@code static}
 *    method, refuses any call site qualified by something other than a
 *    pure class name (e.g. {@code expr.staticMethod()}) -- Java still
 *    evaluates that qualifier expression and discards the result, which
 *    deleting the whole call would silently drop
 *  - refuses a call used as a bare expression statement (result discarded),
 *    in a {@code for}-loop's own initialization/update clause, or as the
 *    discarded arm of an ordinary {@code switch} STATEMENT -- the
 *    substituted value expression generally isn't legal Java in any of
 *    those positions (an expression-bodied lambda's implicit body, and a
 *    {@code switch} EXPRESSION's arrow-arm value -- both of which look
 *    similar to a discarded statement in JavaParser's AST but aren't one --
 *    are correctly distinguished and allowed)
 *  - refuses a call nested inside another call to the same method (in its
 *    own arguments) -- not supported in this version
 *  - refuses outright (rather than partially inlining) if ANY call site, or
 *    any method reference ({@code Foo::method}), can't be safely inlined,
 *    or can't even be resolved enough to tell -- an all-or-nothing
 *    operation, since removing the declaration afterward is only safe once
 *    every use is confirmed gone WITHIN THE PROJECT THIS RUN LOADED (a
 *    single Maven module's own {@code src/main/java}/{@code src/test/java});
 *    a {@code public static} method called only from a SIBLING module isn't
 *    seen by this scan at all, so removing it would break that sibling's
 *    build -- loudly (a compile error), not silently, but still worth
 *    knowing this refactor isn't reactor-aware
 *  - refuses to inline a method whose declaring class has any static
 *    initialization effect of its own (a {@code static} initializer block,
 *    a {@code static} field whose initializer isn't itself a compile-time
 *    constant, or simply being an enum) -- invoking a method on a class
 *    triggers that class's one-time {@code <clinit>} (JLS 12.4.1) if it
 *    hasn't run yet; deleting every call site removes the one thing that
 *    was still guaranteeing that happens, an effect (registration side
 *    effects, lazy setup, even an {@code ExceptionInInitializerError}) this
 *    refactor's body-only analysis has no way to see (see {@link
 *    #rejectDeclaringTypeStaticInitializationEffect}). Checked on the
 *    declaring type AND every ANCESTOR whose {@code <clinit>} its own
 *    initialization cascades into -- its superclass chain, plus any
 *    superinterface DECLARING A DEFAULT METHOD (JLS 12.4.2 step 7);
 *    confirmed via repro that checking only the declaring type's own AST
 *    members missed a {@code static} block inherited from a superclass
 *    entirely. An ancestor with no source to inspect is refused rather than
 *    assumed inert, except {@code java.lang.Object}
 *  - refuses a call whose ARGUMENT is a lambda or method reference -- a
 *    POLY EXPRESSION, whose type is a property of the context it sits in
 *    rather than of the expression itself (JLS 15.27.3/15.13.2), so the
 *    parameter-type-conversion check above can't see anything wrong with it
 *    ({@code calculateResolvedType()} returns the target type, i.e. the
 *    parameter's own declared type, making that check pass by
 *    construction). Confirmed via repro that {@code Object o =
 *    Util.wrap(() -> ...)} inlined to {@code Object o = (() -> ...)},
 *    reported success, and left the project unable to compile ("Object is
 *    not a functional interface") -- and in an overload set the same
 *    substitution can silently select a DIFFERENT overload instead of
 *    failing loudly
 *  - the "every argument is a compile-time constant expression" call-site
 *    check above (and the JLS 15.29 definition itself) also covers the
 *    conditional operator {@code ? :} when its condition and both branches
 *    are all constant, and only actually examines the arguments bound to
 *    parameters the return expression REFERENCES -- an argument bound to an
 *    unreferenced parameter contributes nothing to the substituted result,
 *    so a non-constant "decoy" argument there can't stop the OTHER,
 *    referenced arguments from still promoting the result to a constant
 *  - the side-effect-free argument whitelist (used when a parameter's
 *    reference pattern alone isn't enough to prove substitution-safety)
 *    excludes both fields AND enum constants -- reading either can trigger
 *    its declaring class/enum's static initializer, the same hazard the
 *    static-initialization-effect check above exists to catch, just via an
 *    ARGUMENT instead of the invoked method's own declaring class
 *  - the substituted expression (and each substituted argument, embedded
 *    into whatever operator context surrounds its parameter's occurrence)
 *    is always wrapped in parentheses unless it's one of a few
 *    obviously-atomic kinds (a literal, a name, a method call, a field
 *    access, object creation, array access, or {@code this}) -- guarantees
 *    correct operator precedence without needing to reason about it, at
 *    the cost of some redundant parens
 *  - a static import of the method is only removed if no OTHER overload of
 *    the same name remains on the declaring class (a single-type static
 *    import covers every overload of that name)
 */
public final class InlineMethodRefactor {

    private InlineMethodRefactor() {
    }

    public static RefactorResult run(ProjectContext ctx, String className, String methodName, String paramsFilter,
                                      boolean apply) {
        List<String> warnings = new ArrayList<>();

        TypeDeclaration<?> targetClass = ClassFinder.findClass(ctx, className);
        MethodDeclaration targetMethod = findMethod(targetClass, methodName, paramsFilter);
        ResolvedMethodDeclaration resolvedTarget = resolveTarget(targetMethod, targetClass, methodName);
        String targetSignature = resolvedTarget.getQualifiedSignature();
        String ownerQualifiedName = resolvedTarget.declaringType().getQualifiedName();

        rejectVirtualDispatchRisk(targetMethod);
        rejectUnsupportedShape(targetMethod);
        rejectSynchronized(targetMethod);
        rejectThrowsClause(targetMethod);
        rejectDeclaringTypeStaticInitializationEffect(targetClass, targetMethod);

        Expression returnExpr = ((ReturnStmt) targetMethod.getBody().orElseThrow().getStatement(0))
                .getExpression().orElseThrow();

        rejectSelfRecursion(returnExpr, targetSignature);
        rejectFreeReferences(returnExpr, targetMethod);
        rejectParameterWrites(returnExpr, targetMethod);
        rejectDeferredEvaluationConstructs(returnExpr, targetMethod);
        rejectMutatingOrCallExpressions(returnExpr, targetMethod);
        rejectNonPrimitiveOperands(returnExpr, methodName);
        rejectConstantExpressionPromotion(returnExpr, targetMethod);
        rejectReturnTypeConversion(resolvedTarget, returnExpr, methodName);

        Set<ImportDeclaration> staticImportsToRemove = findStaticImportsToRemove(ctx, ownerQualifiedName, methodName,
                targetSignature);

        // All-or-nothing: since the method declaration is being DELETED,
        // leaving even one use unresolved/uninlined would break the build
        // with no way back short of re-adding the declaration by hand -- so
        // unlike this codebase's usual "warn and leave unchanged" stance for
        // things it can't resolve, ANY unresolvable candidate that merely
        // NAME-matches (and therefore might be a real, missed use) refuses
        // the whole operation outright instead of just warning about it.
        List<MethodCallExpr> matchedCalls = new ArrayList<>();
        List<PlannedInline> plannedInlines = new ArrayList<>();
        for (CompilationUnit cu : ctx.unitsByFile().values()) {
            for (MethodReferenceExpr ref : cu.findAll(MethodReferenceExpr.class)) {
                if (!ref.getIdentifier().equals(methodName)) {
                    continue;
                }
                ResolvedMethodDeclaration resolvedRef;
                try {
                    resolvedRef = ref.resolve();
                } catch (RuntimeException e) {
                    throw new RefactorException("Could not resolve method reference '" + ref + "' in " + fileOf(ctx, cu)
                            + " while checking for uses of '" + methodName + "' -- refusing rather than risk "
                            + "deleting the method while this might still reference it: " + e.getMessage(), e);
                }
                if (resolvedRef.getQualifiedSignature().equals(targetSignature)) {
                    throw new RefactorException("Cannot inline '" + methodName + "': it's referenced via "
                            + "a method reference ('" + ref + "' in " + fileOf(ctx, cu) + "), which can't "
                            + "be textually inlined the way a call site can.");
                }
            }
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                if (!call.getNameAsString().equals(methodName)) {
                    continue;
                }
                ResolvedMethodDeclaration resolvedCall;
                try {
                    resolvedCall = call.resolve();
                } catch (RuntimeException e) {
                    throw new RefactorException("Could not resolve call '" + call + "' in " + fileOf(ctx, cu)
                            + " -- refusing rather than risk deleting the method while this might still call it: "
                            + e.getMessage(), e);
                }
                if (!resolvedCall.getQualifiedSignature().equals(targetSignature)) {
                    continue;
                }
                for (MethodCallExpr already : matchedCalls) {
                    if (isDescendant(call, already) || isDescendant(already, call)) {
                        throw new RefactorException("Cannot inline '" + methodName + "': a call to it at "
                                + call.getBegin().map(Object::toString).orElse("?") + " is nested inside another "
                                + "call to the same method -- not supported in this version.");
                    }
                }
                matchedCalls.add(call);
                rejectUnsafeReceiver(call, targetMethod.isStatic(), methodName);
                rejectStatementPosition(call, methodName);
                rejectParameterTypeConversion(call, resolvedTarget, methodName);
                rejectAllLiteralArgumentsConstantPromotion(call, returnExpr, targetMethod, methodName);
                plannedInlines.add(planInline(call, returnExpr, targetMethod, methodName));
            }
        }

        Map<Path, String> originalContents = new LinkedHashMap<>();
        originalContents.computeIfAbsent(fileOf(ctx, targetMethod.findCompilationUnit().orElseThrow()),
                InlineMethodRefactor::readOriginal);
        for (PlannedInline plan : plannedInlines) {
            originalContents.computeIfAbsent(fileOf(ctx, plan.call.findCompilationUnit().orElseThrow()),
                    InlineMethodRefactor::readOriginal);
        }
        for (ImportDeclaration imp : staticImportsToRemove) {
            originalContents.computeIfAbsent(fileOf(ctx, imp.findCompilationUnit().orElseThrow()),
                    InlineMethodRefactor::readOriginal);
        }

        // Apply every mutation together.
        for (PlannedInline plan : plannedInlines) {
            plan.call.replace(plan.substituted);
        }
        for (ImportDeclaration imp : staticImportsToRemove) {
            imp.remove();
        }
        targetMethod.remove();

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

    private record PlannedInline(MethodCallExpr call, Expression substituted) {
    }

    /**
     * Builds the substituted expression for one call site: clones the
     * target's return expression, then replaces every {@code NameExpr}
     * confirmed (by resolving it on the ORIGINAL, still-attached return
     * expression, then correlating by {@code findAll} traversal index into
     * the clone -- same technique used in {@code RenameClassRefactor} for a
     * {@code VariableDeclarator}'s type subtree) to be a genuine reference
     * to one of the method's OWN parameters -- resolving on the original
     * rather than guessing by name avoids misidentifying a same-named
     * parameter of a NESTED lambda inside the return expression as if it
     * were the outer method's own parameter.
     */
    private static PlannedInline planInline(MethodCallExpr call, Expression returnExpr, MethodDeclaration targetMethod,
                                             String methodName) {
        List<NameExpr> originalNodes = returnExpr.findAll(NameExpr.class);
        List<Integer> paramIndexPerNode = new ArrayList<>();
        for (NameExpr node : originalNodes) {
            paramIndexPerNode.add(resolveAsOwnParameter(node, targetMethod).orElse(-1));
        }

        List<Parameter> parameters = targetMethod.getParameters();
        if (parameters.size() != call.getArguments().size()) {
            throw new RefactorException("Cannot inline call to '" + methodName + "' at "
                    + call.getBegin().map(Object::toString).orElse("?") + ": argument count doesn't match the "
                    + "method's parameter count.");
        }

        rejectUnsafeArgumentSubstitution(call, returnExpr, parameters, paramIndexPerNode, methodName);

        // A return expression that IS (not merely contains) one of the
        // method's own parameters -- e.g. `private int id(int x) { return
        // x; }` -- is itself the single element of `originalNodes`, with NO
        // parent inside `returnExpr` to call `.replace()` on. Node#replace()
        // fails silently (returns false, mutates nothing) when asked to
        // replace a node that has no parent, so the general clone-and-
        // replace-children approach below would leave the bare parameter
        // NAME spliced into the call site verbatim instead of substituting
        // the actual argument -- confirmed via repro: `Id.id(y)` was
        // silently rewritten to the literal text `x`, either resolving to
        // some unrelated same-named variable in scope at the call site, or
        // failing to compile if none existed. Handled directly instead: if
        // the WHOLE return expression is a parameter reference, the
        // substituted result is simply that call's corresponding argument,
        // no cloning/reparsing of `returnExpr` involved at all.
        if (returnExpr instanceof NameExpr && paramIndexPerNode.size() == 1 && paramIndexPerNode.get(0) >= 0) {
            Expression argument = call.getArgument(paramIndexPerNode.get(0)).clone();
            Expression wrapped = needsParens(argument) ? new EnclosedExpr(argument) : argument;
            return new PlannedInline(call, wrapped);
        }

        Expression clonedReturn = returnExpr.clone();
        List<NameExpr> clonedNodes = clonedReturn.findAll(NameExpr.class);
        for (int i = 0; i < paramIndexPerNode.size(); i++) {
            int paramIndex = paramIndexPerNode.get(i);
            if (paramIndex >= 0) {
                // The argument is being embedded into whatever operator
                // context surrounds its parameter's occurrence in the
                // return expression (e.g. substituting `x` in `x * x` with
                // an argument `b + 1` needs to become `(b + 1) * (b + 1)`,
                // not `b + 1 * b + 1` -- confirmed via repro that omitting
                // this produces exactly that silently-wrong precedence) --
                // so it needs the same conservative "wrap unless atomic"
                // treatment as the assembled expression gets at the call
                // site below, applied per-occurrence.
                Expression argument = call.getArgument(paramIndex).clone();
                Expression argumentToInsert = needsParens(argument) ? new EnclosedExpr(argument) : argument;
                clonedNodes.get(i).replace(argumentToInsert);
            }
        }

        // LexicalPreservingPrinter prints a CLONED node (even after its
        // children are structurally replaced, as just done above) using
        // STALE text captured at clone time -- confirmed via minimal repro:
        // `orig.clone()` then replacing every child `NameExpr` produces a
        // clone whose `toString()` correctly reflects the new structure
        // ("a * a"), but printing the CU that clone got inserted into via
        // `LexicalPreservingPrinter.print(cu)` still shows the ORIGINAL
        // pre-mutation text ("x * x") at that position. A freshly-PARSED
        // node (never derived from `clone()`) has no such stale association
        // and prints correctly, so the mutated clone's plain (non-lexical)
        // `toString()` -- which IS structurally correct -- is re-parsed into
        // a brand new node before being spliced into the tree.
        Expression reparsed = reparseExpression(clonedReturn.toString());

        Expression substituted = needsParens(reparsed) ? new EnclosedExpr(reparsed) : reparsed;
        return new PlannedInline(call, substituted);
    }

    /**
     * Refuses a call site whose argument substitution wouldn't preserve the
     * ORIGINAL call's evaluation semantics for at least one parameter --
     * Java always evaluates every argument, exactly once, unconditionally,
     * left-to-right, before invoking the method; a return expression's own
     * textual layout doesn't automatically preserve any of that once
     * parameters are substituted directly into it. For each parameter,
     * requires EITHER (a) it's referenced in the body exactly once,
     * unconditionally (not inside a ternary branch, a short-circuit
     * {@code &&}/{@code ||} right operand, or a lambda body -- the last
     * because a lambda defers evaluation to invocation time, possibly zero
     * or many times, not once-eagerly like a normal argument), and at the
     * SAME relative left-to-right position among parameter references as
     * its own declaration index (so reference order matches evaluation
     * order) -- OR (b) its call-site argument is side-effect-free, in which
     * case none of that matters since re-ordering/dropping/duplicating/
     * deferring a side-effect-free expression is unobservable. Confirmed via
     * repro that omitting this allows: an unused parameter's side-effecting
     * argument being silently dropped, parameters referenced out of
     * declaration order silently swapping evaluation order, and a
     * short-circuit/ternary in the body turning a previously-always-run
     * argument into a conditionally-run one.
     */
    private static void rejectUnsafeArgumentSubstitution(MethodCallExpr call, Expression returnExpr,
                                                           List<Parameter> parameters, List<Integer> paramIndexPerNode,
                                                           String methodName) {
        int n = parameters.size();
        int[] referenceCount = new int[n];
        boolean[] conditionalOrDeferred = new boolean[n];
        int[] firstOccurrenceRank = new int[n];
        java.util.Arrays.fill(firstOccurrenceRank, -1);
        int rank = 0;

        List<NameExpr> originalNodes = returnExpr.findAll(NameExpr.class);
        for (int i = 0; i < originalNodes.size(); i++) {
            int paramIndex = paramIndexPerNode.get(i);
            if (paramIndex < 0) {
                continue;
            }
            referenceCount[paramIndex]++;
            if (!isEvaluatedUnconditionallyAndEagerly(originalNodes.get(i), returnExpr)) {
                conditionalOrDeferred[paramIndex] = true;
            }
            if (firstOccurrenceRank[paramIndex] < 0) {
                firstOccurrenceRank[paramIndex] = rank++;
            }
        }

        for (int i = 0; i < n; i++) {
            boolean safeByPattern = referenceCount[i] == 1 && !conditionalOrDeferred[i] && firstOccurrenceRank[i] == i;
            // "Safe by pattern" only means THIS parameter's own reference is
            // fine in isolation -- it says nothing about whether a SIBLING
            // argument at the same call site can affect what this argument
            // reads. With more than one parameter, a duplicated READ (from a
            // param referenced twice, which still requires isSideEffectFree
            // below) can straddle another argument's WRITE -- confirmed via
            // repro: `static int combine(int a, int b) { return a + b + a;
            // }` called as `combine(x, x = 5)` went from printing 7 to 11,
            // because `b`'s own argument (`x = 5`, an assignment) was NEVER
            // checked at all: `b` itself is referenced exactly once, in
            // order, so it was "safe by pattern" and skipped -- but its
            // side effect still landed between the two reads of `a`. So for
            // a multi-parameter call, EVERY argument must be in the narrow
            // side-effect-free whitelist, regardless of that parameter's
            // own reference pattern; only a single-parameter call (where no
            // sibling argument can possibly interfere) gets the "safe by
            // pattern" shortcut.
            if (safeByPattern && n == 1) {
                continue;
            }
            if (isSideEffectFree(call.getArgument(i))) {
                continue;
            }
            String reason = referenceCount[i] == 0 ? "is never referenced in the method body"
                    : referenceCount[i] > 1 ? "is referenced more than once in the method body"
                    : conditionalOrDeferred[i] ? "is only referenced conditionally, or inside a lambda, in the "
                    + "method body"
                    : !safeByPattern ? "is referenced out of order relative to the call's other arguments"
                    : "is one of more than one parameter in this call";
            throw new RefactorException("Cannot inline call to '" + methodName + "' at "
                    + call.getBegin().map(Object::toString).orElse("?") + ": parameter '"
                    + parameters.get(i).getNameAsString() + "' " + reason + ", and its argument here ('"
                    + call.getArgument(i) + "') isn't obviously side-effect-free -- inlining could change when, "
                    + "how many times, or in what order it's evaluated, or let it observe a sibling argument's "
                    + "side effect that the original call's argument evaluation order would have prevented.");
        }
    }

    /**
     * Does evaluating {@code node} happen unconditionally (not gated behind
     * a ternary branch or short-circuit {@code &&}/{@code ||}) and eagerly
     * (not deferred inside a lambda body) on the path from it up to {@code
     * root}? Mirrors the equivalent hoistability walk in {@link
     * ExtractVariableRefactor#run}, but in the opposite direction: that one
     * asks whether it's safe to move evaluation EARLIER; this one asks
     * whether substituting IN PLACE preserves the "always exactly once,
     * eagerly" evaluation every ordinary method argument gets.
     */
    private static boolean isEvaluatedUnconditionallyAndEagerly(Node node, Node root) {
        Node current = node;
        while (current != root) {
            Node parent = current.getParentNode()
                    .orElseThrow(() -> new RefactorException("Internal error: lost the path to the return expression"));
            if (parent instanceof LambdaExpr) {
                return false;
            }
            // Any nested method/constructor/initializer body -- e.g. a
            // method inside an ANONYMOUS class created within the return
            // expression -- defers evaluation the same way a lambda body
            // does (confirmed via repro: an argument meant to be captured
            // once, eagerly, was instead re-evaluated on every invocation of
            // the anonymous class's method).
            if (parent instanceof com.github.javaparser.ast.body.CallableDeclaration
                    || parent instanceof com.github.javaparser.ast.body.InitializerDeclaration) {
                return false;
            }
            // A switch expression's entry (`case X -> ...`) only evaluates
            // when that specific arm is selected -- conditional the same
            // way a ternary branch is (confirmed via repro: an argument
            // referenced only in one arm silently stopped evaluating when
            // a different arm was selected at runtime).
            if (parent instanceof com.github.javaparser.ast.stmt.SwitchEntry) {
                return false;
            }
            if (parent instanceof ConditionalExpr ternary
                    && (ternary.getThenExpr() == current || ternary.getElseExpr() == current)) {
                return false;
            }
            if (parent instanceof BinaryExpr binary
                    && (binary.getOperator() == BinaryExpr.Operator.AND || binary.getOperator() == BinaryExpr.Operator.OR)
                    && binary.getRight() == current) {
                return false;
            }
            current = parent;
        }
        return true;
    }

    /**
     * Refuses to inline a call whose scope expression's evaluation the
     * inlining would silently drop, or (for a non-static method) whose
     * receiver isn't the same object the inlined body would end up running
     * against.
     *
     * <p>For a NON-STATIC method: an unqualified call, or one via a bare
     * (unqualified) {@code this}, is safe -- {@code this} means the same
     * thing at the call site as it would inside the spliced-in body. Any
     * OTHER explicit receiver (a variable, a different instance, or a
     * QUALIFIED {@code Outer.this}) is not: the body's own unqualified
     * field/method references would silently rebind to the CALL SITE's
     * {@code this} instead of the original receiver -- confirmed via repro
     * (`other.doubled()` where `doubled()` reads an instance field silently
     * became `this.field * 2`, i.e. read the CALLER's field instead of
     * `other`'s).
     *
     * <p>For a STATIC method: Java still allows (and evaluates) an
     * INSTANCE-expression qualifier -- {@code expr.staticMethod()} is legal
     * and evaluates {@code expr} (discarding its value) before the static
     * call. A PURE TYPE-NAME qualifier ({@code Util.staticMethod()}) never
     * evaluates anything and is fine to just delete. Confirmed via repro
     * that deleting the whole call node for an instance-qualified static
     * call ({@code get().staticMethod()}) silently drops {@code get()}'s
     * own side effect entirely -- an earlier version of this check treated
     * EVERY static-method scope as automatically safe, which missed this.
     */
    private static void rejectUnsafeReceiver(MethodCallExpr call, boolean isStatic, String methodName) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            return;
        }
        if (isStatic) {
            if (isPureTypeQualifier(scope.get())) {
                return;
            }
            throw new RefactorException("Cannot inline call to '" + methodName + "' at "
                    + call.getBegin().map(Object::toString).orElse("?") + ": it's qualified by an expression ('"
                    + scope.get() + "') rather than just the class name -- Java still evaluates that expression "
                    + "(and discards the result) before a static call, so deleting the call entirely would "
                    + "silently drop any side effect it has. Not supported in this version.");
        }
        if (scope.get() instanceof ThisExpr thisExpr && thisExpr.getTypeName().isEmpty()) {
            return;
        }
        throw new RefactorException("Cannot inline call to '" + methodName + "' at "
                + call.getBegin().map(Object::toString).orElse("?") + ": it has an explicit receiver ('"
                + scope.get() + "') -- inlining a non-static method would rebind any unqualified field/method "
                + "reference inside its body to the CALL SITE's own instance instead, which is only safe for an "
                + "unqualified call or a bare 'this.' call.");
    }

    /**
     * Is {@code scope} purely a class-name qualifier (never evaluated at
     * runtime), as opposed to a value-producing instance expression? Same
     * technique used elsewhere in this codebase for distinguishing a static
     * type qualifier from a shadowing value (see {@code
     * RenameClassRefactor}'s static-qualifier detection): a {@link NameExpr}
     * or {@link FieldAccessExpr} chain that does NOT resolve as a value is
     * treated as a type name; anything else (a method call, object
     * creation, array access, ...) is definitely a real expression.
     */
    private static boolean isPureTypeQualifier(Expression scope) {
        if (scope instanceof NameExpr name) {
            try {
                name.resolve();
                return false;
            } catch (RuntimeException e) {
                return true;
            }
        }
        if (scope instanceof FieldAccessExpr fieldAccess) {
            try {
                fieldAccess.resolve();
                return false;
            } catch (RuntimeException e) {
                return isPureTypeQualifier(fieldAccess.getScope());
            }
        }
        return false;
    }

    /**
     * Refuses to inline a call that's used as a bare expression statement
     * (its result discarded, e.g. {@code noise(a);}) -- Java only allows a
     * narrow set of expression shapes as a statement on their own (method
     * calls, object creation, assignment, {@code ++}/{@code --}), and the
     * substituted expression here is built from an arbitrary VALUE
     * expression (that's the whole premise of a single-{@code return}
     * method), almost always NOT one of those shapes, and never legal once
     * wrapped in parentheses the way this refactor's precedence-safety
     * wrapping requires -- confirmed via repro that inlining into a bare
     * statement position produces "not a statement" compile errors.
     */
    private static void rejectStatementPosition(MethodCallExpr call, String methodName) {
        Node parent = call.getParentNode().orElse(null);
        if (parent instanceof ExpressionStmt stmt) {
            Node stmtParent = stmt.getParentNode().orElse(null);
            // JavaParser normalizes an expression-bodied lambda (`() -> foo()`,
            // no braces) into an implicit ExpressionStmt wrapping the body
            // expression -- for a VALUE-compatible lambda (`.map(v ->
            // Util.f(v))`) that's genuinely not a discarded-result bare
            // statement. But for a VOID-compatible lambda (`Runnable r = ()
            // -> Util.noise(a);`, `.forEach(v -> Util.f(v))`), the body
            // really does have to be a legal STATEMENT EXPRESSION on its
            // own, exactly like a genuine bare statement -- confirmed via
            // repro that substituting a value expression there ("lambda
            // body is not compatible with a void functional interface")
            // broke the build. Telling the two apart needs the lambda's
            // functional-interface TARGET TYPE, which isn't reliably
            // available at this point -- so, having been burned once
            // already treating "parent is a LambdaExpr" as automatically
            // safe, this refuses BOTH cases uniformly rather than risk
            // guessing wrong again; the value-compatible convenience case
            // is a known, accepted cost of that.
            if (stmtParent instanceof LambdaExpr) {
                throw new RefactorException("Cannot inline call to '" + methodName + "' at "
                        + call.getBegin().map(Object::toString).orElse("?") + ": it's the (sole) body of a lambda "
                        + "expression -- if the lambda's target is a void-compatible functional interface (e.g. "
                        + "'Runnable', 'Consumer'), the substituted expression wouldn't be legal there, and "
                        + "reliably telling that apart from a value-compatible lambda isn't done here. Not "
                        + "supported in this version.");
            }
            // Likewise, JavaParser wraps a SWITCH EXPRESSION's arrow-arm value
            // (`case 1 -> foo();`) in the same kind of implicit ExpressionStmt
            // under a SwitchEntry -- that value is used too (it's the switch
            // expression's result for that arm), unlike the equivalent arm in
            // an ordinary switch STATEMENT, which really does discard it.
            // Confirmed via repro that a switch expression arm was refused
            // with the same misleading "result discarded" message even though
            // the equivalent `case 1 -> { yield foo(); }` block form correctly
            // succeeds.
            if (stmtParent instanceof com.github.javaparser.ast.stmt.SwitchEntry entry
                    && entry.getParentNode().orElse(null) instanceof com.github.javaparser.ast.expr.SwitchExpr) {
                return;
            }
            throw new RefactorException("Cannot inline call to '" + methodName + "' at "
                    + call.getBegin().map(Object::toString).orElse("?") + ": it's used as a bare statement "
                    + "(its result discarded) -- the substituted expression generally isn't legal Java in "
                    + "that position. Not supported in this version.");
        }
        // A for-loop's own initialization/update clauses have the same
        // "must be a legal statement expression" restriction as a bare
        // statement, but AREN'T wrapped in an ExpressionStmt -- they're
        // direct elements of the ForStmt's own expression lists (confirmed
        // via repro: `for (int i = 0; i < 3; Util.noise(i))` compiled before
        // inlining and failed with a syntax error after).
        if (parent instanceof com.github.javaparser.ast.stmt.ForStmt forStmt
                && (forStmt.getUpdate().contains(call) || forStmt.getInitialization().contains(call))) {
            throw new RefactorException("Cannot inline call to '" + methodName + "' at "
                    + call.getBegin().map(Object::toString).orElse("?") + ": it's used in a for-loop's own "
                    + "initialization/update clause -- the substituted expression generally isn't legal Java in "
                    + "that position. Not supported in this version.");
        }
    }

    /** Is {@code node} strictly nested inside {@code possibleAncestor} (not equal to it)? */
    private static boolean isDescendant(Node node, Node possibleAncestor) {
        Optional<Node> current = node.getParentNode();
        while (current.isPresent()) {
            if (current.get() == possibleAncestor) {
                return true;
            }
            current = current.get().getParentNode();
        }
        return false;
    }

    /**
     * Refuses to inline a method whose body references anything OTHER than
     * its own parameters -- a field, a constant, or a sibling method called
     * without a qualifier all resolve against the DECLARING class's own
     * scope today; spliced verbatim into a call site elsewhere, the same
     * unqualified text would resolve against the CALL SITE's scope instead
     * (silently picking up an unrelated same-named field/method if one
     * happens to exist there, or failing to compile if not) -- confirmed via
     * repro for both a same-named field in the caller's own class (silently
     * wrong result) and a genuinely missing symbol (compile error). Also
     * refuses:
     * <ul>
     * <li>any explicit {@code this}/{@code super} reference (bare, or as the
     * scope of a field access/method call, e.g. {@code this.field},
     * {@code super.x()}) -- confirmed via repro that an unqualified call
     * from an INNER or ANONYMOUS class nested inside the declaring class has
     * an implicit {@code Outer.this} receiver, not the call site's own
     * {@code this}, which {@link #rejectUnsafeReceiver}'s "bare this is
     * always safe" assumption doesn't account for; a body that references
     * {@code this}/{@code super} at all is refused outright rather than try
     * to distinguish the safe cases from that one
     * <li>any type reference (a {@code new Foo()}, a cast, an
     * {@code instanceof} check, a {@code Foo.class} literal, or a generic
     * type witness) -- confirmed via repro that a type name not imported at
     * the call site produces a compile error, and worse, if a DIFFERENT
     * same-simple-named class happens to be in scope there, silently binds
     * to the wrong one instead; type names aren't {@code NameExpr}s (a
     * {@code new Foo()}'s type is a {@code ClassOrInterfaceType}) so they
     * need their own check
     * </ul>
     * A method that's fully self-contained in terms of its own parameters
     * (no fields, no {@code this}/{@code super}, no unqualified calls, no
     * type references) is unaffected by any of this.
     */
    private static void rejectFreeReferences(Expression returnExpr, MethodDeclaration targetMethod) {
        for (NameExpr name : returnExpr.findAll(NameExpr.class)) {
            if (resolveAsOwnParameter(name, targetMethod).isPresent()) {
                continue;
            }
            throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': its body "
                    + "references '" + name + "', which isn't one of its own parameters -- inlining would resolve "
                    + "that name against the CALL SITE's context instead of its original declaring class, which "
                    + "isn't supported in this version.");
        }
        for (MethodCallExpr call : returnExpr.findAll(MethodCallExpr.class)) {
            if (call.getScope().isEmpty()) {
                throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': its body "
                        + "calls '" + call.getNameAsString() + "(...)' without a qualifier -- inlining would "
                        + "resolve that call against the CALL SITE's context instead of its original declaring "
                        + "class, which isn't supported in this version.");
            }
        }
        if (!returnExpr.findAll(com.github.javaparser.ast.expr.ThisExpr.class).isEmpty()
                || !returnExpr.findAll(com.github.javaparser.ast.expr.SuperExpr.class).isEmpty()) {
            throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': its body "
                    + "references 'this' or 'super' -- inlining could rebind that to the CALL SITE's own instance "
                    + "instead of the original receiver (e.g. when the call site is inside an inner or anonymous "
                    + "class nested in the declaring class), which isn't supported in this version.");
        }
        if (!returnExpr.findAll(com.github.javaparser.ast.type.ClassOrInterfaceType.class).isEmpty()
                || !returnExpr.findAll(com.github.javaparser.ast.expr.CastExpr.class).isEmpty()
                || !returnExpr.findAll(com.github.javaparser.ast.expr.ArrayCreationExpr.class).isEmpty()
                || !returnExpr.findAll(com.github.javaparser.ast.expr.ArrayInitializerExpr.class).isEmpty()
                || !returnExpr.findAll(com.github.javaparser.ast.expr.InstanceOfExpr.class).isEmpty()) {
            // Checked as several separate node kinds, not just
            // ClassOrInterfaceType (which alone misses a primitive cast like
            // `(int) x` -- a CastExpr whose type is a PrimitiveType, not a
            // ClassOrInterfaceType -- and array creation/initializers) --
            // confirmed via review that a ClassOrInterfaceType-only ban lets
            // a primitive cast or `new int[]{...}` through despite this
            // method's own doc claiming "no cast, no new" outright.
            throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': its body "
                    + "references a type (a cast, 'new', an array type, 'instanceof', '.class', or a generic type "
                    + "argument) -- inlining would resolve that type name against the CALL SITE's imports instead "
                    + "of the method's original declaring file, which isn't supported in this version.");
        }
        for (FieldAccessExpr fieldAccess : returnExpr.findAll(FieldAccessExpr.class)) {
            rejectInaccessibleField(fieldAccess, targetMethod);
        }
    }

    /**
     * Refuses to inline a body reading a field through a parameter unless
     * BOTH the field itself AND its declaring type (and every type that
     * ENCLOSES it, for a nested type) are {@code public} -- {@link
     * #rejectFreeReferences}'s other checks confirm the field access ITSELF
     * is legal (it's reached through a parameter, not a free reference),
     * but say nothing about whether it's legal from every CALL SITE's own
     * location. JLS 6.6.1 requires accessibility of BOTH the member and its
     * declaring type; confirmed via repro that checking only the field's
     * own modifier misses the case where the field is {@code public} but
     * declared on a PACKAGE-PRIVATE class obtained some other way (e.g.
     * returned from a public factory method, so the call site never even
     * names the inaccessible type) -- inlining such an accessor writes a
     * diff that reports success but leaves the project unable to compile
     * ({@code "Box is defined in an inaccessible class or interface"}).
     * Requiring public-all-the-way-up sidesteps needing to know each call
     * site's own package/class at body-analysis time.
     */
    private static void rejectInaccessibleField(FieldAccessExpr fieldAccess, MethodDeclaration targetMethod) {
        try {
            ResolvedValueDeclaration resolved = fieldAccess.resolve();
            if (!(resolved instanceof com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration field)) {
                return;
            }
            if (field.accessSpecifier() != com.github.javaparser.ast.AccessSpecifier.PUBLIC) {
                throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': its body "
                        + "reads '" + fieldAccess + "', a non-public field -- inlining would splice that access "
                        + "into every call site, some of which may not have access to it (confirmed via repro to "
                        + "produce a project that no longer compiles). Not supported in this version.");
            }
            if (isTypeOrEnclosingTypeNonPublic(field.declaringType())) {
                throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': its body "
                        + "reads '" + fieldAccess + "', whose declaring type isn't public (or is nested inside a "
                        + "non-public one) -- even though the field itself is public, a call site elsewhere may "
                        + "not have access to the TYPE that declares it (confirmed via repro to produce a project "
                        + "that no longer compiles). Not supported in this version.");
            }
        } catch (RefactorException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RefactorException("Could not resolve field access '" + fieldAccess + "' in '"
                    + targetMethod.getNameAsString() + "' for an accessibility check: " + e.getMessage(), e);
        }
    }

    /**
     * Is {@code type}, or any type that ENCLOSES it (walking outward via
     * {@code containerType()} for a nested type), non-public? A member of a
     * type is only actually reachable from outside if every enclosing type
     * on the way to it is ALSO accessible (JLS 6.6.1) -- checked via {@link
     * com.github.javaparser.resolution.declarations.HasAccessSpecifier},
     * which the concrete class/interface/enum resolution types implement.
     */
    private static boolean isTypeOrEnclosingTypeNonPublic(
            com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration type) {
        com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration current = type;
        while (current != null) {
            if (current instanceof com.github.javaparser.resolution.declarations.HasAccessSpecifier has
                    && has.accessSpecifier() != com.github.javaparser.ast.AccessSpecifier.PUBLIC) {
                return true;
            }
            current = current instanceof com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration ref
                    ? ref.containerType().orElse(null) : null;
        }
        return false;
    }

    /**
     * Refuses to inline a method whose body WRITES to one of its own
     * parameters -- an assignment target ({@code a = b}) or the operand of
     * {@code ++}/{@code --} ({@code x++}). Nothing about "referenced once,
     * unconditionally, in order" (see {@link #rejectUnsafeArgumentSubstitution})
     * distinguishes a READ of a parameter from a WRITE to it, so a write
     * would otherwise be treated as an ordinary safe single reference and
     * the call's ARGUMENT expression spliced directly into the write
     * position -- confirmed via repro that this either silently mutates the
     * CALLER's own local variable (when the argument is itself a plain
     * variable reference, e.g. {@code Util.set(p, 5)} with body {@code
     * return a = b;} silently sets the caller's {@code p} to {@code 5}, a
     * side effect that never happened before) or produces flatly
     * uncompilable Java (when the argument isn't an assignable expression at
     * all, e.g. {@code Util.set(1, 5)} substitutes into {@code 1 = 5}). A
     * method that writes to its own parameter isn't a pure expression
     * method in the sense this refactor requires at all.
     */
    private static void rejectParameterWrites(Expression returnExpr, MethodDeclaration targetMethod) {
        for (var assign : returnExpr.findAll(com.github.javaparser.ast.expr.AssignExpr.class)) {
            if (assign.getTarget() instanceof NameExpr target
                    && resolveAsOwnParameter(target, targetMethod).isPresent()) {
                throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': its body "
                        + "assigns to its own parameter '" + target + "' -- substituting the call's argument "
                        + "expression into that write position would either mutate the caller's variable or "
                        + "produce illegal Java, depending on what's passed. Not supported in this version.");
            }
        }
        for (var unary : returnExpr.findAll(com.github.javaparser.ast.expr.UnaryExpr.class)) {
            if (!isIncrementOrDecrement(unary)) {
                continue;
            }
            if (unary.getExpression() instanceof NameExpr operand
                    && resolveAsOwnParameter(operand, targetMethod).isPresent()) {
                throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': its body "
                        + "applies '" + unary.getOperator().asString() + "' to its own parameter '" + operand
                        + "' -- substituting the call's argument expression into that write position would "
                        + "either mutate the caller's variable or produce illegal Java, depending on what's "
                        + "passed. Not supported in this version.");
            }
        }
    }

    private static boolean isIncrementOrDecrement(com.github.javaparser.ast.expr.UnaryExpr unary) {
        return switch (unary.getOperator()) {
            case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> true;
            default -> false;
        };
    }

    /**
     * Refuses to inline a body containing a lambda or method reference --
     * pure textual substitution has no notion of capture-avoiding
     * substitution (alpha-renaming), and a binder introduced inside the
     * body can silently capture a substituted argument instead of the thing
     * it was originally bound to. Confirmed via repro: a body lambda whose
     * OWN parameter happened to share a name with an unrelated field at the
     * call site silently captured that field instead of the substituted
     * argument, changing a stream filter's result; with a LOCAL of the same
     * name at the call site instead of a field, the identical substitution
     * produced a flat "variable already defined" compile error. A second,
     * independent hazard: a lambda defers evaluation of anything captured
     * inside it to whenever the lambda actually runs (possibly zero, one,
     * or many times) rather than the method argument's normal "evaluated
     * exactly once, eagerly, before the call" semantics -- confirmed via a
     * separate repro where a field read deferred into a lambda body
     * observed a LATER mutation of that field instead of its value at call
     * time. (An anonymous/local class has the same two hazards, but is
     * already unreachable here since it always requires a type reference,
     * which {@link #rejectFreeReferences} already bans outright.)
     */
    private static void rejectDeferredEvaluationConstructs(Expression returnExpr, MethodDeclaration targetMethod) {
        if (!returnExpr.findAll(LambdaExpr.class).isEmpty()) {
            throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': its body contains "
                    + "a lambda expression -- a lambda can capture the call site's own same-named local/field "
                    + "instead of a substituted argument, or defer evaluation of one to whenever the lambda "
                    + "actually runs instead of the original call's eager, once-only evaluation. Not supported in "
                    + "this version.");
        }
        if (!returnExpr.findAll(MethodReferenceExpr.class).isEmpty()) {
            throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': its body contains "
                    + "a method reference -- same capture/deferred-evaluation hazards as a lambda. Not supported "
                    + "in this version.");
        }
    }

    /**
     * Refuses to inline a body that calls a method, or writes to anything
     * (an assignment, or {@code ++}/{@code --}) -- broader than {@link
     * #rejectFreeReferences}'s unqualified-call ban (this bans EVERY call,
     * qualified or not) and {@link #rejectParameterWrites}'s
     * parameter-only write ban (this bans EVERY write, including to an
     * array element or a field of a parameter). A method CALL's side
     * effects have no verified ordering relationship to the CALL SITE's own
     * remaining arguments once substituted -- confirmed via repro: {@code
     * return a.append(x).length() + b;} silently changed behavior once
     * {@code b}'s own argument expression observed the mutation {@code
     * a.append(x)} had just made, because Java evaluates all of a method's
     * arguments before the call, but after inlining, evaluation order
     * instead follows the return expression's own textual layout. A WRITE
     * has the same "unverified interaction with the rest of the
     * expression" problem, and only ever mutates something reachable
     * through a parameter (any other target is already unreachable via
     * {@link #rejectFreeReferences}'s field/type bans) -- so together, a
     * body that survives BOTH this check and {@link #rejectFreeReferences}
     * can only read its own parameters, literals, and operators on them:
     * provably free of both side effects and evaluation-order risk.
     */
    private static void rejectMutatingOrCallExpressions(Expression returnExpr, MethodDeclaration targetMethod) {
        if (!returnExpr.findAll(MethodCallExpr.class).isEmpty()) {
            throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': its body calls a "
                    + "method -- a call's side effects (if any) have no verified ordering relationship to the "
                    + "call site's own remaining arguments once substituted into their textual position, which "
                    + "could silently change evaluation order. Not supported in this version.");
        }
        if (!returnExpr.findAll(com.github.javaparser.ast.expr.AssignExpr.class).isEmpty()) {
            throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': its body contains "
                    + "an assignment -- same evaluation-order risk as a method call. Not supported in this "
                    + "version.");
        }
        if (returnExpr.findAll(com.github.javaparser.ast.expr.UnaryExpr.class).stream()
                .anyMatch(InlineMethodRefactor::isIncrementOrDecrement)) {
            throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': its body applies "
                    + "'++' or '--' -- same evaluation-order risk as a method call. Not supported in this "
                    + "version.");
        }
    }

    /**
     * Refuses to inline a body containing an operator (binary or unary,
     * excluding {@code ++}/{@code --}, already banned elsewhere) applied to
     * a NON-primitive operand -- e.g. {@code a + b} where {@code a}/{@code
     * b} are {@code Integer} (implicit unboxing) or {@code String}/{@code
     * Object} (implicit {@code toString()}/string concatenation). Neither
     * of those conversions has an AST node of its own -- {@link
     * #rejectMutatingOrCallExpressions}'s {@code MethodCallExpr} ban cannot
     * see them at all -- yet both are genuine method invocations with their
     * own exception/evaluation-order behavior. Confirmed via repro (a)
     * {@code static String join(Object a, Object b) { return a + "|" + b;
     * }}: the implicit {@code a.toString()} silently ran BEFORE the second
     * argument was evaluated once inlined, whereas Java evaluates all of a
     * method's own arguments up front, changing which of two side-effecting
     * arguments' output appeared first; (b) {@code static Integer sum(Integer
     * a, Integer b) { return a + b; }}: the implicit unboxing of {@code a}
     * (which can throw {@code NullPointerException}) silently moved to
     * after the second argument's own side effect ran, instead of before
     * it. Requiring every operand to already be a primitive closes both:
     * primitive-to-primitive arithmetic/comparison never invokes anything.
     */
    private static void rejectNonPrimitiveOperands(Expression returnExpr, String methodName) {
        for (BinaryExpr binary : returnExpr.findAll(BinaryExpr.class)) {
            requirePrimitiveOperand(binary.getLeft(), methodName);
            requirePrimitiveOperand(binary.getRight(), methodName);
        }
        for (var unary : returnExpr.findAll(com.github.javaparser.ast.expr.UnaryExpr.class)) {
            if (!isIncrementOrDecrement(unary)) {
                requirePrimitiveOperand(unary.getExpression(), methodName);
            }
        }
        // A ternary's own then/else branches can ALSO trigger implicit
        // unboxing/numeric promotion under their own JLS 15.25 rules (not
        // wrapped in a BinaryExpr/UnaryExpr node at all) -- e.g. `flag ?
        // boxedA : primitiveB` unboxes boxedA. Requiring both branches be
        // primitive is conservative (a same-boxed-type ternary like `flag ?
        // boxedA : boxedB` doesn't actually unbox per JLS) but safe, and
        // consistent with every other operator context in this check.
        for (ConditionalExpr ternary : returnExpr.findAll(ConditionalExpr.class)) {
            // The CONDITION itself unboxes too if it's a `Boolean` rather
            // than a primitive `boolean` -- not just the two branches.
            requirePrimitiveOperand(ternary.getCondition(), methodName);
            requirePrimitiveOperand(ternary.getThenExpr(), methodName);
            requirePrimitiveOperand(ternary.getElseExpr(), methodName);
        }
        // An array index must be int or auto-unboxable to int -- `param[boxedIndex]`
        // implicitly unboxes `boxedIndex` the same way an arithmetic operand does.
        for (ArrayAccessExpr arrayAccess : returnExpr.findAll(ArrayAccessExpr.class)) {
            requirePrimitiveOperand(arrayAccess.getIndex(), methodName);
        }
    }

    private static void requirePrimitiveOperand(Expression operand, String methodName) {
        ResolvedType type;
        try {
            type = operand.calculateResolvedType();
        } catch (RuntimeException e) {
            throw new RefactorException("Could not determine the type of '" + operand + "' in '" + methodName
                    + "' for an implicit-conversion safety check: " + e.getMessage(), e);
        }
        if (!type.isPrimitive()) {
            throw new RefactorException("Cannot inline '" + methodName + "': its body applies an operator to '"
                    + operand + "', whose type ('" + type.describe() + "') isn't primitive -- an operator on a "
                    + "non-primitive operand can implicitly invoke something (unboxing, 'toString()' for string "
                    + "concatenation, ...) that has no AST node of its own for this refactor's method-call ban to "
                    + "catch, but still has its own evaluation-order/exception behavior. Not supported in this "
                    + "version.");
        }
    }

    /**
     * Refuses to inline a method whose return expression references NONE of
     * its own parameters at all -- every substitution at every call site
     * would then be identical and composed purely of literals/operators on
     * them, which Java's compiler recognizes as a COMPILE-TIME CONSTANT
     * EXPRESSION. A plain method call is never a constant expression, so
     * this promotion is a real, observable change: confirmed via repro that
     * {@code while (Flags.enabled())} (a runtime call, `Flags.enabled()`
     * returning a literal {@code true}) compiled fine before, but became
     * {@code while (true)} after inlining -- an unconditionally-true loop
     * condition, which javac then flags any code after the loop as
     * unreachable. The same promotion can silently turn a {@code static
     * final} field initialized from such a call into a true compile-time
     * constant, which javac inlines into the BYTECODE of every other class
     * that reads it -- an ABI-level change no source-level diff shows.
     */
    private static void rejectConstantExpressionPromotion(Expression returnExpr, MethodDeclaration targetMethod) {
        boolean referencesAnyParameter = returnExpr.findAll(NameExpr.class).stream()
                .anyMatch(name -> resolveAsOwnParameter(name, targetMethod).isPresent());
        if (!referencesAnyParameter) {
            throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': its return "
                    + "expression doesn't reference any of its own parameters, so after inlining it would become "
                    + "IDENTICAL at every call site -- Java's compiler treats that as a compile-time constant "
                    + "expression, unlike the original method call, which can change loop/branch reachability "
                    + "analysis or promote a 'static final' field to a true bytecode-inlined constant. Not "
                    + "supported in this version.");
        }
    }

    /**
     * Refuses to inline a specific CALL SITE where every argument is a JLS
     * compile-time CONSTANT EXPRESSION -- even when the return expression
     * DOES reference its parameters (so {@link
     * #rejectConstantExpressionPromotion} doesn't apply), a call whose
     * arguments are all constant expressions still substitutes to an
     * expression composed purely of constants/operators on them, which is
     * itself a compile-time constant for THAT call site specifically (e.g.
     * {@code Util.max(1, 2)} substituting to {@code (1 > 2 ? 1 : 2)}) --
     * same reachability/constant-promotion risk as {@link
     * #rejectConstantExpressionPromotion}, just scoped to one call instead
     * of the method as a whole. Deliberately NOT an {@code instanceof
     * LiteralExpr} check: JLS 15.29 constant expressions are much broader
     * than bare literals -- a parenthesized literal, a unary-minus literal
     * (confirmed via repro that {@code -1} is a {@code UnaryExpr}, not a
     * {@code LiteralExpr}, so {@code Flags.neg(-1)} slipped past an
     * earlier, narrower version of this check while {@code Flags.neg(1)}
     * was correctly caught), a cast of a constant, and -- most
     * consequentially -- a reference to a {@code final} variable/field
     * initialized with a constant expression ("constant variable") ALL
     * count. That last case was confirmed via repro to be the most
     * dangerous: a {@code static final} field initialized from an inlined
     * call compiled cleanly but silently changed from a real runtime field
     * read ({@code getstatic} in the bytecode) to a true compile-time
     * constant baked into every OTHER class's bytecode at compile time --
     * an ABI change invisible in the source diff and undetected by javac.
     *
     * <p>Only checks arguments bound to a parameter the return expression
     * actually REFERENCES -- an argument bound to a parameter the body never
     * uses contributes nothing to the substituted result (its expression is
     * simply dropped), so it can't make that result non-constant, and
     * checking it anyway (as an earlier version of this check did, testing
     * EVERY argument regardless of whether its parameter was referenced) let
     * a non-constant "decoy" argument on an otherwise all-constant call mask
     * the promotion entirely -- confirmed via repro: {@code static int
     * pick(int unused, int b) { return b * 2; }} called as {@code
     * pick(nonConstant, 3)} substitutes to {@code (3 * 2)}, a genuine
     * compile-time constant, even though {@code nonConstant} itself isn't
     * one.
     */
    private static void rejectAllLiteralArgumentsConstantPromotion(MethodCallExpr call, Expression returnExpr,
                                                                     MethodDeclaration targetMethod, String methodName) {
        Set<Integer> referencedParamIndices = referencedParameterIndices(returnExpr, targetMethod);
        if (!referencedParamIndices.isEmpty() && referencedParamIndices.stream()
                .allMatch(i -> mightBeConstantExpression(call.getArgument(i)))) {
            throw new RefactorException("Cannot inline call to '" + methodName + "' at "
                    + call.getBegin().map(Object::toString).orElse("?") + ": every argument actually referenced by "
                    + "the method body is a compile-time constant expression, so the substituted expression would "
                    + "be a compile-time constant at this call site, unlike the original method call -- same "
                    + "reachability/constant-promotion risk as an entirely parameter-free body. Not supported in "
                    + "this version.");
        }
    }

    /** The indices of {@code targetMethod}'s own parameters that {@code returnExpr} actually references. */
    private static Set<Integer> referencedParameterIndices(Expression returnExpr, MethodDeclaration targetMethod) {
        Set<Integer> indices = new LinkedHashSet<>();
        for (NameExpr name : returnExpr.findAll(NameExpr.class)) {
            resolveAsOwnParameter(name, targetMethod).ifPresent(indices::add);
        }
        return indices;
    }

    /**
     * Is {@code expr} a JLS 15.29 compile-time constant expression? A
     * recursive, best-effort check: literals, parenthesized/unary/binary/
     * cast compositions of constants, and a reference to an explicitly
     * {@code final} variable or field whose OWN initializer is (recursively)
     * a constant expression -- checked via the AST wrapped node when the
     * declaration is source-based (same technique used elsewhere in this
     * file for parameter identity), same as JLS 4.12.4's definition of a
     * "constant variable".
     *
     * <p>Whatever this can't positively determine (a field/local from
     * compiled/library code with no AST to inspect, an enum constant, or any
     * resolution failure) answers {@code whenUnknown} -- which callers MUST
     * pass according to which direction is safe FOR THEM, because the two
     * uses of this predicate in this file want opposite defaults:
     * <ul>
     * <li>{@link #rejectAllLiteralArgumentsConstantPromotion} treats
     * "constant" as a reason to REFUSE, so unknown must answer {@code true}
     * (see {@link #mightBeConstantExpression}) -- under-refusing there is
     * exactly the ABI-hazard bug that check exists to prevent
     * <li>{@link #rejectDeclaringTypeStaticInitializationEffect} treats
     * "constant" as a reason to ALLOW (a constant static field costs no
     * {@code <clinit>}), so unknown must answer {@code false} (see {@link
     * #isDefinitelyConstantExpression}) -- confirmed via repro that sharing
     * the "unknown means constant" default let {@code static final Level
     * DEFAULT = Level.HIGH;} (an enum constant, which has no {@code
     * JavaParserFieldDeclaration} to inspect) be misread as a constant
     * field, so the class was judged to have no static-initialization effect
     * at all and its {@code <clinit>} was silently deleted along with the
     * method
     * </ul>
     * Both call sites getting "unknown" wrong in their own direction is the
     * same latent hazard, which is why the answer is a required parameter
     * here rather than a default baked into this method.
     */
    private static boolean isConstantExpression(Expression expr, boolean whenUnknown) {
        if (expr instanceof LiteralExpr) {
            return true;
        }
        if (expr instanceof EnclosedExpr enclosed) {
            return isConstantExpression(enclosed.getInner(), whenUnknown);
        }
        if (expr instanceof com.github.javaparser.ast.expr.UnaryExpr unary && !isIncrementOrDecrement(unary)) {
            return isConstantExpression(unary.getExpression(), whenUnknown);
        }
        if (expr instanceof BinaryExpr binary) {
            return isConstantExpression(binary.getLeft(), whenUnknown)
                    && isConstantExpression(binary.getRight(), whenUnknown);
        }
        if (expr instanceof com.github.javaparser.ast.expr.CastExpr cast) {
            return isConstantExpression(cast.getExpression(), whenUnknown);
        }
        // JLS 15.29 also lists the conditional operator `? :` itself, when
        // its condition and both branches are all constant expressions --
        // confirmed via repro that omitting this let a ternary of constants
        // (e.g. `DEBUG ? 1 : 0` with `DEBUG` a `static final boolean`) slip
        // through as "not constant" and trigger the same unreachable-
        // statement/ABI-promotion hazard this check exists to prevent.
        if (expr instanceof ConditionalExpr ternary) {
            return isConstantExpression(ternary.getCondition(), whenUnknown)
                    && isConstantExpression(ternary.getThenExpr(), whenUnknown)
                    && isConstantExpression(ternary.getElseExpr(), whenUnknown);
        }
        if (expr instanceof NameExpr || expr instanceof FieldAccessExpr) {
            return resolvesToConstantVariable(expr, whenUnknown);
        }
        return false;
    }

    /**
     * {@link #isConstantExpression} for a caller that REFUSES on "constant"
     * -- anything undeterminable counts as possibly-constant.
     */
    private static boolean mightBeConstantExpression(Expression expr) {
        return isConstantExpression(expr, true);
    }

    /**
     * {@link #isConstantExpression} for a caller that ALLOWS on "constant"
     * -- anything undeterminable counts as NOT constant.
     */
    private static boolean isDefinitelyConstantExpression(Expression expr) {
        return isConstantExpression(expr, false);
    }

    private static boolean resolvesToConstantVariable(Expression expr, boolean whenUnknown) {
        ResolvedValueDeclaration resolved;
        try {
            resolved = expr instanceof NameExpr name ? name.resolve() : ((FieldAccessExpr) expr).resolve();
        } catch (RuntimeException e) {
            return whenUnknown;
        }
        if (resolved instanceof com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserParameterDeclaration) {
            // A parameter is never initialized, so it can never satisfy
            // JLS 4.12.4's "final variable... initialized with a constant
            // expression" -- definitely not a constant variable.
            return false;
        }
        if (resolved instanceof com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserVariableDeclaration localVar) {
            var declExpr = localVar.getWrappedNode();
            if (!declExpr.isFinal()) {
                return false;
            }
            return declExpr.getVariables().stream()
                    .filter(vd -> vd.getNameAsString().equals(resolved.getName()))
                    .findFirst()
                    .flatMap(com.github.javaparser.ast.body.VariableDeclarator::getInitializer)
                    .map(init -> isConstantExpression(init, whenUnknown))
                    .orElse(false);
        }
        if (resolved instanceof com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserFieldDeclaration fieldDecl) {
            var wrapped = fieldDecl.getWrappedNode();
            if (!wrapped.isFinal()) {
                return false;
            }
            return wrapped.getVariables().stream()
                    .filter(vd -> vd.getNameAsString().equals(resolved.getName()))
                    .findFirst()
                    .flatMap(com.github.javaparser.ast.body.VariableDeclarator::getInitializer)
                    .map(init -> isConstantExpression(init, whenUnknown))
                    .orElse(false);
        }
        // A field/local from compiled or library code (no AST to inspect),
        // an enum constant, or anything else with no source declaration this
        // can inspect -- undeterminable, so answer whichever way is safe for
        // this particular caller.
        return whenUnknown;
    }

    /**
     * If {@code nameExpr} resolves to one of {@code targetMethod}'s OWN
     * declared parameters (checked by identity against the actual AST
     * {@link Parameter} node, via {@link JavaParserParameterDeclaration}'s
     * wrapped node -- not just by name, which a shadowing nested lambda
     * parameter of the same name could otherwise be confused for), returns
     * that parameter's index.
     */
    private static java.util.Optional<Integer> resolveAsOwnParameter(NameExpr nameExpr, MethodDeclaration targetMethod) {
        try {
            ResolvedValueDeclaration resolved = nameExpr.resolve();
            if (resolved instanceof JavaParserParameterDeclaration jpd) {
                Parameter wrapped = jpd.getWrappedNode();
                int index = targetMethod.getParameters().indexOf(wrapped);
                if (index >= 0) {
                    return java.util.Optional.of(index);
                }
            }
        } catch (RuntimeException ignored) {
            // Not a resolvable reference at all -- not a parameter reference either.
        }
        return java.util.Optional.empty();
    }

    /**
     * Parses {@code text} as a standalone expression using a fresh,
     * locally-scoped {@link JavaParser} instance -- not {@code
     * StaticJavaParser}, whose shared global configuration this project
     * otherwise avoids touching (see {@code ProjectContext.load()}) since a
     * long-lived process handling multiple projects shouldn't have one
     * in-flight parse's config affect another's; a throwaway per-call
     * instance here has no such sharing risk.
     */
    private static Expression reparseExpression(String text) {
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        return parser.parseExpression(text).getResult()
                .orElseThrow(() -> new RefactorException("Internal error: could not re-parse substituted "
                        + "expression '" + text + "'"));
    }

    /**
     * Whitelist (not blacklist) of "trivially safe to drop, duplicate,
     * reorder, or defer" argument shapes -- a plain LOCAL VARIABLE/
     * PARAMETER read, a literal, or {@code this}, none of which can ever
     * throw or have a side effect. Deliberately NOT a blacklist of
     * "known-dangerous" node kinds (method calls, object creation,
     * assignment, increment/decrement): confirmed via repro that such a
     * blacklist still misses plenty of genuinely observable expressions
     * that throw without containing any of those kinds -- array access
     * ({@code ArrayIndexOutOfBoundsException}), division/modulo ({@code
     * ArithmeticException}), a cast ({@code ClassCastException}), or a
     * field/array dereference on a possibly-null reference ({@code
     * NullPointerException}). A previously-caught exception silently
     * stopped firing (or a divide-by-zero silently stopped throwing) once
     * its argument was dropped as an "unused parameter" or evaluated at a
     * different point than the original call.
     *
     * <p>A bare {@link NameExpr} specifically is only treated as safe if it
     * resolves to a local variable or parameter -- NOT a field. A field
     * read is not guaranteed side-effect-free: reading a class's static
     * field (reached directly, via inheritance, or via a static import) can
     * trigger that class's static initializer, an observable effect
     * (including a possible {@code ExceptionInInitializerError}) -- and an
     * instance field read can throw {@code NullPointerException} on a null
     * receiver, same as the already-excluded {@code FieldAccessExpr} case.
     * Confirmed via repro: dropping an "unused parameter" whose argument
     * was a {@code NameExpr} resolving to another class's static field (via
     * a single static import) silently removed that class's initializer
     * side effect entirely, even though nothing about the argument LOOKED
     * dangerous. A statically-imported ENUM CONSTANT has the exact same
     * hazard and its own distinct resolution type ({@code
     * ResolvedEnumConstantDeclaration}, which does NOT extend {@code
     * ResolvedFieldDeclaration}) -- confirmed via repro that excluding only
     * {@code ResolvedFieldDeclaration} still let a dropped enum-constant
     * argument silently skip the enum's own static initializer.
     */
    private static boolean isSideEffectFree(Expression argument) {
        if (argument instanceof LiteralExpr || argument instanceof ThisExpr) {
            return true;
        }
        if (argument instanceof NameExpr name) {
            try {
                var resolved = name.resolve();
                return !(resolved instanceof com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration)
                        && !(resolved instanceof com.github.javaparser.resolution.declarations.ResolvedEnumConstantDeclaration);
            } catch (RuntimeException e) {
                return false;
            }
        }
        return false;
    }

    /** Whitelist of expression kinds that never need wrapping parens for correct precedence when substituted. */
    private static boolean needsParens(Expression expr) {
        return !(expr instanceof NameExpr || expr instanceof LiteralExpr || expr instanceof MethodCallExpr
                || expr instanceof FieldAccessExpr || expr instanceof ObjectCreationExpr
                || expr instanceof ArrayAccessExpr || expr instanceof ThisExpr || expr instanceof EnclosedExpr);
    }

    /**
     * Refuses to inline anything but a {@code static} or {@code private}
     * method -- see the class-level doc for why any other instance method
     * carries a virtual-dispatch risk this refactor can't safely rule out.
     */
    private static void rejectVirtualDispatchRisk(MethodDeclaration targetMethod) {
        if (!targetMethod.isStatic() && !targetMethod.isPrivate()) {
            throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': only static or "
                    + "private methods can be inlined in this version -- any other instance method is subject to "
                    + "virtual dispatch, and a call site typed as an ancestor class/interface could dispatch to "
                    + "this exact override at runtime without this refactor's call-site scan ever finding it "
                    + "(it would statically resolve to the ancestor's own signature instead), so inlining could "
                    + "silently change behavior for such a call site.");
        }
    }

    /**
     * Refuses to inline a {@code synchronized} method -- the monitor
     * acquisition is a declaration-level effect this refactor's body-only
     * analysis never even looks at; deleting the declaration silently
     * discards it, turning what was deliberately an atomic operation into a
     * data race with no error, warning, or even a non-concurrent test able
     * to catch it.
     */
    private static void rejectSynchronized(MethodDeclaration targetMethod) {
        if (targetMethod.isSynchronized()) {
            throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': it's "
                    + "'synchronized' -- inlining would silently drop the monitor acquisition. Not supported in "
                    + "this version.");
        }
    }

    /**
     * Refuses to inline a method with a {@code throws} clause -- a method
     * can legitimately declare a checked exception it never actually throws
     * (a normal API-stability idiom, e.g. reserving the right to throw it in
     * a future version), and a {@code catch} at a call site written against
     * that declaration would become unreachable ("exception X is never
     * thrown") once the call is replaced by a plain expression that can't
     * throw it -- confirmed via repro. This refactor doesn't attempt to
     * verify a body actually throws (or doesn't throw) anything it declares,
     * so it refuses any {@code throws} clause outright rather than guess.
     */
    private static void rejectThrowsClause(MethodDeclaration targetMethod) {
        if (!targetMethod.getThrownExceptions().isEmpty()) {
            throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': it declares a "
                    + "'throws' clause -- a call site's own 'catch' for a declared-but-never-actually-thrown "
                    + "exception would become unreachable once the call is replaced by a plain expression. Not "
                    + "supported in this version.");
        }
    }

    /**
     * Refuses to inline a method whose declaring type has any static
     * initialization effect (a {@code static} initializer block, an enum's
     * own implicit constant-initialization effect, or a {@code static}
     * field whose initializer isn't itself a JLS 4.12.4 constant expression)
     * -- per JLS 12.4.1, invoking a static method (or, more generally,
     * executing any code that reaches the class the FIRST time) triggers
     * that class's {@code <clinit>}. Deleting the call site removes the one
     * thing that was triggering it there, an observable effect (registration
     * side effects, lazy singleton setup, even an {@code
     * ExceptionInInitializerError}) this refactor's body-only, expression-
     * level analysis has no way to see or preserve -- confirmed via repro: a
     * class with a {@code static { System.out.println(...); }} block and a
     * trivial {@code public static boolean atLeast(int n) { return n > 0; }}
     * printed the block's output before inlining and never printed it after,
     * with the call replaced by {@code (n > 0)} and no trace of the class
     * ever being touched. This applies even to a {@code private} method
     * called from a nested class, since that call is what would otherwise
     * guarantee the enclosing class gets initialized from the nested class's
     * perspective. Conservative rather than trying to prove some specific
     * call site is already guaranteed to run after the class is otherwise
     * initialized -- matches this file's general "fail loudly rather than
     * guess" stance.
     *
     * <p>Checked on the declaring type AND on every ANCESTOR whose own
     * {@code <clinit>} that type's initialization would cascade into: per
     * JLS 12.4.2 step 7, initializing a class first initializes its
     * SUPERCLASS, and (only) those SUPERINTERFACES that declare a default
     * method. Confirmed via repro that inspecting just the declaring type's
     * own members -- which is all an AST {@link TypeDeclaration} exposes --
     * missed both: a {@code class Util extends Base} where {@code Base} held
     * the {@code static { ... }} block was accepted and inlined, silently
     * dropping {@code Base}'s initializer, and the same held for an
     * interface with a non-constant field and a default method. That was the
     * same recurring bug this file keeps hitting -- an AST node-kind
     * enumeration standing in for a semantic property (here, "initializing
     * this class has an observable effect", which is defined over the whole
     * ancestor chain, not one declaration).
     *
     * <p>An ancestor with no source AST to inspect (a compiled/library type)
     * can't be checked at all, so it's refused rather than assumed inert --
     * except {@code java.lang.Object}, whose initialization is complete
     * before any user code runs at all and so can never be the thing a
     * deleted call site was still triggering.
     */
    private static void rejectDeclaringTypeStaticInitializationEffect(TypeDeclaration<?> targetClass,
                                                                        MethodDeclaration targetMethod) {
        rejectStaticInitializationEffectOn(targetClass, targetClass.getNameAsString(), targetMethod, "its declaring "
                + "type '" + targetClass.getNameAsString() + "'");

        com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration resolvedClass;
        try {
            resolvedClass = targetClass.resolve();
        } catch (RuntimeException e) {
            throw new RefactorException("Could not resolve '" + targetClass.getNameAsString() + "' to check its "
                    + "ancestors for static-initialization effects: " + e.getMessage(), e);
        }
        for (var ancestor : resolvedClass.getAllAncestors()) {
            var declOpt = ancestor.getTypeDeclaration();
            if (declOpt.isEmpty()) {
                throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': an ancestor of "
                        + "its declaring type ('" + ancestor.describe() + "') couldn't be resolved, so whether "
                        + "initializing it has an observable effect (JLS 12.4.2) can't be checked. Refusing rather "
                        + "than guess.");
            }
            var decl = declOpt.get();
            if (decl.getQualifiedName().equals("java.lang.Object")) {
                // Always fully initialized before any user code runs -- can
                // never be what a deleted call site was still triggering.
                continue;
            }
            // JLS 12.4.2 step 7: initializing a class initializes its
            // superclass, but only those superinterfaces that DECLARE A
            // DEFAULT METHOD. An interface without one is never initialized
            // on a subtype's account, so it can't be affected here.
            if (decl.isInterface() && decl.getDeclaredMethods().stream().noneMatch(m -> m.isDefaultMethod())) {
                continue;
            }
            var ast = decl.toAst(TypeDeclaration.class);
            if (ast.isEmpty()) {
                throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': its declaring "
                        + "type inherits from '" + decl.getQualifiedName() + "', which has no source available to "
                        + "check for static-initialization effects -- initializing the declaring type would also "
                        + "initialize that type (JLS 12.4.2), and deleting every call site could silently drop the "
                        + "one thing still guaranteeing that happens. Refusing rather than guess.");
            }
            rejectStaticInitializationEffectOn(ast.get(), decl.getQualifiedName(), targetMethod,
                    "'" + decl.getQualifiedName() + "', which its declaring type inherits from (so initializing "
                            + "the declaring type initializes it too, JLS 12.4.2),");
        }
    }

    /**
     * The single-type half of {@link
     * #rejectDeclaringTypeStaticInitializationEffect}: does initializing
     * exactly {@code type} (ignoring its own ancestors) run anything
     * observable? {@code description} names it in the error message, since
     * the same test applies to the declaring type itself and to each
     * ancestor.
     */
    private static void rejectStaticInitializationEffectOn(TypeDeclaration<?> type, String typeName,
                                                             MethodDeclaration targetMethod, String description) {
        if (type instanceof com.github.javaparser.ast.body.EnumDeclaration) {
            throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': " + description
                    + " is an enum, which always has its own static initialization effect (constructing its "
                    + "constants) that invoking one of its methods would otherwise trigger -- deleting every call "
                    + "could silently drop the only thing still causing that to happen. Not supported in this "
                    + "version.");
        }
        for (var member : type.getMembers()) {
            if (member instanceof com.github.javaparser.ast.body.InitializerDeclaration init && init.isStatic()) {
                throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': " + description
                        + " has a 'static' initializer block -- invoking a method on this class triggers that block "
                        + "to run (JLS 12.4.1) if it hasn't already; deleting every call site could silently drop "
                        + "the one thing still guaranteeing that happens. Not supported in this version.");
            }
            if (member instanceof com.github.javaparser.ast.body.FieldDeclaration field && field.isStatic()) {
                for (var vd : field.getVariables()) {
                    // NOTE the polarity here: "constant" ALLOWS, so an
                    // undeterminable initializer must count as NOT constant
                    // (see isDefinitelyConstantExpression) -- the opposite of
                    // what the constant-promotion check needs.
                    boolean constantInit = vd.getInitializer()
                            .map(InlineMethodRefactor::isDefinitelyConstantExpression)
                            .orElse(false);
                    if (!constantInit) {
                        throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': "
                                + description + " has a 'static' field ('" + vd.getNameAsString() + "') whose "
                                + "initializer isn't a compile-time constant expression -- evaluating it is part of "
                                + "that class's <clinit>, which invoking a method on this class triggers (JLS "
                                + "12.4.1) if it hasn't run yet; deleting every call site could silently drop the "
                                + "one thing still guaranteeing that happens. Not supported in this version.");
                    }
                }
            }
        }
    }

    private static void rejectUnsupportedShape(MethodDeclaration targetMethod) {
        if (!targetMethod.getTypeParameters().isEmpty()) {
            throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': it declares its "
                    + "own type parameters (a generic method) -- not supported in this version.");
        }
        if (!targetMethod.getParameters().isEmpty()
                && targetMethod.getParameters().get(targetMethod.getParameters().size() - 1).isVarArgs()) {
            throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': it has a varargs "
                    + "parameter -- not supported in this version.");
        }
        java.util.Optional<BlockStmt> body = targetMethod.getBody();
        if (body.isEmpty()) {
            throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString()
                    + "': it has no body (abstract or native).");
        }
        List<Statement> statements = body.get().getStatements();
        if (statements.size() != 1 || !(statements.get(0) instanceof ReturnStmt returnStmt)
                || returnStmt.getExpression().isEmpty()) {
            throw new RefactorException("Cannot inline '" + targetMethod.getNameAsString() + "': its body isn't "
                    + "exactly a single 'return <expr>;' statement -- only single-expression methods are "
                    + "supported in this version.");
        }
    }

    /**
     * Refuses to inline a method whose {@code return} statement requires an
     * implicit conversion (widening, boxing/unboxing) to match its declared
     * return type -- e.g. {@code static double half(int x) { return x; }}
     * implicitly widens the {@code int} expression {@code x} to {@code
     * double} at the return statement. Splicing the bare return EXPRESSION
     * into the call site (as this refactor does) carries only the
     * expression's OWN type, not that conversion, which can silently change
     * behavior anywhere the result feeds into further arithmetic or overload
     * resolution -- confirmed via repro: {@code Util.half(n) / 2} went from
     * {@code 0.5} (double division) to {@code 0} (integer division) once
     * inlined, and a boxing case ({@code Object} from {@code int})
     * silently picked a DIFFERENT overload ({@code List.remove(int)} vs.
     * {@code remove(Object)}) at the call site.
     */
    private static void rejectReturnTypeConversion(ResolvedMethodDeclaration resolvedTarget, Expression returnExpr,
                                                     String methodName) {
        ResolvedType declared;
        ResolvedType actual;
        try {
            declared = resolvedTarget.getReturnType();
            actual = returnExpr.calculateResolvedType();
        } catch (RuntimeException e) {
            // JavaParser's resolver is known not to support resolving the
            // type of a switch expression, among other things -- rather
            // than surface that as an opaque internal-looking failure, say
            // plainly that this refactor can't verify the conversion here
            // and is refusing rather than guess.
            throw new RefactorException("Cannot inline '" + methodName + "': could not determine its return "
                    + "expression's type for a conversion-safety check (" + e.getMessage() + ") -- this can happen "
                    + "for a switch expression, which this refactor's underlying resolver doesn't fully support. "
                    + "Refusing rather than guess whether an implicit conversion is involved.");
        }
        String declaredDescribe = declared.describe();
        String actualDescribe = actual.describe();
        if (!declaredDescribe.equals(actualDescribe) && !declaredDescribe.equals(stripWildcardBound(actualDescribe))) {
            throw new RefactorException("Cannot inline '" + methodName + "': its declared return type ('"
                    + declaredDescribe + "') differs from its return expression's own type ('" + actualDescribe
                    + "') -- inlining would substitute the expression's un-converted type instead of the implicit "
                    + "conversion Java applies at the return statement, which isn't supported in this version.");
        }
    }

    /**
     * Similar conversion-safety concern as {@link #rejectReturnTypeConversion}
     * but for each PARAMETER: a gap between a call's actual argument type
     * and the parameter's declared type is exactly the implicit conversion
     * (widening OR boxing/unboxing) Java applies when binding the argument,
     * and confirmed via repro to be unsafe to drop in EITHER direction, not
     * just for primitive widening -- {@code static double scale(double x) {
     * return x * 2; }} inlined with an {@code int} argument silently
     * switches from double to integer arithmetic wherever the parameter is
     * USED in the body, and separately, a boxing/unboxing conversion at the
     * parameter boundary is unsafe whenever the parameter's value flows
     * unchanged into the return expression (the common {@code return x;}
     * shape): substituting an {@code int} argument directly in place of an
     * {@code Integer} parameter -- or vice versa -- can silently change
     * which downstream overload gets selected (the exact {@code
     * List.remove(int)} vs. {@code remove(Object)} danger the return-side
     * check exists to catch, reachable again through the parameter side once
     * the parameter's value passes straight through) or make a previously-
     * throwing unboxing-of-null {@code NullPointerException} silently stop
     * happening. An EARLIER version of this check tried to tolerate plain
     * primitive/wrapper boxing here on the theory that it was safe unless it
     * escaped through the return value -- confirmed via repro that it is NOT
     * safe in general (the identity-method shape make the "escapes through
     * the return value" case trivially common, not a rare corner) -- so this
     * is back to requiring an exact type match, with ONLY the generic-
     * inference wildcard-capture artifact (e.g. {@code "? super
     * java.lang.Integer"}, confirmed to show up for a lambda parameter
     * passed into a stream operation) unwrapped to its bound before
     * comparing, since that specific gap is a resolver-description quirk
     * for the SAME type, not an actual conversion.
     */
    private static void rejectParameterTypeConversion(MethodCallExpr call, ResolvedMethodDeclaration resolvedTarget,
                                                        String methodName) {
        List<ResolvedType> declaredParamTypes;
        try {
            declaredParamTypes = resolvedTarget.formalParameterTypes();
        } catch (RuntimeException e) {
            throw new RefactorException("Could not determine '" + methodName + "'s parameter types for a "
                    + "conversion-safety check: " + e.getMessage(), e);
        }
        for (int i = 0; i < declaredParamTypes.size() && i < call.getArguments().size(); i++) {
            rejectPolyExpressionArgument(call.getArgument(i), i, call, methodName);
            ResolvedType actual;
            try {
                actual = call.getArgument(i).calculateResolvedType();
            } catch (RuntimeException e) {
                throw new RefactorException("Could not determine the type of the argument at position " + i
                        + " in call to '" + methodName + "' at " + call.getBegin().map(Object::toString).orElse("?")
                        + " for a conversion-safety check: " + e.getMessage(), e);
            }
            String declaredDescribe = declaredParamTypes.get(i).describe();
            String actualDescribe = actual.describe();
            if (!declaredDescribe.equals(actualDescribe) && !declaredDescribe.equals(stripWildcardBound(actualDescribe))) {
                throw new RefactorException("Cannot inline call to '" + methodName + "' at "
                        + call.getBegin().map(Object::toString).orElse("?") + ": parameter " + i + " has declared "
                        + "type '" + declaredDescribe + "' but this call's argument has type '" + actualDescribe
                        + "' -- inlining would substitute the argument's un-converted type instead of the implicit "
                        + "conversion Java applies when binding it to the parameter, which isn't supported in this "
                        + "version.");
            }
        }
    }

    /**
     * Refuses a call whose argument is a POLY EXPRESSION -- a lambda or
     * method reference, whose type is not a property of the expression at
     * all but of the CONTEXT it appears in (JLS 15.27.3 / 15.13.2). This is
     * the one argument shape that defeats {@link
     * #rejectParameterTypeConversion}'s whole approach: {@code
     * calculateResolvedType()} on a lambda returns its TARGET type, i.e. the
     * parameter's own declared type, so the "declared type equals actual
     * type" test it relies on passes trivially, by construction, no matter
     * what. String-equality of resolved types is standing in for the
     * semantic property "this expression has that type independently of
     * where it sits" -- which is exactly false here.
     *
     * <p>Confirmed via repro: {@code static Runnable wrap(Runnable r) {
     * return r; }} called as {@code Object o = Util.wrap(() ->
     * System.out.println("hi"));} passed every single gate (static, single
     * {@code return r;}, no free references, no lambda IN THE BODY, no
     * operators, exact return- and parameter-type matches, referenced once
     * unconditionally) and inlined to {@code Object o = (() ->
     * System.out.println("hi"));} -- the tool reported success and exited 0,
     * and javac then failed with "incompatible types: Object is not a
     * functional interface", because the lambda's target type went from
     * {@code Runnable} to whatever the call's own surrounding context
     * supplies. Where that context is an overload set rather than a plain
     * assignment, the same substitution can silently select a DIFFERENT
     * overload instead of failing loudly.
     */
    private static void rejectPolyExpressionArgument(Expression argument, int index, MethodCallExpr call,
                                                        String methodName) {
        if (argument instanceof LambdaExpr || argument instanceof MethodReferenceExpr) {
            throw new RefactorException("Cannot inline call to '" + methodName + "' at "
                    + call.getBegin().map(Object::toString).orElse("?") + ": the argument at position " + index
                    + " ('" + argument + "') is a lambda or method reference, whose type comes from the CONTEXT it "
                    + "sits in rather than from the expression itself -- substituting it into the call's own "
                    + "surrounding context would re-target it against a different functional interface (or none at "
                    + "all), which can silently pick a different overload or fail to compile. Not supported in "
                    + "this version.");
        }
    }

    /**
     * Strips a leading generic-inference wildcard-capture bound (e.g. {@code
     * "? super java.lang.Integer"} -> {@code "java.lang.Integer"}) -- only
     * the OUTER prefix, deliberately: a wildcard nested inside a type
     * argument (e.g. {@code "java.util.List<? extends
     * java.lang.Integer>"}) is left as-is, since that's a genuinely
     * different (parameterized) type, not a capture artifact for the same
     * type this method's caller is trying to see through.
     */
    private static String stripWildcardBound(String describe) {
        if (describe.startsWith("? super ")) {
            return describe.substring("? super ".length());
        }
        if (describe.startsWith("? extends ")) {
            return describe.substring("? extends ".length());
        }
        return describe;
    }

    private static void rejectSelfRecursion(Expression returnExpr, String targetSignature) {
        for (MethodCallExpr call : returnExpr.findAll(MethodCallExpr.class)) {
            try {
                if (call.resolve().getQualifiedSignature().equals(targetSignature)) {
                    throw new RefactorException("Cannot inline this method: its own body calls itself ('" + call
                            + "'), so it can't be textually substituted into its own call sites.");
                }
            } catch (RefactorException e) {
                throw e;
            } catch (RuntimeException ignored) {
                // Unresolvable call inside the body -- not our concern here, only self-recursion is.
            }
        }
    }

    /**
     * Finds static imports of exactly this method, matched the same way
     * {@code RenameMethodRefactor.findStaticImportsToRename} matches them
     * (including one reached via a subtype-qualified import, e.g. {@code
     * import static Sub.method;} where {@code method} is actually declared
     * on {@code Base}) -- but these get REMOVED rather than renamed, since
     * the method they import won't exist anymore. Unlike a rename, removal
     * is only safe if NO OTHER overload of {@code methodName} remains
     * visible (declared OR inherited) on the SPECIFIC class the import
     * itself names -- Java's single-type static import of a method name
     * covers every overload of that name visible from the imported class,
     * so if another overload is still reachable through it, the import is
     * still needed and must be left alone. This is checked per-import
     * against the import's OWN qualifier, not against {@code targetClass}:
     * confirmed via repro that a subtype-qualified import (e.g. {@code
     * import static Sub.foo;} where {@code Sub extends Base} and both
     * declare their own {@code foo} overload) was incorrectly removed by an
     * earlier version of this check that only looked at {@code
     * targetClass}'s own directly-declared methods, breaking the still-live
     * overload declared on {@code Sub} itself.
     */
    private static Set<ImportDeclaration> findStaticImportsToRemove(ProjectContext ctx,
                                                                      String ownerQualifiedName, String methodName,
                                                                      String targetSignature) {
        Set<ImportDeclaration> result = new LinkedHashSet<>();
        for (CompilationUnit cu : ctx.unitsByFile().values()) {
            for (ImportDeclaration imp : cu.getImports()) {
                if (!imp.isStatic() || imp.isAsterisk()) {
                    continue;
                }
                String qualifiedName = imp.getNameAsString();
                int lastDot = qualifiedName.lastIndexOf('.');
                if (lastDot < 0 || !qualifiedName.substring(lastDot + 1).equals(methodName)) {
                    continue;
                }
                String qualifier = qualifiedName.substring(0, lastDot);
                var resolved = ctx.typeSolver().tryToSolveType(qualifier);
                if (!resolved.isSolved()) {
                    continue;
                }
                var decl = resolved.getCorrespondingDeclaration();
                boolean matchesFamily = decl.getQualifiedName().equals(ownerQualifiedName)
                        || decl.getAllAncestors().stream().anyMatch(a -> a.getQualifiedName().equals(ownerQualifiedName));
                if (matchesFamily && !hasOtherVisibleOverload(decl, methodName, targetSignature)) {
                    result.add(imp);
                }
            }
        }
        return result;
    }

    /** Does {@code decl} (declared OR inherited) still have some OTHER method visible under {@code methodName}? */
    private static boolean hasOtherVisibleOverload(
            com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration decl,
            String methodName, String targetSignature) {
        for (var declared : decl.getDeclaredMethods()) {
            if (declared.getName().equals(methodName) && !declared.getQualifiedSignature().equals(targetSignature)) {
                return true;
            }
        }
        for (var ancestor : decl.getAllAncestors()) {
            for (var inherited : ancestor.getDeclaredMethods()) {
                if (inherited.getName().equals(methodName)
                        && !inherited.getDeclaration().getQualifiedSignature().equals(targetSignature)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Finds the method to inline, disambiguating overloads via
     * {@code paramsFilter} (the CLI's {@code --params}) when more than one
     * method on the class shares {@code methodName} -- same approach as
     * {@code RenameMethodRefactor.findMethod}.
     */
    private static MethodDeclaration findMethod(TypeDeclaration<?> targetClass, String methodName, String paramsFilter) {
        List<MethodDeclaration> matches = targetClass.getMethods().stream()
                .filter(m -> m.getNameAsString().equals(methodName))
                .toList();
        if (matches.isEmpty()) {
            throw new RefactorException(
                    "No method named '" + methodName + "' declared directly on '" + targetClass.getNameAsString() + "'");
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (paramsFilter == null) {
            throw new RefactorException("Method name '" + methodName + "' is overloaded (" + matches.size()
                    + " overloads) on '" + targetClass.getNameAsString() + "'. Disambiguate with --params.");
        }
        List<String> wanted = parseParamsFilter(paramsFilter);
        List<MethodDeclaration> filtered = new ArrayList<>();
        for (MethodDeclaration candidate : matches) {
            try {
                if (paramsFilterMatches(candidate.resolve().formalParameterTypes(), wanted)) {
                    filtered.add(candidate);
                }
            } catch (RuntimeException ignored) {
                // Couldn't resolve this overload to check it -- not this candidate's fault, skip it.
            }
        }
        if (filtered.isEmpty()) {
            throw new RefactorException("No overload of '" + methodName + "' on '" + targetClass.getNameAsString()
                    + "' matches --params '" + paramsFilter + "'.");
        }
        if (filtered.size() > 1) {
            throw new RefactorException("--params '" + paramsFilter + "' matches more than one overload of '"
                    + methodName + "' on '" + targetClass.getNameAsString()
                    + "'. Use fully-qualified type names to disambiguate.");
        }
        return filtered.get(0);
    }

    private static List<String> parseParamsFilter(String paramsFilter) {
        if (paramsFilter.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : paramsFilter.split(",", -1)) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                throw new RefactorException("Invalid --params '" + paramsFilter
                        + "': empty parameter type (use --params \"\" for a zero-arg overload).");
            }
            tokens.add(trimmed);
        }
        return tokens;
    }

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

    private static String baseTypeName(String describe) {
        int genericStart = describe.indexOf('<');
        return genericStart < 0 ? describe : describe.substring(0, genericStart);
    }

    private static String simpleNameOf(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot < 0 ? qualifiedName : qualifiedName.substring(lastDot + 1);
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
