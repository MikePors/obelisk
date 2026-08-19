package dev.obelisk.core.refactor;

import dev.obelisk.core.testsupport.TestProject;
import dev.obelisk.guard.Check;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards that were written as inline {@code throw}s and so were invisible to
 * {@code tools/mutation-check.sh}, which only mutates calls to named
 * {@code reject*}/{@code verify*} methods.
 *
 * <p>Extracting them into named methods immediately showed all of them
 * surviving mutation -- they had never been tested. That is the point of the
 * exercise: a refusal written inline is not just harder to measure, it is
 * systematically less likely to be covered, because nothing reports its
 * absence.
 */
class ExposedGuardsTest {

    @TempDir
    Path dir;

    private TestProject project() {
        return new TestProject(dir);
    }

    private TestProject.RefactorAction extract(TestProject p, String snippet, String name) {
        int[] r = p.rangeOf("com/example/Main.java", snippet);
        Path path = dir.resolve("src/main/java/com/example/Main.java");
        return ctx -> ExtractVariableRefactor.run(ctx, path, r[0], r[1], r[2], r[3], name, true);
    }

    @Test
    @DisplayName("rename-class refuses when the destination FILE already exists (prevents data loss)")
    void refusesWhenTargetFileExists() throws Exception {
        TestProject p = project().add("com/example/Formatter.java", """
                package com.example;
                public class Formatter { }
                """);
        // A file named after the new class, but NOT declaring a type of that
        // name -- so the duplicate-type-name check doesn't apply and this
        // guard is the only thing standing between the rename and an
        // unrelated file being clobbered (the move uses REPLACE_EXISTING).
        Path collide = dir.resolve("src/main/java/com/example/TextFormatter.java");
        Files.writeString(collide, "package com.example;\nclass SomethingElse { }\n");

        assertThat(p.expectRefused(Check.REJECT_EXISTING_TARGET_FILE, ctx ->
                RenameClassRefactor.run(ctx, "Formatter", "TextFormatter", true)))
                .hasMessageContaining("Remove or rename it first");

        // And the bystander file is untouched.
        assertThat(Files.readString(collide)).contains("class SomethingElse");
    }

    @Test
    @DisplayName("extract-variable refuses a braceless if/while/for body")
    void refusesBracelessBody() {
        TestProject p = project().add("com/example/Main.java", """
                package com.example;
                public class Main {
                    static int compute(int n) { return n + 3; }
                    public static int go(boolean flag) {
                        if (flag)
                            return compute(3);
                        return 0;
                    }
                }
                """);
        assertThat(p.expectRefused(Check.REJECT_UNANCHORABLE_STATEMENT, extract(p, "compute(3)", "c")))
                .hasMessageContaining("{ } block");
    }

    @Test
    @DisplayName("extract-variable refuses a statement that doesn't start its own line")
    void refusesStatementNotAtLineStart() {
        TestProject p = project().add("com/example/Main.java", """
                package com.example;
                public class Main {
                    static int compute(int n) { return n + 3; }
                    public static int go() {
                        int a = 1; int b = compute(3);
                        return a + b;
                    }
                }
                """);
        assertThat(p.expectRefused(Check.REJECT_STATEMENT_NOT_AT_LINE_START, extract(p, "compute(3)", "c")))
                .hasMessageContaining("first thing on its");
    }

    @Test
    @DisplayName("inline-method refuses a call nested inside another call to the same method")
    void refusesNestedSelfCall() {
        TestProject p = project()
                .add("com/example/Util.java", """
                        package com.example;
                        public final class Util {
                            public static int twice(int v) { return v * 2; }
                        }
                        """)
                .add("com/example/Main.java", """
                        package com.example;
                        public class Main {
                            public static int go(int n) { return Util.twice(Util.twice(n)); }
                        }
                        """);
        assertThat(p.expectRefused(Check.REJECT_NESTED_SELF_CALL, ctx ->
                InlineMethodRefactor.run(ctx, "Util", "twice", null, true)))
                .hasMessageContaining("nested inside another");
    }
}
