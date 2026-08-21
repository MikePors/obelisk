package dev.obelisk.core.refactor;

import dev.obelisk.core.ProjectContext;
import dev.obelisk.core.RefactorException;
import dev.obelisk.core.testsupport.TestProject;
import dev.obelisk.guard.Check;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Coverage for the checks that report "what you named could not be
 * identified" or "what you asked for is malformed".
 *
 * <p>These were the largest block of {@link Check} constants with no test
 * asserting them. An earlier version of {@code RefactorException} exempted
 * exactly this category from carrying an identity at all, on the reasoning
 * that a lookup failure is not "a safety check refusing" -- that boundary
 * was removed as one more hand-drawn line of the kind this codebase keeps
 * getting wrong. Leaving the same category untested was the same line drawn
 * again, one layer up: the identities existed, and nothing pinned them.
 *
 * <p>They are cheap to test and they are what a user hits FIRST, before any
 * hazard analysis runs. A wrong identity here is not harmless either -- a
 * bulk script once labelled three unrelated throw sites
 * {@code INVALID_IDENTIFIER}, which compiled and passed the whole suite
 * (see {@code CheckIdIntegrityTest}).
 *
 * <p>Not covered here, and deliberately: the environment and resolver
 * failures ({@code CLASSPATH_*}, {@code *_UNRESOLVABLE},
 * {@code WRITE_FAILED}, {@code INTERNAL_ERROR}). Those need fault injection
 * into an external process or a deliberately broken filesystem, not a
 * fixture. {@code CheckAssertionCoverageTest} lists them explicitly with
 * that reason, so the exemption is recorded rather than assumed.
 */
class LookupAndValidationChecksTest {

    @TempDir
    Path dir;

    private TestProject project() {
        return new TestProject(dir);
    }

    private TestProject widget() {
        return project().add("com/example/Widget.java", """
                package com.example;
                public class Widget {
                    private int count = 1;
                    public int get() { return count; }
                    public int size(int n) { return n; }
                }
                """);
    }

    // --- Argument validation: what you asked for is malformed -------------

    @Test
    @DisplayName("a new name that is a Java keyword is refused as an invalid identifier")
    void refusesKeywordAsNewName() {
        assertThat(widget().expectRefused(Check.INVALID_IDENTIFIER,
                ctx -> RenameMethodRefactor.run(ctx, "Widget", "get", "class", null, true)))
                .hasMessageContaining("is not a valid Java method name");
    }

    @Test
    @DisplayName("a --params value with an empty segment is refused rather than misread")
    void refusesParamsFilterWithEmptySegment() {
        assertThat(widget().expectRefused(Check.INVALID_PARAMS_FILTER,
                ctx -> RenameMethodRefactor.run(ctx, "Widget", "size", "length", "int,", true)))
                .hasMessageContaining("not a leading/trailing/doubled comma");
    }

    @Test
    @DisplayName("a --file outside the project's parsed sources is refused")
    void refusesFileOutsideProject() {
        TestProject p = widget();
        Path outside = dir.resolve("elsewhere/Other.java");
        assertThat(p.expectRefused(Check.FILE_NOT_IN_PROJECT,
                ctx -> ExtractVariableRefactor.run(ctx, outside, 1, 1, 1, 2, "x", true)))
                .hasMessageContaining("is not one of the project's parsed source files");
    }

    // --- Target lookup: the thing you named could not be identified -------

    @Test
    @DisplayName("renaming a field that does not exist names the field, not the class")
    void refusesMissingField() {
        assertThat(widget().expectRefused(Check.FIELD_NOT_FOUND,
                ctx -> RenameFieldRefactor.run(ctx, "Widget", "nope", "other", true)))
                .hasMessageContaining("No field named");
    }

    /**
     * An enum CONSTANT is not a {@code ResolvedFieldDeclaration} -- it is a
     * {@code ResolvedEnumConstantDeclaration}, one of the JavaParser
     * divergences catalogued in CLAUDE.md. But it never reaches the code
     * that would notice: {@code findField} walks {@code FieldDeclaration}
     * members only, so an enum constant is simply not found.
     *
     * <p>This test was first written asserting {@code
     * TARGET_FIELD_NOT_A_FIELD} and failed, naming the check that actually
     * refused -- which is precisely what asserting on the identity is for.
     * That makes {@code TARGET_FIELD_NOT_A_FIELD} unreachable by
     * construction: a backstop, guarding the day {@code findField} is
     * widened to return something that is not a field (see the
     * record-component gap, issue #9). It is exempted in {@code
     * CheckAssertionCoverageTest} on that basis rather than left looking
     * like an untested check.
     */
    @Test
    @DisplayName("pointing rename-field at an enum constant reports no such field")
    void refusesEnumConstantAsField() {
        TestProject p = project().add("com/example/Color.java", """
                package com.example;
                public enum Color {
                    RED, GREEN;
                }
                """);
        assertThat(p.expectRefused(Check.FIELD_NOT_FOUND,
                ctx -> RenameFieldRefactor.run(ctx, "Color", "RED", "CRIMSON", true)))
                .hasMessageContaining("No field named");
    }

    @Test
    @DisplayName("an expression outside any statement is refused by extract-variable")
    void refusesExpressionNotInAStatement() {
        TestProject p = project().add("com/example/Main.java", """
                package com.example;
                public class Main {
                    private int total = 2 + 3;
                }
                """);
        int[] r = p.rangeOf("com/example/Main.java", "2 + 3");
        Path file = dir.resolve("src/main/java/com/example/Main.java");
        assertThat(p.expectRefused(Check.EXPRESSION_NOT_IN_A_STATEMENT,
                ctx -> ExtractVariableRefactor.run(ctx, file, r[0], r[1], r[2], r[3], "sum", true)))
                .hasMessageContaining("isn't inside a method/constructor/initializer body");
    }

    // --- Overload disambiguation -----------------------------------------

    private TestProject overloaded() {
        return project().add("com/example/Svc.java", """
                package com.example;
                public class Svc {
                    public void handle(int n) { }
                    public void handle(String s) { }
                }
                """);
    }

    @Test
    @DisplayName("a --params matching no overload lists the ones that exist")
    void refusesParamsMatchingNoOverload() {
        assertThat(overloaded().expectRefused(Check.NO_OVERLOAD_MATCHES_PARAMS,
                ctx -> RenameMethodRefactor.run(ctx, "Svc", "handle", "process", "double", true)))
                .hasMessageContaining("Available overloads");
    }

    /**
     * Two overloads whose parameter types share a SIMPLE name: {@code
     * --params List} matches both, and obelisk refuses rather than picking
     * one.
     */
    @Test
    @DisplayName("a --params matching several overloads refuses instead of picking one")
    void refusesAmbiguousParamsFilter() {
        TestProject p = project()
                .add("com/example/List.java", """
                        package com.example;
                        public class List { }
                        """)
                .add("com/example/Svc.java", """
                        package com.example;
                        public class Svc {
                            public void handle(com.example.List a) { }
                            public void handle(java.util.List<String> b) { }
                        }
                        """);
        assertThat(p.expectRefused(Check.PARAMS_FILTER_AMBIGUOUS,
                ctx -> RenameMethodRefactor.run(ctx, "Svc", "handle", "process", "List", true)))
                .hasMessageContaining("type names to disambiguate.");
    }

    // --- Loading the project at all --------------------------------------

    @Test
    @DisplayName("a directory with no source roots is refused by name")
    void refusesProjectWithNoSourceRoots() {
        Path empty = dir.resolve("empty");
        try {
            Files.createDirectories(empty);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThatThrownBy(() -> ProjectContext.load(empty))
                .isInstanceOf(RefactorException.class)
                .hasMessageContaining("No source roots found under")
                .extracting(e -> ((RefactorException) e).check())
                .isEqualTo(Check.NO_SOURCE_ROOTS);
    }

    /**
     * Every source file under the project's roots is parsed up front, so one
     * unparseable file stops the whole run. That is deliberate -- a refactor
     * that silently skipped a file it could not read would under-report its
     * own call sites, which is the failure mode this tool exists to avoid.
     */
    @Test
    @DisplayName("an unparseable source file stops the run and names the file")
    void refusesUnparseableSource() {
        TestProject p = widget().add("com/example/Broken.java", """
                package com.example;
                public class Broken {
                    public void oops( {
                }
                """);
        assertThatThrownBy(() -> p.run(ctx -> RenameFieldRefactor.run(ctx, "Widget", "count", "total", true)))
                .isInstanceOf(RefactorException.class)
                .hasMessageContaining("Failed to parse")
                .extracting(e -> ((RefactorException) e).check())
                .isEqualTo(Check.SOURCE_FILE_UNPARSEABLE);
    }
}
