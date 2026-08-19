package dev.obelisk.core.refactor;

import dev.obelisk.core.testsupport.TestProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hoisting and {@code var}-inference regressions in
 * {@link ExtractVariableRefactor}.
 *
 * <p>Extract-variable moves an expression's evaluation EARLIER and gives it
 * a standalone {@code var} type. Each test here is a case where one of those
 * two things silently changed the program, reproduced against the real CLI
 * before the fix.
 */
class ExtractVariableHoistingTest {

    @TempDir
    Path dir;

    private TestProject project() {
        return new TestProject(dir);
    }

    private Path mainFile() {
        return dir.resolve("src/main/java/com/example/Main.java");
    }

    @Test
    @DisplayName("hoisting out of a local class body turns a per-instance initializer into evaluate-once")
    void refusesHoistOutOfLocalClassBody() {
        TestProject p = project().add("com/example/Main.java", """
                package com.example;
                public class Main {
                    static int counter = 0;
                    static int next() { return ++counter; }
                    public static String go() {
                        class Counter { int v = next(); }
                        return new Counter().v + "," + new Counter().v;
                    }
                }
                """);

        // `next()` on line 6. Before the fix this hoisted above the class
        // declaration and output went from "1,2" to "1,1".
        assertThat(p.expectRefused(ctx ->
                ExtractVariableRefactor.run(ctx, mainFile(), 6, 33, 6, 38, "first", true)))
                .hasMessageContaining("local or anonymous");
    }

    @Test
    @DisplayName("hoisting out of a try-with-resources header escapes the try's own catch")
    void refusesHoistOutOfResourceSpecification() {
        TestProject p = project().add("com/example/Main.java", """
                package com.example;
                import java.io.Closeable;
                public class Main {
                    static Closeable open() { throw new RuntimeException("boom"); }
                    public static String go() {
                        try (Closeable c = open()) {
                            return "ok";
                        } catch (Exception e) {
                            return "caught: " + e.getMessage();
                        }
                    }
                }
                """);

        // `open()` on line 6. Before the fix the call was hoisted above the
        // try and the exception stopped being caught.
        assertThat(p.expectRefused(ctx ->
                ExtractVariableRefactor.run(ctx, mainFile(), 6, 28, 6, 33, "res", true)))
                .hasMessageContaining("try-with-resources");
    }

    @Test
    @DisplayName("a diamond's type arguments come from context, so var would infer Object")
    void refusesDiamond() {
        TestProject p = project().add("com/example/Main.java", """
                package com.example;
                import java.util.ArrayList;
                import java.util.List;
                public class Main {
                    public static int go() {
                        List<String> a = new ArrayList<>();
                        return a.size();
                    }
                }
                """);

        // `new ArrayList<>()` on line 6. Before the fix this produced
        // `var zz = new ArrayList<>();` inferring ArrayList<Object>.
        assertThat(p.expectRefused(ctx ->
                ExtractVariableRefactor.run(ctx, mainFile(), 6, 26, 6, 42, "zz", true)))
                .hasMessageContaining("diamond");
    }

    @Test
    @DisplayName("an assignment context applying a narrowing conversion can't survive a var")
    void refusesNarrowingAssignmentContext() {
        TestProject p = project().add("com/example/Main.java", """
                package com.example;
                public class Main {
                    public static byte go() {
                        byte b = 5;
                        return b;
                    }
                }
                """);

        // `5` on line 4. Before the fix this produced `var zz = 5;` (int)
        // and then `byte b = zz;` -- a lossy conversion.
        assertThat(p.expectRefused(ctx ->
                ExtractVariableRefactor.run(ctx, mainFile(), 4, 18, 4, 18, "zz", true)))
                .hasMessageContaining("isn't assignable");
    }

    @Test
    @DisplayName("a compound assignment reads its target before the RHS, so hoisting reorders")
    void refusesCompoundAssignmentReordering() {
        TestProject p = project().add("com/example/Main.java", """
                package com.example;
                public class Main {
                    static int x = 1;
                    static int f() { x = 100; return 5; }
                    public static int go() {
                        x += f();
                        return x;
                    }
                }
                """);

        // `f()` on line 6. Before the fix this produced `var v = f(); x += v;`
        // and x went from 6 to 105.
        assertThat(p.expectRefused(ctx ->
                ExtractVariableRefactor.run(ctx, mainFile(), 6, 14, 6, 16, "v", true)))
                .hasMessageContaining("compound assignment");
    }

    // ------------------------------------------------------------------
    // Cases that must STAY allowed -- the checks above are broad, so these
    // pin down that ordinary extraction still works.

    @Test
    void allowsOrdinaryExtraction() {
        TestProject p = project().add("com/example/Main.java", """
                package com.example;
                public class Main {
                    static int compute(int n) { return n + 3; }
                    public static int go() {
                        int a = compute(3) * 2;
                        return a;
                    }
                }
                """);

        p.run(ctx -> ExtractVariableRefactor.run(ctx, mainFile(), 5, 17, 5, 26, "computed", true));

        assertThat(p.source("com/example/Main.java"))
                .contains("var computed = compute(3);")
                .contains("int a = computed * 2;");
        p.assertCompiles();
    }

    @Test
    @DisplayName("a PLAIN assignment RHS is fine -- only compound assignment reorders")
    void allowsPlainAssignmentRhs() {
        TestProject p = project().add("com/example/Main.java", """
                package com.example;
                public class Main {
                    static int f() { return 5; }
                    public static int go() {
                        int x;
                        x = f() + 1;
                        return x;
                    }
                }
                """);

        p.run(ctx -> ExtractVariableRefactor.run(ctx, mainFile(), 6, 13, 6, 15, "v", true));

        assertThat(p.source("com/example/Main.java")).contains("var v = f();");
        p.assertCompiles();
    }

    @Test
    @DisplayName("an explicitly-typed generic construction is fine -- only the diamond is target-typed")
    void allowsExplicitTypeArguments() {
        TestProject p = project().add("com/example/Main.java", """
                package com.example;
                import java.util.ArrayList;
                import java.util.List;
                public class Main {
                    public static int go() {
                        List<String> a = new ArrayList<String>();
                        return a.size();
                    }
                }
                """);

        p.run(ctx -> ExtractVariableRefactor.run(ctx, mainFile(), 6, 26, 6, 48, "made", true));

        assertThat(p.source("com/example/Main.java")).contains("var made = new ArrayList<String>();");
        p.assertCompiles();
    }

    @Test
    @DisplayName("a generic method's type arguments come from context, same as a diamond")
    void refusesTargetTypedGenericMethod() {
        TestProject p = project().add("com/example/Main.java", """
                package com.example;
                import java.util.List;
                public class Main {
                    public static int go() {
                        List<String> b = java.util.Collections.emptyList();
                        return b.size();
                    }
                }
                """);

        // `Collections.emptyList()` on line 5. Comparing resolved types can't
        // catch this -- the resolver reports the target-typed List<String> on
        // both sides -- so it's refused structurally, like the diamond.
        assertThat(p.expectRefused(ctx ->
                ExtractVariableRefactor.run(ctx, mainFile(), 5, 26, 5, 58, "empty", true)))
                .hasMessageContaining("inferred from the context");
    }

    @Test
    @DisplayName("a return position applies an assignment conversion too")
    void refusesNarrowingInReturnPosition() {
        TestProject p = project().add("com/example/Main.java", """
                package com.example;
                public class Main {
                    public static byte go() {
                        return 5;
                    }
                }
                """);

        assertThat(p.expectRefused(ctx ->
                ExtractVariableRefactor.run(ctx, mainFile(), 4, 16, 4, 16, "zz", true)))
                .hasMessageContaining("isn't assignable");
    }

    @Test
    @DisplayName("an explicit type witness leaves nothing to infer, so it stays allowed")
    void allowsExplicitTypeWitness() {
        TestProject p = project().add("com/example/Main.java", """
                package com.example;
                import java.util.List;
                public class Main {
                    public static int go() {
                        List<String> c = java.util.Collections.<String>emptyList();
                        return c.size();
                    }
                }
                """);

        p.run(ctx -> ExtractVariableRefactor.run(ctx, mainFile(), 5, 26, 5, 66, "empty", true));

        assertThat(p.source("com/example/Main.java")).contains("var empty = java.util.Collections.<String>emptyList();");
        p.assertCompiles();
    }

    @Test
    @DisplayName("compound assignment to a LOCAL is safe -- a callee can't touch a caller's local")
    void allowsCompoundAssignmentToLocal() {
        TestProject p = project().add("com/example/Main.java", """
                package com.example;
                public class Main {
                    static int compute(int n) { return n + 3; }
                    public static int go() {
                        int sum = 0;
                        sum += compute(3);
                        return sum;
                    }
                }
                """);

        p.run(ctx -> ExtractVariableRefactor.run(ctx, mainFile(), 6, 16, 6, 25, "part", true));

        assertThat(p.source("com/example/Main.java"))
                .contains("var part = compute(3);")
                .contains("sum += part;");
        p.assertCompiles();
    }
}
