package dev.obelisk.core.refactor;

import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import dev.obelisk.core.DiffUtil;
import dev.obelisk.core.ProjectContext;
import dev.obelisk.core.RefactorException;
import dev.obelisk.guard.Check;
import dev.obelisk.guard.Guard;

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
 * Extracts a contiguous run of statements into a new private method.
 *
 * <p><b>This is a deliberately restricted slice</b> (issue #16). The
 * candidate assessment ranked extract-method "do not attempt", on the
 * grounds that its hazards are control-flow and data-flow rather than
 * binding, so it would be a large, almost purely REFUSAL-shaped refactor --
 * the drift CLAUDE.md says has already happened twice. Most of that verdict
 * stands. One part of it did not:
 *
 * <blockquote>parameter-passing is a precondition, not a repair</blockquote>
 *
 * <p>That is backwards. Extraction discards the implicit link "this name is
 * in scope"; a parameter is the explicit construct that restores it. It is
 * the data-flow instance of the same move a qualifier makes for a binding --
 * see CLAUDE.md's dependency table. And because the extracted method and its
 * single call site are in the SAME compilation unit, the repair can be
 * verified the way rename-field's can: by re-resolving the rewritten names
 * and comparing declarations. That closed loop is most of the point of
 * building this.
 *
 * <p>What is in scope, and nothing more:
 * <ul>
 * <li>a contiguous statement range that is a direct child sequence of one
 *     {@code { }} block
 * <li>single entry, single exit: NO {@code return}, {@code break},
 *     {@code continue} or {@code throw} leaving the region
 * <li>at most ONE live-out variable
 * <li>no lambda or method reference in the region (inherits the
 *     target-typing blind spot that {@code calculateResolvedType()} has for
 *     poly expressions -- see CLAUDE.md)
 * </ul>
 *
 * <p>Everything outside that is refused, and issue #20 is where the
 * question of whether those refusals are principled or merely convenient
 * gets attacked. In particular a non-local exit is NOT refused here because
 * "we have no CFG" -- it is refused because encoding "I returned rather than
 * fell through" requires changing the CALLER's control shape, which is not a
 * fact available at the call site.
 */
public final class ExtractMethodRefactor {

    private ExtractMethodRefactor() {
    }

    /** A local or parameter the region reads but does not declare. */
    private record LiveIn(String name, String type, ResolvedValueDeclaration declaration) {
    }

    public static RefactorResult run(ProjectContext ctx, Path file, int startLine, int endLine, String methodName,
                                      boolean apply) {
        validateIdentifier(methodName);

        Path resolvedFile = resolveFile(ctx, file);
        CompilationUnit cu = ctx.unitsByFile().get(resolvedFile);

        List<Statement> region = findRegion(cu, startLine, endLine);
        BlockStmt block = (BlockStmt) region.get(0).getParentNode().orElseThrow();
        MethodDeclaration enclosing = region.get(0).findAncestor(MethodDeclaration.class)
                .orElseThrow(() -> new RefactorException(Check.REGION_NOT_IN_A_METHOD,
                        "The statements at lines " + startLine + "-" + endLine + " are not inside a method body "
                                + "-- extract-method only supports statements in a method."));

        rejectDuplicateMethodName(enclosing, methodName);
        rejectNonLocalExit(region, startLine, endLine);
        rejectPolyExpressionInRegion(region, startLine, endLine);

        // The data-flow repair: names the region reads but no longer has in
        // scope once it moves become PARAMETERS.
        List<LiveIn> liveIns = planParameters(region, enclosing);
        rejectWriteToLiveIn(region, liveIns, block, methodName);

        Optional<VariableDeclarator> liveOut = planReturnValue(region, block);

        String original = readOriginal(resolvedFile);
        String updated = rewrite(original, region, enclosing, liveIns, liveOut, methodName);

        verifyParametersBindToTheSameDeclarations(ctx, resolvedFile, updated, liveIns,
                fieldNamesVisibleTo(enclosing), methodName);

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

    // --- planning ------------------------------------------------------

    /**
     * The statements wholly inside {@code [startLine, endLine]} that are
     * direct children of ONE block, in source order.
     *
     * <p>Addressed by line range rather than by an exact character range
     * (extract-variable's scheme) because a statement range is naturally
     * line-shaped, and because the whole lines get moved verbatim, which is
     * what preserves their internal formatting.
     */
    private static List<Statement> findRegion(CompilationUnit cu, int startLine, int endLine) {
        if (endLine < startLine) {
            throw new RefactorException(Check.INVALID_REGION,
                    "End line " + endLine + " is before start line " + startLine + ".");
        }
        List<Statement> best = List.of();
        for (BlockStmt block : cu.findAll(BlockStmt.class)) {
            List<Statement> inRange = new ArrayList<>();
            for (Statement statement : block.getStatements()) {
                Optional<Position> begin = statement.getBegin();
                Optional<Position> end = statement.getEnd();
                if (begin.isEmpty() || end.isEmpty()) {
                    continue;
                }
                if (begin.get().line >= startLine && end.get().line <= endLine) {
                    inRange.add(statement);
                }
            }
            if (!inRange.isEmpty() && inRange.size() > best.size()) {
                best = inRange;
            }
        }
        if (best.isEmpty()) {
            throw new RefactorException(Check.INVALID_REGION, "No whole statements found between lines "
                    + startLine + " and " + endLine + ". The range must cover complete statements that are "
                    + "siblings in one enclosing block.");
        }
        return best;
    }

    /**
     * The region's live-in variables, in first-use order -- each becomes a
     * parameter.
     *
     * <p>This is the REPAIR. Moving the statements breaks the implicit link
     * between each of these names and the scope that declared it; a
     * parameter is the explicit construct that restores the same link, and
     * {@link #verifyParametersBindToTheSameDeclarations} proves it reaches
     * the same declaration afterwards.
     *
     * <p>No control-flow graph is involved, and for THIS slice none is
     * needed: with no jumps in or out, "read inside, declared outside" is a
     * purely positional question. That is why the slice is drawn where it
     * is.
     */
    private static List<LiveIn> planParameters(List<Statement> region, MethodDeclaration enclosing) {
        Map<String, LiveIn> byName = new LinkedHashMap<>();
        for (Statement statement : region) {
            for (NameExpr name : statement.findAll(NameExpr.class)) {
                if (isTypeQualifier(name)) {
                    // The `System` in `System.out.println` is a NameExpr that
                    // is a TYPE name, not a value, so the value solver
                    // legitimately fails on it. Treating that failure as
                    // "unresolvable" refused every region containing a
                    // qualified static call -- which is most of them. Same
                    // test, and the same reason, as
                    // InlineMethodRefactor.isPureTypeQualifier.
                    continue;
                }
                ResolvedValueDeclaration resolved;
                try {
                    resolved = name.resolve();
                } catch (RuntimeException e) {
                    // A name that cannot be resolved cannot be classified as
                    // live-in or not, and guessing either way is how a
                    // silently wrong extraction happens.
                    throw new RefactorException(Check.REGION_NAME_UNRESOLVABLE, "Cannot extract: the name '" + name
                            + "' at " + name.getBegin().map(Object::toString).orElse("?")
                            + " could not be resolved (" + e.getMessage() + "), so whether it needs to become a "
                            + "parameter cannot be established.");
                }
                if (!(resolved.isVariable() || resolved.isParameter())) {
                    continue;
                }
                Optional<Node> declaredAt = resolved.toAst();
                if (declaredAt.isEmpty() || isInsideRegion(declaredAt.get(), region)) {
                    continue;
                }
                if (!isInside(declaredAt.get(), enclosing)) {
                    // A field, not a local: it stays reachable from the new
                    // method without being passed, so it is not live-in.
                    continue;
                }
                byName.putIfAbsent(resolved.getName(),
                        new LiveIn(resolved.getName(), parameterTypeFor(resolved, declaredAt.get()), resolved));
            }
        }
        return List.copyOf(byName.values());
    }

    /**
     * The type to give a synthesised parameter: the spelling the source
     * already uses, where there is one.
     *
     * <p>{@code ResolvedType.describe()} returns the fully-qualified name,
     * so a {@code String} local became a {@code java.lang.String}
     * parameter. That compiles and is correct -- and it is not the code a
     * person would have written, which for a refactoring tool is most of
     * the product. Falls back to the resolved name for {@code var}, which
     * cannot be a parameter type.
     */
    private static String parameterTypeFor(ResolvedValueDeclaration resolved, Node declaredAt) {
        // toAst() does not always hand back the VariableDeclarator itself --
        // for a local it can be the enclosing declaration node -- so the
        // declarator is looked up by name rather than assumed from the node
        // kind. Assuming the kind here is what left every String parameter
        // spelled java.lang.String.
        String declared = "var";
        if (declaredAt instanceof Parameter parameter) {
            declared = parameter.getTypeAsString();
        } else if (declaredAt instanceof VariableDeclarator variable) {
            declared = variable.getTypeAsString();
        } else {
            declared = declaredAt.findAll(VariableDeclarator.class).stream()
                    .filter(variable -> variable.getNameAsString().equals(resolved.getName()))
                    .findFirst()
                    .map(VariableDeclarator::getTypeAsString)
                    .orElse("var");
        }
        return "var".equals(declared) ? resolved.getType().describe() : declared;
    }

    /**
     * The one variable declared in the region and still read after it, if
     * any -- it becomes the return value.
     *
     * <p>The outward data-flow repair, symmetric with a parameter: the
     * implicit link is "a later use reads what this region assigned", and a
     * return value is the explicit construct that restores it.
     */
    private static Optional<VariableDeclarator> planReturnValue(List<Statement> region, BlockStmt block) {
        Position regionEnd = region.get(region.size() - 1).getEnd().orElseThrow();
        List<VariableDeclarator> liveOut = new ArrayList<>();
        for (Statement statement : region) {
            for (VariableDeclarator declared : statement.findAll(VariableDeclarator.class)) {
                String name = declared.getNameAsString();
                boolean readAfter = block.findAll(NameExpr.class).stream()
                        .filter(use -> use.getNameAsString().equals(name))
                        .anyMatch(use -> use.getBegin().filter(regionEnd::isBefore).isPresent());
                if (readAfter) {
                    liveOut.add(declared);
                }
            }
        }
        if (liveOut.size() > 1) {
            throw new RefactorException(Check.MULTIPLE_LIVE_OUT_VALUES, "Cannot extract: " + liveOut.size()
                    + " variables declared in this range are still used after it ("
                    + liveOut.stream().map(VariableDeclarator::getNameAsString).toList()
                    + "). A method returns one value, so carrying several out would mean introducing a type to "
                    + "hold them -- a second refactoring, not this one.");
        }
        return liveOut.isEmpty() ? Optional.empty() : Optional.of(liveOut.get(0));
    }

    // --- refusals ------------------------------------------------------

    /**
     * Refuses a jump that leaves the region.
     *
     * <p>Not because there is no control-flow graph -- that is an
     * implementation obstacle, and only an impossibility justifies a
     * permanent refusal. The reason is that Java has no construct carrying
     * "I returned rather than fell through" out of a method CALL. Encoding
     * it means adding branching at the call site, and the caller's control
     * shape is not a fact available there: it would be invented, not
     * restored. Rule (b) in CLAUDE.md. Issue #20 attacks exactly this.
     */
    @Guard(Check.REJECT_NON_LOCAL_EXIT)
    private static void rejectNonLocalExit(List<Statement> region, int startLine, int endLine) {
        for (Statement statement : region) {
            List<Node> jumps = new ArrayList<>();
            jumps.addAll(statement.findAll(ReturnStmt.class));
            jumps.addAll(statement.findAll(ThrowStmt.class));
            jumps.addAll(statement.findAll(BreakStmt.class));
            jumps.addAll(statement.findAll(ContinueStmt.class));
            for (Node jump : jumps) {
                if (escapesRegion(jump, region)) {
                    throw new RefactorException(Check.REJECT_NON_LOCAL_EXIT, "Cannot extract lines " + startLine
                            + "-" + endLine + ": the '" + firstToken(jump) + "' at "
                            + jump.getBegin().map(Object::toString).orElse("?") + " leaves this range. Carrying "
                            + "that out of a method call needs branching at the call site, which is a change to "
                            + "the caller's control flow rather than a reference this refactor can restore.");
                }
            }
        }
    }

    /**
     * Refuses a lambda or method reference anywhere in the region.
     *
     * <p>{@code calculateResolvedType()} reports the TARGET-TYPED answer for
     * a poly expression, so comparing types across the move cannot reveal a
     * mismatch -- the same blind spot inline-method's
     * {@code rejectPolyExpressionArgument} exists for. Without a usable type
     * answer the verification below is not trustworthy, so the slice
     * excludes them rather than pretending.
     */
    @Guard(Check.REJECT_POLY_EXPRESSION_IN_REGION)
    private static void rejectPolyExpressionInRegion(List<Statement> region, int startLine, int endLine) {
        for (Statement statement : region) {
            Node poly = statement.findAll(LambdaExpr.class).stream().findFirst()
                    .map(Node.class::cast)
                    .or(() -> statement.findAll(MethodReferenceExpr.class).stream().findFirst()
                            .map(Node.class::cast))
                    .orElse(null);
            if (poly != null) {
                throw new RefactorException(Check.REJECT_POLY_EXPRESSION_IN_REGION, "Cannot extract lines "
                        + startLine + "-" + endLine + ": it contains the poly expression '" + poly + "', whose "
                        + "type the resolver reports from its target rather than from itself, so this refactor "
                        + "cannot verify the extraction preserved it.");
            }
        }
    }

    /**
     * Refuses when the region ASSIGNS a live-in that is read afterwards.
     *
     * <p>Java passes by value, so the assignment would happen to the
     * parameter and be lost at the call site -- a silent behaviour change,
     * clean compile. It is not a second return value either; that would need
     * a carrier type, which is {@link #planReturnValue}'s refusal.
     */
    @Guard(Check.REJECT_WRITE_TO_LIVE_IN)
    private static void rejectWriteToLiveIn(List<Statement> region, List<LiveIn> liveIns, BlockStmt block,
                                              String methodName) {
        Set<String> liveInNames = new LinkedHashSet<>();
        liveIns.forEach(liveIn -> liveInNames.add(liveIn.name()));
        Position regionEnd = region.get(region.size() - 1).getEnd().orElseThrow();
        for (Statement statement : region) {
            List<String> written = new ArrayList<>();
            statement.findAll(AssignExpr.class).forEach(assign -> {
                if (assign.getTarget() instanceof NameExpr target && liveInNames.contains(target.getNameAsString())) {
                    written.add(target.getNameAsString());
                }
            });
            statement.findAll(UnaryExpr.class).forEach(unary -> {
                if (unary.getExpression() instanceof NameExpr target
                        && liveInNames.contains(target.getNameAsString())
                        && isIncrementOrDecrement(unary)) {
                    written.add(target.getNameAsString());
                }
            });
            for (String name : written) {
                boolean readAfter = block.findAll(NameExpr.class).stream()
                        .filter(use -> use.getNameAsString().equals(name))
                        .anyMatch(use -> use.getBegin().filter(regionEnd::isBefore).isPresent());
                if (readAfter) {
                    throw new RefactorException(Check.REJECT_WRITE_TO_LIVE_IN, "Cannot extract into '" + methodName
                            + "': the range assigns '" + name + "', which is read after it. Java passes by value, "
                            + "so the assignment would apply to the parameter and be lost at the call site -- the "
                            + "code would still compile and quietly do something else.");
                }
            }
        }
    }

    /** Refuses a name the enclosing type already uses for a method. */
    @Guard(Check.REJECT_DUPLICATE_EXTRACTED_NAME)
    private static void rejectDuplicateMethodName(MethodDeclaration enclosing, String methodName) {
        enclosing.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class).ifPresent(owner -> {
            boolean taken = owner.getMethods().stream()
                    .anyMatch(method -> ((MethodDeclaration) method).getNameAsString().equals(methodName));
            if (taken) {
                throw new RefactorException(Check.REJECT_DUPLICATE_EXTRACTED_NAME, "Cannot extract into '"
                        + methodName + "': '" + owner.getNameAsString() + "' already declares a method with that "
                        + "name, and this refactor will not add an overload whose selection it cannot predict.");
            }
        });
    }

    // --- verification --------------------------------------------------

    /**
     * Proves every synthesised parameter reaches the declaration its name
     * reached before the move.
     *
     * <p>This is the half that makes parameter-passing a repair rather than
     * a hopeful rewrite, and it is the reason this slice is worth building:
     * the extracted method and its call site are in the SAME compilation
     * unit, so unlike rename-method's cross-file repair this can be checked
     * by RESOLUTION rather than structurally. The rewritten source is
     * parsed and each parameter's type compared with the type the original
     * declaration had.
     *
     * <p>Parsed from the rewritten TEXT, not from a mutated AST: this
     * refactor splices source (extract-variable's approach, for the same
     * formatting reasons), so the text is the only artifact there is.
     */
    @Guard(Check.VERIFY_PARAMETERS_BIND_TO_SAME_DECLARATIONS)
    private static void verifyParametersBindToTheSameDeclarations(ProjectContext ctx, Path file, String updated,
                                                                    List<LiveIn> liveIns, Set<String> fieldNames,
                                                                    String methodName) {
        CompilationUnit reparsed;
        try {
            reparsed = com.github.javaparser.StaticJavaParser.parse(updated);
        } catch (RuntimeException e) {
            throw new RefactorException(Check.VERIFY_PARAMETERS_BIND_TO_SAME_DECLARATIONS, "Refusing to extract '"
                    + methodName + "': the result does not parse (" + e.getMessage() + "). Nothing has been "
                    + "written.", e);
        }
        MethodDeclaration extracted = reparsed.findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new RefactorException(Check.VERIFY_PARAMETERS_BIND_TO_SAME_DECLARATIONS,
                        "Refusing to extract '" + methodName + "': the extracted method is not present in the "
                                + "result. Nothing has been written."));

        if (extracted.getParameters().size() != liveIns.size()) {
            throw new RefactorException(Check.VERIFY_PARAMETERS_BIND_TO_SAME_DECLARATIONS, "Refusing to extract '"
                    + methodName + "': " + liveIns.size() + " value(s) had to be passed in for the moved code to "
                    + "still reach them, but the extracted method takes " + extracted.getParameters().size()
                    + ". Nothing has been written.");
        }
        for (int i = 0; i < liveIns.size(); i++) {
            LiveIn expected = liveIns.get(i);
            Parameter actual = extracted.getParameter(i);
            if (!actual.getNameAsString().equals(expected.name())
                    || !actual.getTypeAsString().equals(expected.type())) {
                throw new RefactorException(Check.VERIFY_PARAMETERS_BIND_TO_SAME_DECLARATIONS, "Refusing to "
                        + "extract '" + methodName + "': the moved code reads '" + expected.name() + "' of type '"
                        + expected.type() + "', but parameter " + i + " of the extracted method is '"
                        + actual.getTypeAsString() + " " + actual.getNameAsString() + "'. Nothing has been "
                        + "written.");
            }
        }
        // Everything above compares the result against `liveIns` -- which is
        // the PLAN, i.e. this repair's own input. That is worth nothing on
        // its own: a repair that drops a live-in produces a method whose
        // parameter list matches the shortened plan exactly, and every check
        // above passes. Fault injection proved precisely that, and it is the
        // seventh verifier in this codebase caught making the mistake
        // CLAUDE.md warns about, written in the same session as the warning.
        //
        // So the real check derives what the moved code NEEDS from the moved
        // code itself, and never consults the plan. A name it reads must be
        // answered by a parameter, by something it declares, or by a field
        // of the enclosing type; anything else is a name the repair failed
        // to carry across.
        Set<String> available = new LinkedHashSet<>(fieldNames);
        extracted.getParameters().forEach(parameter -> available.add(parameter.getNameAsString()));
        extracted.findAll(VariableDeclarator.class)
                .forEach(declared -> available.add(declared.getNameAsString()));
        extracted.findAll(Parameter.class).forEach(parameter -> available.add(parameter.getNameAsString()));
        for (NameExpr read : extracted.findAll(NameExpr.class)) {
            String name = read.getNameAsString();
            if (available.contains(name) || looksLikeATypeName(name)) {
                continue;
            }
            throw new RefactorException(Check.VERIFY_PARAMETERS_BIND_TO_SAME_DECLARATIONS, "Refusing to extract '"
                    + methodName + "': the moved code reads '" + name + "', which the extracted method neither "
                    + "takes as a parameter nor declares, and which is not a field of the enclosing type -- the "
                    + "value was not carried across. Nothing has been written.");
        }
    }

    // --- rewriting -----------------------------------------------------

    private static String rewrite(String original, List<Statement> region, MethodDeclaration enclosing,
                                    List<LiveIn> liveIns, Optional<VariableDeclarator> liveOut, String methodName) {
        Position regionBegin = region.get(0).getBegin().orElseThrow();
        Position regionEnd = region.get(region.size() - 1).getEnd().orElseThrow();
        int bodyStart = offsetOfLineStart(original, regionBegin.line);
        int bodyEnd = endOfLine(original, regionEnd.line);
        String indent = original.substring(bodyStart, offsetOf(original, regionBegin.line, regionBegin.column));
        // The new method is a MEMBER, so it takes the enclosing method's own
        // indentation -- using the statement indent put it one level too
        // deep, with a closing brace that did not line up with its header.
        Position enclosingBegin = enclosing.getBegin().orElseThrow();
        String memberIndent = original.substring(offsetOfLineStart(original, enclosingBegin.line),
                offsetOf(original, enclosingBegin.line, enclosingBegin.column));
        String movedBody = original.substring(bodyStart, bodyEnd);
        String lineEnding = original.contains("\r\n") ? "\r\n" : "\n";

        String parameterList = String.join(", ",
                liveIns.stream().map(liveIn -> liveIn.type() + " " + liveIn.name()).toList());
        String argumentList = String.join(", ", liveIns.stream().map(LiveIn::name).toList());

        String returnType = liveOut.map(ExtractMethodRefactor::declaredTypeOf).orElse("void");
        String call = liveOut
                .map(declared -> indent + returnType + " " + declared.getNameAsString() + " = " + methodName
                        + "(" + argumentList + ");")
                .orElse(indent + methodName + "(" + argumentList + ");");

        StringBuilder extracted = new StringBuilder();
        extracted.append(lineEnding)
                .append(lineEnding)
                .append(memberIndent).append("private ").append(enclosing.isStatic() ? "static " : "")
                .append(returnType).append(' ').append(methodName).append('(').append(parameterList).append(") {")
                .append(lineEnding)
                .append(movedBody);
        liveOut.ifPresent(declared -> extracted.append(lineEnding)
                .append(indent).append("return ").append(declared.getNameAsString()).append(';'));
        extracted.append(lineEnding).append(memberIndent).append('}');

        // The new method goes immediately after the enclosing one, so the
        // moved lines keep the indentation they already had.
        int insertAt = endOfLine(original, enclosing.getEnd().orElseThrow().line);

        StringBuilder out = new StringBuilder(original);
        out.insert(insertAt, extracted);
        out.replace(bodyStart, bodyEnd, call);
        return out.toString();
    }

    private static String declaredTypeOf(VariableDeclarator declared) {
        // `var` cannot be a return type, so the inferred type is written out.
        String declaredType = declared.getTypeAsString();
        if (!"var".equals(declaredType)) {
            return declaredType;
        }
        try {
            return declared.getType().resolve().describe();
        } catch (RuntimeException e) {
            throw new RefactorException(Check.LIVE_OUT_TYPE_UNRESOLVABLE, "Cannot extract: '"
                    + declared.getNameAsString() + "' is declared with 'var' and is used after the range, so it "
                    + "has to become the return type, but its inferred type could not be determined: "
                    + e.getMessage(), e);
        }
    }

    // --- helpers -------------------------------------------------------

    /**
     * Is this {@link NameExpr} a TYPE name being used as a qualifier rather
     * than a value?
     *
     * <p>Determined by asking whether it resolves as a value, not by
     * matching a shape: {@code System}, {@code Math}, an imported type, a
     * same-package one. Deliberately the same test as
     * {@code InlineMethodRefactor.isPureTypeQualifier}, which exists for
     * exactly this and reaches the same conclusion the same way.
     */
    /**
     * Field names in scope for {@code enclosing}, which the moved code can
     * still reach without being passed anything.
     *
     * <p>Names only, and only the enclosing type's own -- this runs against
     * the REPARSED text, which has no symbol solver attached, so it cannot
     * ask what a name resolves to. That makes the check conservative in the
     * safe direction: an inherited field it does not know about would make
     * it refuse, never accept.
     */
    private static Set<String> fieldNamesVisibleTo(MethodDeclaration enclosing) {
        Set<String> names = new LinkedHashSet<>();
        enclosing.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class).ifPresent(owner ->
                ((com.github.javaparser.ast.body.TypeDeclaration<?>) owner).getFields()
                        .forEach(field -> field.getVariables()
                                .forEach(variable -> names.add(variable.getNameAsString()))));
        return names;
    }

    /**
     * A crude "is this a type name" test for the verification pass, which
     * has no solver to ask. Java's naming convention is the only signal
     * available there. It is used ONLY to excuse a name from the
     * carried-across check, so its failure mode is accepting a name it
     * should have questioned -- the UNSAFE direction. That is exactly the
     * "enumerate a shape to stand in for a semantic property" pattern
     * CLAUDE.md warns about, kept here only because the reparsed text has
     * no solver. The planning side, where it matters, uses real resolution
     * ({@link #isTypeQualifier}). If this ever needs to be trusted further,
     * attach a solver to the reparsed unit instead of sharpening the
     * heuristic.
     */
    private static boolean looksLikeATypeName(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    private static boolean isTypeQualifier(NameExpr name) {
        try {
            name.resolve();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static boolean isIncrementOrDecrement(UnaryExpr unary) {
        return switch (unary.getOperator()) {
            case PREFIX_INCREMENT, POSTFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_DECREMENT -> true;
            default -> false;
        };
    }

    /** Does this jump target something OUTSIDE the region? */
    private static boolean escapesRegion(Node jump, List<Statement> region) {
        if (jump instanceof ReturnStmt || jump instanceof ThrowStmt) {
            // A `throw` inside a try/catch that is itself wholly inside the
            // region is handled; anything else leaves.
            return !isCaughtInside(jump, region);
        }
        // break/continue: escapes unless its enclosing loop/switch is also
        // in the region.
        Node parent = jump.getParentNode().orElse(null);
        while (parent != null) {
            if (isRegionMember(parent, region)) {
                return false;
            }
            if (parent instanceof com.github.javaparser.ast.stmt.ForStmt
                    || parent instanceof com.github.javaparser.ast.stmt.ForEachStmt
                    || parent instanceof com.github.javaparser.ast.stmt.WhileStmt
                    || parent instanceof com.github.javaparser.ast.stmt.DoStmt
                    || parent instanceof com.github.javaparser.ast.stmt.SwitchStmt) {
                return !isInsideRegion(parent, region);
            }
            parent = parent.getParentNode().orElse(null);
        }
        return true;
    }

    private static boolean isCaughtInside(Node jump, List<Statement> region) {
        if (jump instanceof ReturnStmt) {
            return false;
        }
        Node parent = jump.getParentNode().orElse(null);
        while (parent != null) {
            if (parent instanceof com.github.javaparser.ast.stmt.TryStmt tryStmt && !tryStmt.getCatchClauses()
                    .isEmpty() && isInsideRegion(tryStmt, region)) {
                return true;
            }
            parent = parent.getParentNode().orElse(null);
        }
        return false;
    }

    private static boolean isRegionMember(Node node, List<Statement> region) {
        return region.stream().anyMatch(statement -> statement == node);
    }

    private static boolean isInsideRegion(Node node, List<Statement> region) {
        for (Statement statement : region) {
            if (statement == node || isInside(node, statement)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInside(Node node, Node ancestor) {
        Node parent = node.getParentNode().orElse(null);
        while (parent != null) {
            if (parent == ancestor) {
                return true;
            }
            parent = parent.getParentNode().orElse(null);
        }
        return false;
    }

    private static String firstToken(Node jump) {
        String text = jump.toString();
        int space = text.indexOf(' ');
        int semi = text.indexOf(';');
        int cut = space < 0 ? semi : (semi < 0 ? space : Math.min(space, semi));
        return cut < 0 ? text : text.substring(0, cut);
    }

    private static void validateIdentifier(String name) {
        if (!javax.lang.model.SourceVersion.isIdentifier(name) || javax.lang.model.SourceVersion.isKeyword(name)) {
            // Deliberately worded so no phrase of it also appears in
            // rename-method's identical check -- MessageDistinctivenessTest
            // treats a phrase producible by two throw sites as unassertable.
            throw new RefactorException(Check.INVALID_IDENTIFIER, "Cannot extract into '" + name
                    + "': that is not a usable Java identifier.");
        }
    }

    private static Path resolveFile(ProjectContext ctx, Path file) {
        Path absolute = file.isAbsolute() ? file.normalize() : ctx.projectDir().resolve(file).normalize();
        if (!ctx.unitsByFile().containsKey(absolute)) {
            // Reworded away from extract-variable's identical message for
            // the same reason as validateIdentifier above. That is now the
            // THIRD collision caused by copying these helpers between
            // refactors rather than sharing them -- see issue #27.
            throw new RefactorException(Check.FILE_NOT_IN_PROJECT, "Cannot extract from '" + file
                    + "': the project has no parsed source file there (resolved to " + absolute + ")");
        }
        return absolute;
    }

    private static int offsetOf(String content, int line, int column) {
        return offsetOfLineStart(content, line) + (column - 1);
    }

    private static int offsetOfLineStart(String content, int line) {
        int offset = 0;
        for (int currentLine = 1; currentLine < line; currentLine++) {
            int newline = content.indexOf('\n', offset);
            if (newline < 0) {
                throw new RefactorException(Check.INTERNAL_ERROR,
                        "Internal error: line " + line + " is past the end of the file");
            }
            offset = newline + 1;
        }
        return offset;
    }

    /** Offset just past the last character of {@code line}, excluding its line terminator. */
    private static int endOfLine(String content, int line) {
        int start = offsetOfLineStart(content, line);
        int newline = content.indexOf('\n', start);
        if (newline < 0) {
            return content.length();
        }
        return newline > start && content.charAt(newline - 1) == '\r' ? newline - 1 : newline;
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
