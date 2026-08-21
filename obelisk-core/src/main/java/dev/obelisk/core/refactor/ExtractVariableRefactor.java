package dev.obelisk.core.refactor;

import dev.obelisk.guard.Check;
import dev.obelisk.guard.Guard;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.resolution.declarations.ResolvedDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Extracts a single, precisely-addressed expression into a new {@code var}
 * local variable declaration inserted immediately before the statement that
 * contains it, replacing that one occurrence with a reference to the new
 * variable.
 *
 * <p>The target expression is addressed by its exact source range (1-based
 * line/column, inclusive of both ends, matching JavaParser's own
 * {@link Position} convention) rather than by name or pattern -- there's no
 * project-wide search the way the rename refactors do one, since "extract
 * this expression" is inherently about one specific occurrence in one
 * specific file.
 *
 * <p>Unlike every other refactor in this project, the actual edit is NOT
 * done by mutating the AST and reprinting via {@code LexicalPreservingPrinter}
 * -- confirmed via minimal repro that inserting a new statement into a
 * {@link BlockStmt}'s statement list (via either {@code NodeList.add(index,
 * ...)} or {@code NodeList.addBefore(...)}) makes the printer lose track of
 * correct indentation for BOTH the new statement AND, worse, the existing
 * sibling statement it was inserted next to (both end up re-indented to the
 * block's own indentation level, one level shallower than correct). Given
 * this refactor only ever performs one precisely-bounded insertion plus one
 * precisely-bounded text replacement, it's both simpler and more reliable to
 * splice the change directly into the original source text using the AST
 * purely for locating positions and running safety checks, never for
 * mutation.
 *
 * V1 known limitations (fail loudly / document, rather than guess):
 *  - only replaces the ONE addressed occurrence, never "every identical
 *    occurrence in the block" -- deciding that's safe in general requires
 *    data-flow analysis (did anything the expression depends on change
 *    between occurrences?) that this tool doesn't attempt
 *  - the containing statement must be a direct child of a {@code { }}
 *    block, AND must be the first thing on its source line (nothing but
 *    leading whitespace before it) -- both requirements exist so the new
 *    declaration's indentation can be copied verbatim from the existing
 *    statement's own leading whitespace, rather than guessed
 *  - always declares the new variable as {@code var}; extracting a
 *    {@code null} literal, a lambda, or a bare method reference is refused
 *    outright since none of those are legal {@code var} initializers on
 *    their own
 *  - refuses to extract from a position where hoisting the evaluation
 *    earlier could change behavior: the right-hand operand of
 *    {@code &&}/{@code ||} (short-circuit -- currently may not evaluate at
 *    all), either branch of a ternary (conditionally evaluated), the
 *    (non-block) body of a lambda (evaluation there is deferred to
 *    invocation time), a for/while/do-while loop's own condition or update
 *    expression (re-evaluated every iteration, not just once), or an
 *    {@code assert}'s condition/message (conditional on failure, and on
 *    assertions being enabled at all)
 *  - refuses to extract an expression that references a name declared
 *    elsewhere in the same statement (a for-loop's own init variable, or an
 *    earlier declarator in a multi-variable declaration) -- would be an
 *    illegal forward reference once hoisted
 *  - refuses to extract an expression that IS the write-target of an
 *    assignment or a {@code ++}/{@code --} -- would silently turn the write
 *    into a no-op
 *  - refuses a non-value expression (an annotation, a whole variable
 *    declaration) or one whose type is {@code void}
 *  - does not reorder around side effects: extracting one argument out of
 *    several in a call whose OTHER arguments have side effects changes
 *    their relative evaluation order (e.g. {@code combine(sideA(), sideB())}
 *    extracting {@code sideB()} makes it run before {@code sideA()} now) --
 *    only relevant when multiple arguments both have side effects, which is
 *    already fairly unusual code
 */
public final class ExtractVariableRefactor {

    private ExtractVariableRefactor() {
    }

    public static RefactorResult run(ProjectContext ctx, Path file, int startLine, int startColumn, int endLine,
                                      int endColumn, String name, boolean apply) {
        validateIdentifier(name);

        Path resolvedFile = resolveFile(ctx, file);
        CompilationUnit cu = ctx.unitsByFile().get(resolvedFile);

        Position start = new Position(startLine, startColumn);
        Position end = new Position(endLine, endColumn);
        Expression target = findExpression(cu, start, end);

        Statement anchorStatement = target.findAncestor(Statement.class)
                .orElseThrow(() -> new RefactorException(Check.EXPRESSION_NOT_IN_A_STATEMENT, "The expression at " + start + "-" + end
                        + " isn't inside a method/constructor/initializer body (e.g. it's a field initializer, "
                        + "annotation argument, or type-level construct) -- extract-variable only supports "
                        + "expressions inside a statement."));

        rejectUnhoistablePosition(target, anchorStatement);
        rejectRecurringControlPosition(target, anchorStatement);
        rejectAssertPosition(anchorStatement);
        rejectForwardReference(target, anchorStatement);
        rejectLvaluePosition(target);
        rejectCompoundAssignmentReordering(target, anchorStatement);
        rejectHoistAcrossTypeBoundary(target, anchorStatement);
        rejectHoistOutOfResourceSpecification(target, anchorStatement);
        rejectUnsuitableInitializer(target);
        rejectTargetTypedInitializer(target);

        // An expression-bodied lambda (`() -> a + b`, no braces) is
        // normalized by JavaParser into an implicit ExpressionStmt wrapping
        // the body expression, with the LambdaExpr itself as that
        // statement's parent -- so `findAncestor(Statement.class)` above
        // stops at that synthetic statement, one step before ever reaching
        // the LambdaExpr node the walk in rejectUnhoistablePosition checks
        // for. The block check below would still correctly refuse this case
        // (the synthetic statement's parent is a LambdaExpr, not a
        // BlockStmt), but with a misleading "add braces" message -- give
        // the accurate reason instead.
        rejectUnanchorableStatement(anchorStatement);

        rejectNonValueExpression(target);
        rejectLocalNameCollision(target, name);
        // REPAIR: uses of a FIELD the new local would shadow get qualified
        // so they keep reaching the field.
        Map<NameExpr, String> shadowedFieldReferences =
                planShadowedFieldReferences(ctx, target, anchorStatement, name);

        Position anchorBegin = anchorStatement.getBegin()
                .orElseThrow(() -> new RefactorException(Check.INTERNAL_ERROR, "Internal error: enclosing statement has no position"));

        String original = readOriginal(resolvedFile);
        int lineStart = offsetOfLineStart(original, anchorBegin.line);
        int anchorOffset = offsetOf(original, anchorBegin.line, anchorBegin.column);
        String indent = original.substring(lineStart, anchorOffset);
        rejectStatementNotAtLineStart(indent);

        int exprStart = offsetOf(original, start.line, start.column);
        int exprEndExclusive = offsetOf(original, end.line, end.column) + 1;
        String exprText = original.substring(exprStart, exprEndExclusive);

        // Match the file's existing line-ending style rather than hardcoding
        // '\n', so a CRLF file doesn't end up with one lone LF-terminated
        // line among otherwise-CRLF ones.
        String lineEnding = original.contains("\r\n") ? "\r\n" : "\n";

        // Every rewrite as one ordered set of edits on the ORIGINAL text.
        // This refactor splices text rather than mutating the AST, so a
        // repair has to be spliced too -- and doing the qualification in a
        // separate pass would invalidate the offsets computed above.
        List<TextEdit> edits = new ArrayList<>();
        for (Map.Entry<NameExpr, String> entry : shadowedFieldReferences.entrySet()) {
            Range range = entry.getKey().getRange()
                    .orElseThrow(() -> new RefactorException(Check.INTERNAL_ERROR,
                            "Internal error: shadowed reference has no range"));
            int refStart = offsetOf(original, range.begin.line, range.begin.column);
            int refEnd = offsetOf(original, range.end.line, range.end.column) + 1;
            // A reference INSIDE the extracted expression is carried along
            // with it into the initializer, where the new local is already
            // in scope -- so it needs qualifying there too, and the edit
            // that moves the expression must not also overwrite it.
            edits.add(new TextEdit(refStart, refEnd, entry.getValue()));
        }
        String exprTextQualified = applyEdits(exprText, edits.stream()
                .filter(e -> e.start() >= exprStart && e.end() <= exprEndExclusive)
                .map(e -> new TextEdit(e.start() - exprStart, e.end() - exprStart, e.replacement()))
                .toList());

        List<TextEdit> outside = edits.stream()
                .filter(e -> e.end() <= exprStart || e.start() >= exprEndExclusive)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        outside.add(new TextEdit(exprStart, exprEndExclusive, name));
        outside.add(new TextEdit(lineStart, lineStart,
                indent + "var " + name + " = " + exprTextQualified + ";" + lineEnding));

        String updated = applyEdits(original, outside);
        verifyShadowedFieldReferences(shadowedFieldReferences, updated, name);

        Map<Path, String> diffs = new LinkedHashMap<>();
        List<Path> changedFiles = List.of();
        Map<Path, String> updatedContents = Map.of();
        if (!updated.equals(original)) {
            diffs.put(resolvedFile, DiffUtil.unifiedDiff(resolvedFile, original, updated));
            changedFiles = List.of(resolvedFile);
            updatedContents = Map.of(resolvedFile, updated);
        }

        if (apply && !updatedContents.isEmpty()) {
            writeAll(updatedContents);
        }

        return new RefactorResult(apply, changedFiles, diffs, Map.of(), List.of());
    }

    private static void validateIdentifier(String name) {
        if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
            throw new RefactorException(Check.INVALID_IDENTIFIER, "'" + name + "' is not a valid Java variable name");
        }
    }

    private static Path resolveFile(ProjectContext ctx, Path file) {
        Path absolute = file.isAbsolute() ? file.normalize() : ctx.projectDir().resolve(file).normalize();
        if (!ctx.unitsByFile().containsKey(absolute)) {
            throw new RefactorException(Check.FILE_NOT_IN_PROJECT, "'" + file + "' is not one of the project's parsed source files "
                    + "(resolved to " + absolute + ")");
        }
        return absolute;
    }

    /**
     * Finds the {@link Expression} whose range EXACTLY matches
     * {@code [start, end]}, inclusive of both ends -- an exact match rather
     * than "smallest/largest node touching this range" so the addressed
     * occurrence is always unambiguous, at the cost of the caller needing to
     * supply the expression's precise boundaries (naturally available to a
     * caller that has already parsed/inspected the source, e.g. an AI
     * coding agent -- the primary intended caller for this refactor kind).
     */
    private static Expression findExpression(CompilationUnit cu, Position start, Position end) {
        for (Expression candidate : cu.findAll(Expression.class)) {
            if (candidate.getBegin().filter(start::equals).isPresent()
                    && candidate.getEnd().filter(end::equals).isPresent()) {
                return candidate;
            }
        }
        throw new RefactorException(Check.EXPRESSION_NOT_FOUND, "No expression found starting at " + start + " and ending at " + end
                + " -- the range must exactly match a single expression's boundaries.");
    }

    /**
     * Refuses to extract from a position where evaluating the expression
     * earlier (at the enclosing statement, instead of where it currently
     * sits) could change what the program does -- see the class-level doc
     * for the specific cases. Walks from {@code target} up to (but not
     * including) {@code anchorStatement}, since anything at or above the
     * statement boundary is irrelevant to this check.
     */
    @Guard(Check.REJECT_UNHOISTABLE_POSITION)
    private static void rejectUnhoistablePosition(Expression target, Statement anchorStatement) {
        Node current = target;
        while (current != anchorStatement) {
            Node parent = current.getParentNode()
                    .orElseThrow(() -> new RefactorException(Check.REJECT_UNHOISTABLE_POSITION, "Internal error: lost the path from the target "
                            + "expression to its enclosing statement"));
            if (parent instanceof LambdaExpr) {
                throw new RefactorException(Check.REJECT_UNHOISTABLE_POSITION, "Cannot extract this expression: it's the (non-block) body of a "
                        + "lambda, so its evaluation is deferred to when the lambda is invoked -- hoisting it out "
                        + "to the enclosing statement would evaluate it eagerly instead, changing behavior.");
            }
            if (parent instanceof BinaryExpr binary
                    && (binary.getOperator() == BinaryExpr.Operator.AND || binary.getOperator() == BinaryExpr.Operator.OR)
                    && binary.getRight() == current) {
                throw new RefactorException(Check.REJECT_UNHOISTABLE_POSITION, "Cannot extract this expression: it's the right-hand side of a "
                        + "short-circuit " + binary.getOperator().asString() + " and may not currently evaluate at "
                        + "all -- extracting it would evaluate it unconditionally instead, changing behavior.");
            }
            if (parent instanceof ConditionalExpr ternary
                    && (ternary.getThenExpr() == current || ternary.getElseExpr() == current)) {
                throw new RefactorException(Check.REJECT_UNHOISTABLE_POSITION, "Cannot extract this expression: it's a branch of a ternary and only "
                        + "evaluates conditionally -- extracting it would evaluate it unconditionally instead, "
                        + "changing behavior.");
            }
            current = parent;
        }
    }

    /**
     * Refuses to hoist out of the right-hand side of a COMPOUND assignment
     * ({@code +=}, {@code |=}, ...).
     *
     * <p>{@link #rejectLvaluePosition} covers the target BEING the
     * assignment target, but a compound assignment also READS its target
     * before evaluating the right-hand side, so hoisting anything out of the
     * RHS moves that evaluation to before the read. Confirmed by repro:
     * {@code static int x = 1; static int f() { x = 100; return 5; }} with
     * {@code x += f();}. Extracting {@code f()} yields {@code var v = f(); x
     * += v;} and {@code x} goes from 6 to 105, compiling cleanly.
     *
     * <p>The class doc previously framed this reordering hazard as only
     * arising when "multiple arguments both have side effects, which is
     * already fairly unusual code". {@code x += f()} is not unusual.
     */
    @Guard(Check.REJECT_COMPOUND_ASSIGNMENT_REORDERING)
    private static void rejectCompoundAssignmentReordering(Expression target, Statement anchorStatement) {
        for (AssignExpr assign : anchorStatement.findAll(AssignExpr.class)) {
            if (assign.getOperator() == AssignExpr.Operator.ASSIGN) {
                continue;
            }
            if (isWithin(target, assign.getValue())
                    && (isObservableFromElsewhere(assign.getTarget())
                            || rightHandSideWritesTarget(assign))) {
                throw new RefactorException(Check.REJECT_COMPOUND_ASSIGNMENT_REORDERING, "Cannot extract this expression: it's inside the right-hand side of a "
                        + "compound assignment ('" + assign.getOperator().asString() + "') to something the "
                        + "expression could itself modify. A compound assignment READS its target before "
                        + "evaluating the right-hand side, so hoisting the expression above the statement moves "
                        + "its evaluation before that read, which can silently change the result.");
            }
        }
    }

    /**
     * Could the compound assignment's TARGET be modified by something the
     * right-hand side evaluates? Only then does hoisting the right-hand side
     * above the target's read actually change anything.
     *
     * <p>A plain LOCAL cannot be: Java has no way for a callee to reassign a
     * caller's local variable, so {@code sum += expensive(x)} is always safe
     * to extract from -- and is one of the canonical extract-variable sites,
     * so refusing it on the operator alone was a real usability cost. A
     * field, an array element, or anything reached through one is a
     * different matter, and stays refused.
     */
    /**
     * Can the compound assignment's own right-hand side WRITE the target?
     *
     * <p>The narrowing above reasons that a callee cannot reassign a
     * caller's local -- true, but it answers the wrong question. The hazard
     * is whether anything writes the target between the implicit read and
     * the write, and the right-hand side is itself inside that window.
     * Confirmed by repro, both compiling cleanly: {@code x += x++} went from
     * 2 to 3 once {@code x++} was hoisted, and {@code x += (x = 10)} from 11
     * to 20.
     *
     * <p>Matched by identifier rather than by resolved declaration: an
     * over-match here only costs an extraction that a user can work around,
     * whereas a miss is a silent wrong answer.
     */
    private static boolean rightHandSideWritesTarget(AssignExpr assign) {
        String targetName = assign.getTarget().toString();
        for (AssignExpr nested : assign.getValue().findAll(AssignExpr.class)) {
            if (nested.getTarget().toString().equals(targetName)) {
                return true;
            }
        }
        for (UnaryExpr unary : assign.getValue().findAll(UnaryExpr.class)) {
            if (isIncrementOrDecrement(unary) && unary.getExpression().toString().equals(targetName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isObservableFromElsewhere(Expression assignmentTarget) {
        if (assignmentTarget instanceof NameExpr name) {
            try {
                return name.resolve() instanceof com.github.javaparser.resolution.declarations
                        .ResolvedFieldDeclaration;
            } catch (RuntimeException e) {
                return true;
            }
        }
        // A field access, an array element, or anything else -- assume observable.
        return true;
    }

    /**
     * Refuses to hoist an expression OUT of a class body it sits inside.
     *
     * <p>{@code findAncestor(Statement.class)} walks straight past a local
     * or anonymous class declaration, because a field initializer inside one
     * has no enclosing statement of its own -- so the anchor lands outside
     * the class entirely. Confirmed by repro: a local {@code class Counter {
     * int v = next(); }} with {@code new Counter().v + "," + new
     * Counter().v} printed {@code 1,2} before and {@code 1,1} after
     * extracting {@code next()}, which was hoisted above the class
     * declaration -- turning a per-instance initializer into a
     * once-per-program one. Where the initializer references the class's own
     * members, the same path produces a compile error instead.
     */
    @Guard(Check.REJECT_HOIST_ACROSS_TYPE_BOUNDARY)
    private static void rejectHoistAcrossTypeBoundary(Expression target, Statement anchorStatement) {
        Node current = target;
        while (current != anchorStatement) {
            Node parent = current.getParentNode().orElse(null);
            if (parent == null) {
                return;
            }
            if (parent instanceof com.github.javaparser.ast.body.TypeDeclaration
                    || parent instanceof com.github.javaparser.ast.expr.ObjectCreationExpr creation
                            && creation.getAnonymousClassBody().isPresent()
                            && !isWithin(target, creation.getArguments())) {
                throw new RefactorException(Check.REJECT_HOIST_ACROSS_TYPE_BOUNDARY, "Cannot extract this expression: it's inside a local or anonymous "
                        + "class body, and the statement it would be hoisted above sits OUTSIDE that class -- "
                        + "moving it there changes a per-instance initializer into something evaluated once, or "
                        + "puts it where the class's own members aren't in scope.");
            }
            current = parent;
        }
    }

    private static boolean isWithin(Expression target, List<Expression> candidates) {
        return candidates.stream().anyMatch(c -> isWithin(target, c));
    }

    /**
     * Refuses to hoist out of a try-with-resources RESOURCE SPECIFICATION.
     *
     * <p>The anchor for an expression in a try's resource header is the
     * {@code TryStmt} itself, so the new declaration lands BEFORE the
     * {@code try} -- outside the coverage of its own {@code catch} and
     * {@code finally}. Confirmed by repro: extracting {@code open()} from
     * {@code try (Closeable c = open()) {...} catch (Exception e) {...}}
     * moved the call outside, so an exception it threw stopped being caught
     * and crashed the program instead. Compiles cleanly.
     */
    @Guard(Check.REJECT_HOIST_OUT_OF_RESOURCE_SPECIFICATION)
    private static void rejectHoistOutOfResourceSpecification(Expression target, Statement anchorStatement) {
        if (anchorStatement instanceof com.github.javaparser.ast.stmt.TryStmt tryStmt
                && tryStmt.getResources().stream().anyMatch(resource -> isWithin(target, resource))) {
            throw new RefactorException(Check.REJECT_HOIST_OUT_OF_RESOURCE_SPECIFICATION, "Cannot extract this expression: it's part of a try-with-resources "
                    + "resource specification, so hoisting it would move its evaluation OUTSIDE the 'try' -- any "
                    + "exception it throws would stop being handled by this statement's own catch/finally.");
        }
    }

    /**
     * Refuses an expression whose type depends on the CONTEXT it sits in,
     * because {@code var} gives it a standalone type instead.
     *
     * <p>{@link #rejectUnsuitableInitializer} enumerates node KINDS
     * ({@code null}, lambda, method reference) as a stand-in for the
     * semantic property "this expression's standalone type equals its type
     * in context" (JLS 15.2 poly expressions, JLS 5.2 assignment
     * conversion). Two more shapes fall through that enumeration, both
     * confirmed by repro to break the build:
     * <ul>
     * <li>a DIAMOND {@code new ArrayList<>()} assigned to {@code
     * List<String>} -- {@code var} infers {@code ArrayList<Object>}. The
     * diamond is refused outright rather than by comparing types, because
     * the resolver reports the target-typed answer and so cannot be trusted
     * to reveal the mismatch.
     * <li>an assignment context that applies a narrowing conversion --
     * {@code byte b = 5;} extracting {@code 5} yields {@code var t = 5;} of
     * type {@code int}, and {@code byte b = t;} is then a lossy conversion.
     * </ul>
     */
    @Guard(Check.REJECT_TARGET_TYPED_INITIALIZER)
    private static void rejectTargetTypedInitializer(Expression target) {
        if (target instanceof com.github.javaparser.ast.expr.ObjectCreationExpr creation
                && creation.getType().getTypeArguments().map(List::isEmpty).orElse(false)) {
            throw new RefactorException(Check.REJECT_TARGET_TYPED_INITIALIZER, "Cannot extract a diamond ('new "
                    + creation.getType().getNameAsString() + "<>(...)'): its type arguments are inferred from the "
                    + "context it sits in, and 'var' would infer them from the expression alone (typically as "
                    + "'Object'), which generally won't compile in the original position.");
        }
        if (target instanceof com.github.javaparser.ast.expr.MethodCallExpr call
                && returnTypeDependsOnInference(call)) {
            throw new RefactorException(Check.REJECT_TARGET_TYPED_INITIALIZER, "Cannot extract '" + call + "': it's a generic method call whose type "
                    + "arguments are inferred from the context it sits in, and 'var' would infer them from the "
                    + "call alone (typically as 'Object'), which generally won't compile in the original "
                    + "position.");
        }
        if (returnsFromLambdaBody(target)) {
            throw new RefactorException(Check.REJECT_TARGET_TYPED_INITIALIZER, "Cannot extract this expression: it's the value of a 'return' inside a "
                    + "lambda body, and the type expected there comes from the lambda's functional interface, "
                    + "which isn't determinable here. A 'var' would capture the expression's own type instead, "
                    + "which may not be compatible.");
        }
        expectedTypeOf(target).ifPresent(expected -> {
            ResolvedType actual;
            try {
                actual = target.calculateResolvedType();
            } catch (RuntimeException e) {
                return;
            }
            if (!expected.isAssignableBy(actual)) {
                throw new RefactorException(Check.REJECT_TARGET_TYPED_INITIALIZER, "Cannot extract this expression: its own type ('" + actual.describe()
                        + "') isn't assignable to the type expected here ('" + expected.describe() + "'), which "
                        + "means the surrounding code relies on a conversion applied in THIS position. A 'var' "
                        + "declaration would capture the expression's own type instead, so the original position "
                        + "would no longer compile.");
            }
        });
    }

    /**
     * The type the context expects of {@code target}, where that is
     * determinable -- currently a variable initializer or the right-hand
     * side of a plain assignment. Empty elsewhere (an argument position, a
     * return, ...), which simply means {@link #rejectTargetTypedInitializer}
     * makes no claim there.
     */
    /**
     * Is {@code target} the value of a {@code return} whose nearest
     * enclosing callable is a LAMBDA rather than a method?
     *
     * <p>Refused rather than merely left unchecked. An earlier fix here made
     * {@link #expectedTypeOf} answer "no claim" for this case, which
     * corrected a wrong-reason over-refusal but left the real hazard open:
     * the expected type comes from the lambda's functional interface, which
     * this refactor cannot determine, so a {@code var} capturing the
     * expression's own type can be incompatible. Confirmed by repro --
     * extracting {@code 5} from {@code Supplier<Byte> s = () -> { return 5;
     * }} produced a lambda body javac rejects.
     */
    private static boolean returnsFromLambdaBody(Expression target) {
        if (!(target.getParentNode().orElse(null) instanceof com.github.javaparser.ast.stmt.ReturnStmt)) {
            return false;
        }
        for (Node up = target; up != null; up = up.getParentNode().orElse(null)) {
            if (up instanceof LambdaExpr) {
                return true;
            }
            if (up instanceof CallableDeclaration) {
                return false;
            }
        }
        return false;
    }

    /**
     * Does {@code call}'s return type mention one of the method's OWN type
     * parameters? If so its type arguments are inferred, and in an
     * assignment context they are inferred from the TARGET type -- so
     * {@code List<String> l = Collections.emptyList();} works while
     * {@code var t = Collections.emptyList();} infers {@code List<Object>}.
     *
     * <p>Checked structurally rather than by comparing resolved types, for
     * exactly the reason the diamond is: the resolver reports the
     * target-typed answer, so a type comparison sees {@code List<String>} on
     * both sides and finds nothing wrong. Confirmed by repro for {@code
     * Collections.emptyList()}, {@code Optional.empty()} and {@code
     * List.of()}, all of which previously emitted uncompilable code.
     */
    private static boolean returnTypeDependsOnInference(com.github.javaparser.ast.expr.MethodCallExpr call) {
        if (call.getTypeArguments().isPresent()) {
            // Explicit witness (`Collections.<String>emptyList()`) -- nothing
            // is left to infer from context.
            return false;
        }
        try {
            var resolved = call.resolve();
            if (resolved.getTypeParameters().isEmpty()) {
                return false;
            }
            // Only the type parameters that CANNOT be pinned by an argument
            // matter. `Collections.emptyList()` infers T purely from the
            // target type, so `var` gets Object -- but
            // `Objects.requireNonNull(raw)` and `Optional.of(s)` infer T from
            // the argument, and `var` gets exactly the right type. An earlier
            // version tested "return type mentions any own type parameter",
            // which refused that entire (large, completely safe) population.
            Set<String> unpinned = new HashSet<>();
            resolved.getTypeParameters().forEach(tp -> unpinned.add(tp.getName()));
            for (int i = 0; i < resolved.getNumberOfParams(); i++) {
                ResolvedType paramType = resolved.getParam(i).getType();
                unpinned.removeIf(n -> mentionsAny(paramType, Set.of(n)));
            }
            return mentionsAny(resolved.getReturnType(), unpinned);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean mentionsAny(ResolvedType type, Set<String> typeParameterNames) {
        if (type.isTypeVariable()) {
            return typeParameterNames.contains(type.asTypeVariable().describe());
        }
        if (type.isArray()) {
            return mentionsAny(type.asArrayType().getComponentType(), typeParameterNames);
        }
        if (type.isReferenceType()) {
            return type.asReferenceType().typeParametersValues().stream()
                    .anyMatch(t -> mentionsAny(t, typeParameterNames));
        }
        return false;
    }

    /**
     * The type the context expects of {@code target}, where that is
     * determinable: a variable initializer, the right-hand side of a plain
     * assignment, or a {@code return}. Empty elsewhere, which simply means
     * {@link #rejectTargetTypedInitializer} makes no claim there.
     *
     * <p><b>An ARGUMENT position is deliberately not covered, because there
     * is nothing there to catch.</b> If the call RESOLVES, the resolver
     * already found the method applicable to that argument type, so the
     * conversion in play is identity, widening or boxing -- all of which
     * survive an intermediate {@code var} -- and {@code isAssignableBy}
     * would say yes anyway. The branch could only ever have been dead code,
     * or a false positive on boxing.
     *
     * <p>And the case it might look like it should catch does not exist:
     * {@code takesByte(5)} is not legal Java in the first place. Method
     * invocation conversion (JLS 5.3) deliberately EXCLUDES the constant
     * narrowing that assignment conversion (JLS 5.2) permits, so javac
     * rejects it with "possible lossy conversion from int to byte" before
     * any refactoring is involved. An earlier version of this note claimed
     * JavaParser was deficient here and that a narrowing-in-argument-
     * position gap remained; both were wrong, and a phantom gap in the
     * record invites someone to "close" it later.
     */
    private static Optional<ResolvedType> expectedTypeOf(Expression target) {
        Node parent = target.getParentNode().orElse(null);
        try {
            if (parent instanceof VariableDeclarator declarator && declarator.getInitializer()
                    .map(init -> init == target).orElse(false)) {
                if (declarator.getType().isVarType()) {
                    return Optional.empty();
                }
                return Optional.of(declarator.getType().resolve());
            }
            if (parent instanceof AssignExpr assign
                    && assign.getOperator() == AssignExpr.Operator.ASSIGN
                    && assign.getValue() == target) {
                return Optional.of(assign.getTarget().calculateResolvedType());
            }
            // `return 5;` in a byte-returning method applies the same
            // assignment conversion a `byte b = 5;` initializer does.
            if (parent instanceof com.github.javaparser.ast.stmt.ReturnStmt) {
                // Walk up manually rather than findAncestor(CallableDeclaration):
                // a LambdaExpr is NOT a CallableDeclaration, so findAncestor
                // sails straight past a block-bodied lambda and answers with
                // the ENCLOSING METHOD's return type. Confirmed by repro in
                // both directions -- it let an int be extracted out of a
                // Supplier<Byte> lambda (uncompilable), and refused a valid
                // extraction citing an unrelated method's return type.
                for (Node up = target; up != null; up = up.getParentNode().orElse(null)) {
                    if (up instanceof LambdaExpr) {
                        return Optional.empty();
                    }
                    if (up instanceof com.github.javaparser.ast.body.MethodDeclaration method) {
                        return Optional.of(method.getType().resolve());
                    }
                    if (up instanceof CallableDeclaration) {
                        return Optional.empty();
                    }
                }
                return Optional.empty();
            }
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    @Guard(Check.REJECT_UNSUITABLE_INITIALIZER)
    private static void rejectUnsuitableInitializer(Expression target) {
        if (target instanceof NullLiteralExpr) {
            throw new RefactorException(Check.REJECT_UNSUITABLE_INITIALIZER, "Cannot extract a bare 'null' literal: 'var x = null;' isn't legal Java "
                    + "-- 'var' can't infer a type from it. Not supported in this version.");
        }
        if (target instanceof LambdaExpr) {
            throw new RefactorException(Check.REJECT_UNSUITABLE_INITIALIZER, "Cannot extract a lambda expression on its own: 'var' can't infer a "
                    + "functional interface type without a target type. Not supported in this version.");
        }
        if (target instanceof MethodReferenceExpr) {
            throw new RefactorException(Check.REJECT_UNSUITABLE_INITIALIZER, "Cannot extract a bare method reference on its own: 'var' can't infer a "
                    + "functional interface type without a target type. Not supported in this version.");
        }
        if (target instanceof AnnotationExpr) {
            throw new RefactorException(Check.REJECT_UNSUITABLE_INITIALIZER, "Cannot extract an annotation: it isn't a value-producing expression.");
        }
        if (target instanceof VariableDeclarationExpr) {
            throw new RefactorException(Check.REJECT_UNSUITABLE_INITIALIZER, "Cannot extract a variable declaration: it isn't a value-producing "
                    + "expression on its own.");
        }
    }

    /**
     * Refuses to extract a position whose enclosing statement RE-EVALUATES
     * it on every iteration -- a loop's own condition/update, unlike an
     * ordinary statement's expression, isn't evaluated once and done, so
     * hoisting it to a one-time {@code var} before the loop would freeze a
     * value that's supposed to change (silently turning, say, {@code while
     * (queue.size() > 0)} into an infinite loop). The loop's INITIALIZATION
     * (a {@code ForStmt}'s {@code getInitialization()}) is deliberately not
     * included here -- it only runs once, at the same point in time hoisting
     * would move it to, so it's just as extractable as an ordinary statement
     * (see {@link #rejectForwardReference} for the separate scoping risk a
     * for-init can introduce).
     */
    @Guard(Check.REJECT_RECURRING_CONTROL_POSITION)
    private static void rejectRecurringControlPosition(Expression target, Statement anchorStatement) {
        if (anchorStatement instanceof ForStmt forStmt) {
            if (isWithin(target, forStmt.getCompare().orElse(null))
                    || forStmt.getUpdate().stream().anyMatch(u -> isWithin(target, u))) {
                throw recurringControlException("a for-loop's condition or update expression");
            }
        } else if (anchorStatement instanceof WhileStmt whileStmt && isWithin(target, whileStmt.getCondition())) {
            throw recurringControlException("a while-loop's condition");
        } else if (anchorStatement instanceof DoStmt doStmt && isWithin(target, doStmt.getCondition())) {
            throw recurringControlException("a do-while loop's condition");
        }
    }

    private static RefactorException recurringControlException(String where) {
        return new RefactorException(Check.REJECT_RECURRING_CONTROL_POSITION,
                "Cannot extract this expression: it's " + where + ", which is re-evaluated "
                + "on every iteration -- hoisting it to a one-time variable before the loop would freeze its value "
                + "instead, changing behavior.");
    }

    /** Is {@code node} equal to, or nested inside, {@code subtreeRoot}? */
    private static boolean isWithin(Node node, Node subtreeRoot) {
        if (subtreeRoot == null) {
            return false;
        }
        Node current = node;
        while (true) {
            if (current == subtreeRoot) {
                return true;
            }
            Optional<Node> parent = current.getParentNode();
            if (parent.isEmpty()) {
                return false;
            }
            current = parent.get();
        }
    }

    /**
     * Refuses to extract from inside an {@code assert} statement -- both the
     * checked condition and (especially) the message are evaluated
     * conditionally in ways a plain hoisted {@code var} can't replicate: the
     * message only runs when the assertion actually fails, and assertions
     * themselves are routinely disabled entirely at runtime (the JVM default
     * is {@code -da}), in which case NEITHER the condition nor the message
     * evaluates at all -- a hoisted {@code var} would evaluate unconditionally
     * either way.
     */
    @Guard(Check.REJECT_ASSERT_POSITION)
    private static void rejectAssertPosition(Statement anchorStatement) {
        if (anchorStatement instanceof AssertStmt) {
            throw new RefactorException(Check.REJECT_ASSERT_POSITION, "Cannot extract this expression: it's part of an 'assert' statement's "
                    + "condition or message, both of which only evaluate conditionally (the message only on "
                    + "failure, and neither at all when assertions are disabled, the JVM default) -- hoisting it "
                    + "into an unconditional variable would change when it runs.");
        }
    }

    /**
     * Refuses to extract an expression that references a name declared
     * elsewhere WITHIN the same enclosing statement (a for-loop's own
     * {@code int i = 0} init variable used in its condition, or an earlier
     * declarator in a multi-variable declaration like {@code int x = 5, y =
     * x + 1;}) -- hoisting the expression to before the whole statement
     * would place the reference before its own declaration, an illegal
     * forward reference.
     */
    @Guard(Check.REJECT_FORWARD_REFERENCE)
    private static void rejectForwardReference(Expression target, Statement anchorStatement) {
        Set<String> declaredInStatement = new HashSet<>();
        anchorStatement.findAll(VariableDeclarator.class).forEach(vd -> declaredInStatement.add(vd.getNameAsString()));
        anchorStatement.findAll(Parameter.class).forEach(p -> declaredInStatement.add(p.getNameAsString()));
        anchorStatement.findAll(TypePatternExpr.class).forEach(p -> declaredInStatement.add(p.getNameAsString()));
        if (declaredInStatement.isEmpty()) {
            return;
        }
        boolean referencesOwnDeclaration = target.findAll(NameExpr.class).stream()
                .anyMatch(n -> declaredInStatement.contains(n.getNameAsString()));
        if (referencesOwnDeclaration) {
            throw new RefactorException(Check.REJECT_FORWARD_REFERENCE, "Cannot extract this expression: it references a name declared elsewhere "
                    + "in the same statement (e.g. a for-loop's own init variable, or an earlier variable in the "
                    + "same declaration) -- hoisting it before the whole statement would reference that name "
                    + "before its declaration.");
        }
    }

    /**
     * Refuses to extract an expression that IS the write target of an
     * assignment or a {@code ++}/{@code --} -- replacing it with a plain
     * read reference would silently turn the write into a no-op (e.g.
     * {@code field = 5;} would become {@code var v = field; v = 5;}, which
     * never actually assigns {@code field}). A sub-part of the target (e.g.
     * the index expression in {@code arr[i] = 7}) is unaffected and remains
     * extractable -- this only refuses when {@code target} IS the exact
     * write-target node, not merely nested somewhere inside it.
     */
    @Guard(Check.REJECT_LVALUE_POSITION)
    private static void rejectLvaluePosition(Expression target) {
        Node parent = target.getParentNode().orElse(null);
        if (parent instanceof AssignExpr assign && assign.getTarget() == target) {
            throw new RefactorException(Check.REJECT_LVALUE_POSITION, "Cannot extract this expression: it's the left-hand side of an "
                    + "assignment -- replacing it with a variable reference would turn the assignment into a "
                    + "no-op instead of actually writing to it.");
        }
        if (parent instanceof UnaryExpr unary && unary.getExpression() == target && isIncrementOrDecrement(unary)) {
            throw new RefactorException(Check.REJECT_LVALUE_POSITION, "Cannot extract this expression: it's the operand of '"
                    + unary.getOperator().asString() + "' -- replacing it with a variable reference would make "
                    + "the increment/decrement apply to the new variable instead of the original.");
        }
    }

    private static boolean isIncrementOrDecrement(UnaryExpr unary) {
        return switch (unary.getOperator()) {
            case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> true;
            default -> false;
        };
    }

    /**
     * Refuses to extract an expression that isn't a value at all, or whose
     * value is {@code void} -- {@code var} requires a real, typed value.
     * Structurally-wrong node kinds ({@link AnnotationExpr}, a whole
     * {@link VariableDeclarationExpr}) are already caught by
     * {@link #rejectUnsuitableInitializer}; this catches the remaining case
     * that needs actual type resolution: a {@code void}-returning method
     * call (e.g. {@code System.out.println(...)}), which is structurally a
     * completely ordinary-looking {@link Expression} and would otherwise
     * slip through every other check. Resolution failures are also refused
     * rather than assumed safe, consistent with this tool's "fail loudly
     * rather than guess" approach elsewhere.
     */
    /**
     * Refuses an anchor statement this refactor cannot insert before: the
     * body of an expression-bodied lambda (evaluation is deferred there), or
     * anything that isn't a direct child of a {@code { }} block.
     *
     * <p>Extracted from an inline {@code throw} so {@code
     * tools/mutation-check.sh} can reach it -- that script only mutates
     * calls to named {@code reject*}/{@code verify*} methods, so a refusal
     * written inline is invisible to it and its coverage report silently
     * excluded this one.
     */
    @Guard(Check.REJECT_UNANCHORABLE_STATEMENT)
    private static void rejectUnanchorableStatement(Statement anchorStatement) {
        if (anchorStatement.getParentNode().orElse(null) instanceof LambdaExpr lambda
                && lambda.getExpressionBody().isPresent()) {
            throw new RefactorException(Check.REJECT_UNANCHORABLE_STATEMENT, "Cannot extract this expression: it's (part of) the body of an "
                    + "expression-bodied lambda, so its evaluation is deferred to when the lambda is invoked -- "
                    + "hoisting it out to an enclosing statement would evaluate it eagerly instead, changing "
                    + "behavior.");
        }
        if (!(anchorStatement.getParentNode().orElse(null) instanceof BlockStmt)) {
            throw new RefactorException(Check.REJECT_UNANCHORABLE_STATEMENT, "The statement containing this expression isn't a direct child of a "
                    + "{ } block (likely a braceless if/while/for body) -- add braces around it first.");
        }
    }

    /**
     * Refuses when the anchor statement doesn't start its own source line,
     * since the new declaration's indentation is copied verbatim from it.
     */
    @Guard(Check.REJECT_STATEMENT_NOT_AT_LINE_START)
    private static void rejectStatementNotAtLineStart(String indent) {
        if (!indent.isBlank()) {
            throw new RefactorException(Check.REJECT_STATEMENT_NOT_AT_LINE_START, "The statement containing this expression isn't the first thing on its "
                    + "source line -- extract-variable needs to copy its indentation, so move it to its own line "
                    + "first.");
        }
    }

    @Guard(Check.REJECT_NON_VALUE_EXPRESSION)
    private static void rejectNonValueExpression(Expression target) {
        try {
            if (target.calculateResolvedType().isVoid()) {
                throw new RefactorException(Check.REJECT_NON_VALUE_EXPRESSION, "Cannot extract this expression: it has type 'void', so it doesn't "
                        + "produce a value 'var' could hold.");
            }
        } catch (RefactorException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RefactorException(Check.REJECT_NON_VALUE_EXPRESSION, "Could not determine this expression's type (" + e.getMessage()
                    + ") -- refusing rather than risk extracting something that isn't a proper value.", e);
        }
    }

    /** One replacement over a half-open range of the original text. */
    private record TextEdit(int start, int end, String replacement) {
    }

    /**
     * Applies edits back-to-front so each one's offsets stay valid.
     *
     * <p>Back-to-front is the whole trick: applying forwards shifts every
     * later offset by the length delta, which is the classic way a
     * multi-edit splice lands one edit in the wrong place. Ties are ordered
     * so an INSERTION at a position lands before a replacement starting
     * there, which is what puts the new declaration line above the
     * statement it is anchored to.
     */
    private static String applyEdits(String text, List<TextEdit> edits) {
        List<TextEdit> ordered = new ArrayList<>(edits);
        ordered.sort(java.util.Comparator.comparingInt(TextEdit::start)
                .thenComparingInt(TextEdit::end).reversed());
        StringBuilder out = new StringBuilder(text);
        for (TextEdit edit : ordered) {
            out.replace(edit.start(), edit.end(), edit.replacement());
        }
        return out.toString();
    }

    /**
     * Proves every reference the new local would have shadowed came out
     * qualified.
     *
     * <p>Textual, and it has to be: this refactor never mutates the AST, so
     * there is no rewritten node to resolve. It checks the produced source
     * contains each qualified form and no longer contains a bare occurrence
     * that the local would capture -- the strongest statement available
     * without re-parsing, which would resolve against the pre-edit file on
     * disk anyway (CLAUDE.md).
     */
    @Guard(Check.VERIFY_SHADOWED_FIELD_REFERENCES)
    private static void verifyShadowedFieldReferences(Map<NameExpr, String> shadowed, String updated, String name) {
        for (String qualified : new LinkedHashSet<>(shadowed.values())) {
            // The form must actually BE a qualification. Checking only that
            // the text is present is vacuous when the "qualified" form is
            // the bare name: the name is obviously still there, the repair
            // reports success, and the local captures the field exactly as
            // it would have without any repair at all. Fault injection with
            // an empty qualifier walked straight through the presence check
            // -- the quietest way a repair can fail is to change nothing.
            if (!qualified.endsWith("." + name) || qualified.length() <= name.length() + 1) {
                throw new RefactorException(Check.VERIFY_SHADOWED_FIELD_REFERENCES, "Refusing to introduce '"
                        + name + "': the escape form '" + qualified + "' does not qualify '" + name
                        + "' at all, so the new variable would still shadow the field. Nothing has been written.");
            }
            if (!updated.contains(qualified)) {
                throw new RefactorException(Check.VERIFY_SHADOWED_FIELD_REFERENCES, "Refusing to introduce '"
                        + name + "': a use of the field it shadows had to be written as '" + qualified
                        + "' to keep reaching the field, and the result does not contain it. Nothing has been "
                        + "written.");
            }
        }
        // Count what the repair claimed against what the text shows, so a
        // reference the plan silently dropped cannot pass unnoticed -- the
        // failure mode fault injection found in rename-class's verifier.
        long expected = shadowed.size();
        long present = shadowed.values().stream()
                .mapToLong(qualified -> countOccurrences(updated, qualified))
                .sum();
        if (present < expected) {
            throw new RefactorException(Check.VERIFY_SHADOWED_FIELD_REFERENCES, "Refusing to introduce '" + name
                    + "': " + expected + " use(s) of the shadowed field needed qualifying, but the result "
                    + "contains only " + present + ". Nothing has been written.");
        }
    }

    private static long countOccurrences(String text, String needle) {
        long count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    /**
     * Refuses only the half of a name collision that has no explicit form:
     * the name already belongs to a LOCAL or PARAMETER in an enclosing
     * scope.
     *
     * <p>Two locals of one name in overlapping scopes is a compile error
     * (JLS 6.4), and a local cannot be qualified -- there is no
     * {@code this.x} for it. Rule (b) in CLAUDE.md: no fact at the site
     * would let a repair name the one that is being hidden.
     *
     * <p>The FIELD half used to be refused here too and is now repaired --
     * see {@link #planShadowedFieldReferences}.
     */
    @Guard(Check.REJECT_LOCAL_NAME_COLLISION)
    private static void rejectLocalNameCollision(Expression target, String name) {
        for (Node scopeRoot : allEnclosingScopeRoots(target)) {
            boolean collides = scopeRoot.findAll(VariableDeclarator.class).stream()
                    .anyMatch(vd -> vd.getNameAsString().equals(name))
                    || scopeRoot.findAll(Parameter.class).stream()
                    .anyMatch(p -> p.getNameAsString().equals(name));
            if (collides) {
                throw new RefactorException(Check.REJECT_LOCAL_NAME_COLLISION, "Cannot introduce a variable named '"
                        + name + "': an enclosing method/constructor/initializer/lambda already has a local "
                        + "variable or parameter with that name.");
            }
        }
    }

    /**
     * The references to a FIELD that the new local would shadow, each with
     * the qualified text that keeps it reaching that field.
     *
     * <p>The hazard, confirmed by repro with {@code static int limit = 5;}:
     * extracting {@code compute(3)} as {@code --name limit} left a trailing
     * {@code println("limit=" + limit)} printing 6 instead of 5, compiling
     * cleanly. The new local shadows the field for the whole rest of the
     * block.
     *
     * <p>A binding hazard, so per CLAUDE.md the repair case: those uses get
     * {@code this.limit} or {@code Config.limit} and keep meaning the field.
     *
     * <p><b>This edits code the user did not point at.</b> extract-variable
     * addresses ONE expression by position, and the repair rewrites other
     * statements in the same block. That is a deliberate product decision,
     * the same one rename-field's capture repair already makes, and the
     * alternative is refusing an extraction over a name the file happens to
     * use elsewhere.
     *
     * <p>Only references at or after the anchor statement are touched. The
     * local's scope starts at its declaration, so an earlier use of the
     * name is not shadowed and must be left exactly as it is.
     */
    private static Map<NameExpr, String> planShadowedFieldReferences(ProjectContext ctx, Expression target,
                                                                       Statement anchorStatement, String name) {
        Optional<ResolvedDeclaration> bound =
                NameBindingChecker.valueDeclarationAt(ctx.typeSolver(), target, name);
        if (bound.isEmpty()) {
            return Map.of();
        }
        if (!(bound.get() instanceof ResolvedFieldDeclaration field)) {
            // Not a field and not a local either (rejectLocalNameCollision
            // ran first) -- an enum constant, say. No qualified form.
            throw new RefactorException(Check.REJECT_NAME_COLLISION, "Cannot introduce a variable named '" + name
                    + "': that name already means " + NameBindingChecker.describeOf(bound.get())
                    + " here, which is not a field and so cannot be reached by any qualified form.");
        }

        Node scope = anchorStatement.getParentNode().orElse(anchorStatement);
        Position from = anchorStatement.getBegin()
                .orElseThrow(() -> new RefactorException(Check.INTERNAL_ERROR,
                        "Internal error: enclosing statement has no position"));
        String owner = field.declaringType().getQualifiedName();
        String qualifier = field.isStatic() ? field.declaringType().getName() + "." : "this.";

        Map<NameExpr, String> shadowed = new LinkedHashMap<>();
        for (NameExpr reference : scope.findAll(NameExpr.class)) {
            if (!reference.getNameAsString().equals(name)) {
                continue;
            }
            if (reference.getBegin().filter(at -> at.isBefore(from)).isPresent()) {
                continue;
            }
            try {
                ResolvedValueDeclaration resolved = reference.resolve();
                if (resolved instanceof ResolvedFieldDeclaration f
                        && f.declaringType().getQualifiedName().equals(owner)
                        && f.getName().equals(name)) {
                    shadowed.put(reference, qualifier + name);
                }
            } catch (RuntimeException e) {
                throw new RefactorException(Check.SHADOWED_FIELD_REFERENCE_UNRESOLVABLE, "Cannot introduce a variable "
                        + "named '" + name + "': an existing '" + name + "' reference at "
                        + reference.getBegin().map(Object::toString).orElse("?") + " could not be resolved ("
                        + e.getMessage() + "), so whether the new variable would shadow it cannot be established.");
            }
        }
        return shadowed;
    }

    /**
     * Every enclosing method/constructor/initializer/lambda body that scopes
     * local names for {@code node} -- ALL of them, walking outward past each
     * one found rather than stopping at the nearest, so an outer method's
     * captured locals aren't missed when {@code node} is inside a nested
     * lambda or anonymous/local class (see {@link #rejectNameCollision}).
     * Same approach as {@code RenameFieldRefactor.enclosingScopeRoots}.
     */
    private static List<Node> allEnclosingScopeRoots(Node node) {
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

    /** Converts a 1-based (line, column) position into a 0-based char offset into {@code content}. */
    private static int offsetOf(String content, int line, int column) {
        return offsetOfLineStart(content, line) + (column - 1);
    }

    /** Char offset of the first character of {@code line} (1-based) in {@code content}. */
    private static int offsetOfLineStart(String content, int line) {
        int offset = 0;
        for (int currentLine = 1; currentLine < line; currentLine++) {
            int newline = content.indexOf('\n', offset);
            if (newline < 0) {
                throw new RefactorException(Check.INTERNAL_ERROR, "Internal error: line " + line + " is past the end of the file");
            }
            offset = newline + 1;
        }
        return offset;
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
            throw new RefactorException(Check.WRITE_FAILED, "Failed to write changes: " + e.getMessage(), e);
        }
    }
}
