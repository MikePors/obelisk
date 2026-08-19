package dev.obelisk.core.refactor;

import dev.obelisk.core.testsupport.TestProject;
import dev.obelisk.guard.Check;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for checks that {@code tools/mutation-check.sh} reported as
 * SURVIVED -- disabling them left the whole suite green, meaning nothing
 * exercised them.
 *
 * <p>These all predate the review work; none is a new check. They were
 * simply never tested, which is exactly the gap the mutation script exists
 * to surface: reading the code cannot tell you a check is unexercised, and a
 * suite that is green either way looks identical to one that is protecting
 * something.
 */
class UncoveredChecksTest {

    @TempDir
    Path dir;

    private TestProject project() {
        return new TestProject(dir);
    }

    private static TestProject.RefactorAction inline(String className, String methodName) {
        return ctx -> InlineMethodRefactor.run(ctx, className, methodName, null, true);
    }

    /** Runs extract-variable over a snippet located by text rather than hand-counted columns. */
    private TestProject.RefactorAction extract(TestProject p, String file, String snippet, String name) {
        int[] r = p.rangeOf(file, snippet);
        Path path = dir.resolve("src/main/java/" + file);
        return ctx -> ExtractVariableRefactor.run(ctx, path, r[0], r[1], r[2], r[3], name, true);
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("extract-variable: positions where hoisting changes evaluation")
    class HoistPositions {

        private TestProject withBody(String body) {
            return project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        static boolean flag() { return true; }
                        static int compute(int n) { return n + 3; }
                        static void sink(int v) { }
                        public static void go(int a) {
                    %s
                        }
                    }
                    """.formatted(body));
        }

        @Test
        @DisplayName("the right operand of && may not be evaluated at all")
        void refusesShortCircuitRightOperand() {
            TestProject p = withBody("        boolean b = flag() && compute(a) > 0;");
            assertThat(p.expectRefused(Check.REJECT_UNHOISTABLE_POSITION, extract(p, "com/example/Main.java", "compute(a)", "c")))
                    .hasMessageContaining("short-circuit");
        }

        @Test
        @DisplayName("a ternary branch is only conditionally evaluated")
        void refusesTernaryBranch() {
            TestProject p = withBody("        int b = flag() ? compute(a) : 0;");
            assertThat(p.expectRefused(Check.REJECT_UNHOISTABLE_POSITION, extract(p, "com/example/Main.java", "compute(a)", "c")))
                    .isNotNull();
        }

        @Test
        @DisplayName("a while condition is re-evaluated every iteration")
        void refusesLoopCondition() {
            TestProject p = withBody("        while (compute(a) > 100) { }");
            assertThat(p.expectRefused(Check.REJECT_RECURRING_CONTROL_POSITION, extract(p, "com/example/Main.java", "compute(a)", "c")))
                    .isNotNull();
        }

        @Test
        @DisplayName("an assert's condition only runs when assertions are enabled")
        void refusesAssertCondition() {
            TestProject p = withBody("        assert compute(a) > 0;");
            assertThat(p.expectRefused(Check.REJECT_ASSERT_POSITION, extract(p, "com/example/Main.java", "compute(a)", "c")))
                    .isNotNull();
        }

        @Test
        @DisplayName("hoisting above a for-init would forward-reference its variable")
        void refusesForwardReference() {
            // Deliberately targets the for-INIT, not the condition: the
            // condition is refused earlier by rejectRecurringControlPosition,
            // which would make this test pass without ever reaching the
            // forward-reference check (confirmed by mutation).
            TestProject p = withBody("        for (int i = 0, n = compute(i); i < n; i++) { }");
            assertThat(p.expectRefused(Check.REJECT_FORWARD_REFERENCE, extract(p, "com/example/Main.java", "compute(i)", "c")))
                    .hasMessageContaining("declared elsewhere in the same statement");
        }

        @Test
        @DisplayName("extracting an assignment TARGET would turn the write into a no-op")
        void refusesLvalue() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        static int[] data = new int[3];
                        public static void go() {
                            data[1] = 7;
                        }
                    }
                    """);
            assertThat(p.expectRefused(Check.REJECT_LVALUE_POSITION, extract(p, "com/example/Main.java", "data[1]", "slot")))
                    .hasMessageContaining("left-hand side");
        }

        @Test
        @DisplayName("'var x = null' isn't legal Java")
        void refusesNullLiteral() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        static void sink(String s) { }
                        public static void go() {
                            sink(null);
                        }
                    }
                    """);
            assertThat(p.expectRefused(Check.REJECT_UNSUITABLE_INITIALIZER, extract(p, "com/example/Main.java", "null", "n")))
                    .hasMessageContaining("null");
        }

        @Test
        @DisplayName("a void call produces no value to put in a variable")
        void refusesVoidExpression() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        static void act() { }
                        public static void go() {
                            Main.act();
                        }
                    }
                    """);
            assertThat(p.expectRefused(Check.REJECT_NON_VALUE_EXPRESSION, extract(p, "com/example/Main.java", "Main.act()", "v")))
                    .isNotNull();
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("inline-method: body purity")
    class BodyPurity {

        @Test
        @DisplayName("a call site with an explicit receiver would rebind the body's own references")
        void refusesExplicitReceiverOnPrivateMethod() {
            TestProject p = project().add("com/example/Util.java", """
                    package com.example;
                    public class Util {
                        private int twice(int v) { return v * 2; }
                        public int viaOther(Util other, int n) { return other.twice(n); }
                    }
                    """);
            assertThat(p.expectRefused(Check.REJECT_UNSAFE_RECEIVER, inline("Util", "twice")))
                    .hasMessageContaining("explicit receiver");
        }

        @Test
        @DisplayName("an instance-qualified STATIC call still evaluates its qualifier")
        void refusesInstanceQualifiedStaticCall() {
            TestProject p = project().add("com/example/Util.java", """
                    package com.example;
                    public class Util {
                        public static int twice(int v) { return v * 2; }
                        static Util self() { return new Util(); }
                        public static int go(int n) { return self().twice(n); }
                    }
                    """);
            assertThat(p.expectRefused(Check.REJECT_UNSAFE_RECEIVER, inline("Util", "twice")))
                    .hasMessageContaining("qualified by an expression");
        }

        @Test
        @DisplayName("a QUALIFIED call in the body still carries evaluation-order risk")
        void refusesQualifiedCallInBody() {
            // A qualified call passes rejectFreeReferences (which only bans
            // UNqualified ones), so rejectMutatingOrCallExpressions is the
            // check actually exercised here.
            TestProject p = project().add("com/example/Util.java", """
                    package com.example;
                    public class Util {
                        public static int lengthOf(String s) { return s.length(); }
                        public static int go(String t) { return lengthOf(t); }
                    }
                    """);
            assertThat(p.expectRefused(Check.REJECT_MUTATING_OR_CALL_EXPRESSIONS, inline("Util", "lengthOf")))
                    .hasMessageContaining("calls a method");
        }

        @Test
        @DisplayName("renaming a method that itself overrides a supertype's is refused")
        void refusesNonRootOverrideTarget() {
            TestProject p = project()
                    .add("com/example/Base.java", """
                            package com.example;
                            public class Base {
                                public String greet() { return "Base"; }
                            }
                            """)
                    .add("com/example/Child.java", """
                            package com.example;
                            public class Child extends Base {
                                @Override
                                public String greet() { return "Child"; }
                            }
                            """);
            assertThat(p.expectRefused(Check.REJECT_NON_ROOT_TARGET, ctx ->
                    RenameMethodRefactor.run(ctx, "Child", "greet", "salute", null, true)))
                    .isNotNull();
        }
    }
}
