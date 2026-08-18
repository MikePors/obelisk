package dev.obelisk.core.refactor;

import com.github.javaparser.Position;
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
                .orElseThrow(() -> new RefactorException("The expression at " + start + "-" + end
                        + " isn't inside a method/constructor/initializer body (e.g. it's a field initializer, "
                        + "annotation argument, or type-level construct) -- extract-variable only supports "
                        + "expressions inside a statement."));

        rejectUnhoistablePosition(target, anchorStatement);
        rejectRecurringControlPosition(target, anchorStatement);
        rejectAssertPosition(anchorStatement);
        rejectForwardReference(target, anchorStatement);
        rejectLvaluePosition(target);
        rejectUnsuitableInitializer(target);

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
        if (anchorStatement.getParentNode().orElse(null) instanceof LambdaExpr lambda
                && lambda.getExpressionBody().isPresent()) {
            throw new RefactorException("Cannot extract this expression: it's (part of) the body of an "
                    + "expression-bodied lambda, so its evaluation is deferred to when the lambda is invoked -- "
                    + "hoisting it out to an enclosing statement would evaluate it eagerly instead, changing "
                    + "behavior.");
        }

        if (!(anchorStatement.getParentNode().orElse(null) instanceof BlockStmt)) {
            throw new RefactorException("The statement containing this expression isn't a direct child of a "
                    + "{ } block (likely a braceless if/while/for body) -- add braces around it first.");
        }

        rejectNonValueExpression(target);
        rejectNameCollision(target, name);

        Position anchorBegin = anchorStatement.getBegin()
                .orElseThrow(() -> new RefactorException("Internal error: enclosing statement has no position"));

        String original = readOriginal(resolvedFile);
        int lineStart = offsetOfLineStart(original, anchorBegin.line);
        int anchorOffset = offsetOf(original, anchorBegin.line, anchorBegin.column);
        String indent = original.substring(lineStart, anchorOffset);
        if (!indent.isBlank()) {
            throw new RefactorException("The statement containing this expression isn't the first thing on its "
                    + "source line -- extract-variable needs to copy its indentation, so move it to its own line "
                    + "first.");
        }

        int exprStart = offsetOf(original, start.line, start.column);
        int exprEndExclusive = offsetOf(original, end.line, end.column) + 1;
        String exprText = original.substring(exprStart, exprEndExclusive);

        // Match the file's existing line-ending style rather than hardcoding
        // '\n', so a CRLF file doesn't end up with one lone LF-terminated
        // line among otherwise-CRLF ones.
        String lineEnding = original.contains("\r\n") ? "\r\n" : "\n";

        String updated = original.substring(0, lineStart)
                + indent + "var " + name + " = " + exprText + ";" + lineEnding
                + original.substring(lineStart, exprStart)
                + name
                + original.substring(exprEndExclusive);

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
            throw new RefactorException("'" + name + "' is not a valid Java variable name");
        }
    }

    private static Path resolveFile(ProjectContext ctx, Path file) {
        Path absolute = file.isAbsolute() ? file.normalize() : ctx.projectDir().resolve(file).normalize();
        if (!ctx.unitsByFile().containsKey(absolute)) {
            throw new RefactorException("'" + file + "' is not one of the project's parsed source files "
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
        throw new RefactorException("No expression found starting at " + start + " and ending at " + end
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
    private static void rejectUnhoistablePosition(Expression target, Statement anchorStatement) {
        Node current = target;
        while (current != anchorStatement) {
            Node parent = current.getParentNode()
                    .orElseThrow(() -> new RefactorException("Internal error: lost the path from the target "
                            + "expression to its enclosing statement"));
            if (parent instanceof LambdaExpr) {
                throw new RefactorException("Cannot extract this expression: it's the (non-block) body of a "
                        + "lambda, so its evaluation is deferred to when the lambda is invoked -- hoisting it out "
                        + "to the enclosing statement would evaluate it eagerly instead, changing behavior.");
            }
            if (parent instanceof BinaryExpr binary
                    && (binary.getOperator() == BinaryExpr.Operator.AND || binary.getOperator() == BinaryExpr.Operator.OR)
                    && binary.getRight() == current) {
                throw new RefactorException("Cannot extract this expression: it's the right-hand side of a "
                        + "short-circuit " + binary.getOperator().asString() + " and may not currently evaluate at "
                        + "all -- extracting it would evaluate it unconditionally instead, changing behavior.");
            }
            if (parent instanceof ConditionalExpr ternary
                    && (ternary.getThenExpr() == current || ternary.getElseExpr() == current)) {
                throw new RefactorException("Cannot extract this expression: it's a branch of a ternary and only "
                        + "evaluates conditionally -- extracting it would evaluate it unconditionally instead, "
                        + "changing behavior.");
            }
            current = parent;
        }
    }

    private static void rejectUnsuitableInitializer(Expression target) {
        if (target instanceof NullLiteralExpr) {
            throw new RefactorException("Cannot extract a bare 'null' literal: 'var x = null;' isn't legal Java "
                    + "-- 'var' can't infer a type from it. Not supported in this version.");
        }
        if (target instanceof LambdaExpr) {
            throw new RefactorException("Cannot extract a lambda expression on its own: 'var' can't infer a "
                    + "functional interface type without a target type. Not supported in this version.");
        }
        if (target instanceof MethodReferenceExpr) {
            throw new RefactorException("Cannot extract a bare method reference on its own: 'var' can't infer a "
                    + "functional interface type without a target type. Not supported in this version.");
        }
        if (target instanceof AnnotationExpr) {
            throw new RefactorException("Cannot extract an annotation: it isn't a value-producing expression.");
        }
        if (target instanceof VariableDeclarationExpr) {
            throw new RefactorException("Cannot extract a variable declaration: it isn't a value-producing "
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
        return new RefactorException("Cannot extract this expression: it's " + where + ", which is re-evaluated "
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
    private static void rejectAssertPosition(Statement anchorStatement) {
        if (anchorStatement instanceof AssertStmt) {
            throw new RefactorException("Cannot extract this expression: it's part of an 'assert' statement's "
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
            throw new RefactorException("Cannot extract this expression: it references a name declared elsewhere "
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
    private static void rejectLvaluePosition(Expression target) {
        Node parent = target.getParentNode().orElse(null);
        if (parent instanceof AssignExpr assign && assign.getTarget() == target) {
            throw new RefactorException("Cannot extract this expression: it's the left-hand side of an "
                    + "assignment -- replacing it with a variable reference would turn the assignment into a "
                    + "no-op instead of actually writing to it.");
        }
        if (parent instanceof UnaryExpr unary && unary.getExpression() == target && isIncrementOrDecrement(unary)) {
            throw new RefactorException("Cannot extract this expression: it's the operand of '"
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
    private static void rejectNonValueExpression(Expression target) {
        try {
            if (target.calculateResolvedType().isVoid()) {
                throw new RefactorException("Cannot extract this expression: it has type 'void', so it doesn't "
                        + "produce a value 'var' could hold.");
            }
        } catch (RefactorException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RefactorException("Could not determine this expression's type (" + e.getMessage()
                    + ") -- refusing rather than risk extracting something that isn't a proper value.", e);
        }
    }

    /**
     * Refuses to introduce {@code name} if it would collide with an
     * existing local variable or parameter already in scope -- checks EVERY
     * enclosing method/constructor/initializer/lambda body reachable by
     * walking outward from the target, not just the nearest one: an
     * anonymous or local class's method is itself a {@link
     * CallableDeclaration}, so a naive nearest-only lookup starting from a
     * reference inside one would stop there and miss an OUTER method's
     * captured parameter/local of the same name (confirmed via repro: an
     * anonymous {@code Runnable}'s method referencing an outer method's
     * {@code total} parameter, extracting a sub-expression as {@code --name
     * total}, produced a self-referential {@code var total = total + 1;}
     * with no collision reported). A plain lambda doesn't have this gap on
     * its own since {@link LambdaExpr} isn't a {@link CallableDeclaration}
     * -- but the same broadened walk is used uniformly rather than trying to
     * special-case which boundary actually needed it. A same-named field is
     * deliberately left unchecked (a local shadowing a field is legal Java,
     * just ordinary shadowing, not a compile error).
     */
    private static void rejectNameCollision(Expression target, String name) {
        for (Node scopeRoot : allEnclosingScopeRoots(target)) {
            boolean collides = scopeRoot.findAll(VariableDeclarator.class).stream()
                    .anyMatch(vd -> vd.getNameAsString().equals(name))
                    || scopeRoot.findAll(Parameter.class).stream()
                    .anyMatch(p -> p.getNameAsString().equals(name));
            if (collides) {
                throw new RefactorException("Cannot introduce a variable named '" + name + "': an enclosing "
                        + "method/constructor/initializer/lambda already has a local variable or parameter with "
                        + "that name.");
            }
        }
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
                throw new RefactorException("Internal error: line " + line + " is past the end of the file");
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
            throw new RefactorException("Failed to write changes: " + e.getMessage(), e);
        }
    }
}
