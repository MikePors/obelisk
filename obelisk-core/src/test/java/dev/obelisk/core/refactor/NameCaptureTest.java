package dev.obelisk.core.refactor;

import dev.obelisk.core.testsupport.TestProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Name-capture regressions across the rename-shaped refactors.
 *
 * <p>These all come from one review finding: every refactor checked the
 * collision surface of the OLD name, and none checked what the NEW name
 * already binds to at each affected site. Each test below was reproduced
 * against the real CLI before the fix -- most of them compiled cleanly and
 * silently changed the program's output, which is the failure mode that
 * matters most here.
 *
 * <p>See {@link NameBindingChecker}, which is the shared fix.
 */
class NameCaptureTest {

    @TempDir
    Path dir;

    private TestProject project() {
        return new TestProject(dir);
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("rename-field")
    class RenameField {

        @Test
        @DisplayName("renaming onto an INHERITED field name hides it and rebinds bare uses")
        void refusesCaptureOfInheritedField() {
            TestProject p = project()
                    .add("com/example/Base.java", """
                            package com.example;
                            public class Base {
                                protected int count = 1;
                            }
                            """)
                    .add("com/example/Child.java", """
                            package com.example;
                            public class Child extends Base {
                                private int total = 10;
                                public int sum() { return count + this.total; }
                            }
                            """);

            // Before the fix this succeeded and sum() silently went 11 -> 20.
            assertThat(p.expectRefused(ctx ->
                    RenameFieldRefactor.run(ctx, "Child", "total", "count", true)))
                    .hasMessageContaining("already means")
                    .hasMessageContaining("count");
        }

        @Test
        @DisplayName("renaming onto a STATIC-IMPORTED field name shadows the import")
        void refusesCaptureOfStaticImport() {
            TestProject p = project()
                    .add("com/example/Consts.java", """
                            package com.example;
                            public final class Consts {
                                public static final int count = 100;
                            }
                            """)
                    .add("com/example/Calc.java", """
                            package com.example;
                            import static com.example.Consts.count;
                            public class Calc {
                                private int total = 7;
                                public int calc() { return count + this.total; }
                            }
                            """);

            // Before the fix this succeeded and calc() silently went 107 -> 14.
            assertThat(p.expectRefused(ctx ->
                    RenameFieldRefactor.run(ctx, "Calc", "total", "count", true)))
                    .hasMessageContaining("already means");
        }

        @Test
        @DisplayName("an ordinary rename with no existing binding must stay allowed")
        void allowsRenameWithNoCapture() {
            TestProject p = project()
                    .add("com/example/Base.java", """
                            package com.example;
                            public class Base {
                                protected int count = 1;
                            }
                            """)
                    .add("com/example/Child.java", """
                            package com.example;
                            public class Child extends Base {
                                private int total = 10;
                                public int sum() { return count + this.total; }
                            }
                            """);

            p.run(ctx -> RenameFieldRefactor.run(ctx, "Child", "total", "grandTotal", true));

            assertThat(p.source("com/example/Child.java"))
                    .contains("private int grandTotal = 10")
                    .contains("return count + this.grandTotal");
            p.assertCompiles();
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("rename-method")
    class RenameMethod {

        @Test
        @DisplayName("renaming onto an existing overload redirects the call to a different method")
        void refusesRenameOntoApplicableOverload() {
            TestProject p = project()
                    .add("com/example/A.java", """
                            package com.example;
                            public class A {
                                public String foo(Object o) { return "foo(Object)"; }
                                public String bar(String s) { return "bar(String)"; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static String go(A a) { return a.foo("hello"); }
                            }
                            """);

            // Before the fix this succeeded; go() silently returned
            // "bar(String)" instead of "foo(Object)".
            assertThat(p.expectRefused(ctx ->
                    RenameMethodRefactor.run(ctx, "A", "foo", "bar", null, true)))
                    .hasMessageContaining("already visible");
        }

        @Test
        @DisplayName("renaming onto a SUPERCLASS method name creates an accidental override")
        void refusesAccidentalOverrideDownward() {
            TestProject p = project()
                    .add("com/example/Base.java", """
                            package com.example;
                            public class Base {
                                public String greet() { return "Base.greet"; }
                            }
                            """)
                    .add("com/example/Child.java", """
                            package com.example;
                            public class Child extends Base {
                                public String hello() { return "Child.hello"; }
                            }
                            """);

            assertThat(p.expectRefused(ctx ->
                    RenameMethodRefactor.run(ctx, "Child", "hello", "greet", null, true)))
                    .hasMessageContaining("already visible");
        }

        @Test
        @DisplayName("renaming onto a SUBCLASS method name creates an accidental override too")
        void refusesAccidentalOverrideUpward() {
            TestProject p = project()
                    .add("com/example/Base.java", """
                            package com.example;
                            public class Base {
                                public String hello() { return "Base.hello"; }
                            }
                            """)
                    .add("com/example/Child.java", """
                            package com.example;
                            public class Child extends Base {
                                public String greet() { return "Child.greet"; }
                            }
                            """);

            assertThat(p.expectRefused(ctx ->
                    RenameMethodRefactor.run(ctx, "Base", "hello", "greet", null, true)))
                    .hasMessageContaining("subtype");
        }

        @Test
        @DisplayName("an unqualified call shadowed by an INHERITED method of the same new name")
        void refusesShadowingByInheritedMethod() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static String log(String s) { return "Util.log:" + s; }
                            }
                            """)
                    .add("com/example/Base.java", """
                            package com.example;
                            public class Base {
                                public static String report(String s) { return "Base.report:" + s; }
                            }
                            """)
                    .add("com/example/Sub.java", """
                            package com.example;
                            import static com.example.Util.log;
                            public class Sub extends Base {
                                public String go() { return log("x"); }
                            }
                            """);

            assertThat(p.expectRefused(ctx ->
                    RenameMethodRefactor.run(ctx, "Util", "log", "report", null, true)))
                    .hasMessageContaining("already visible");
        }

        @Test
        @DisplayName("an ordinary rename with no existing binding must stay allowed")
        void allowsRenameWithNoCapture() {
            TestProject p = project()
                    .add("com/example/A.java", """
                            package com.example;
                            public class A {
                                public String foo(Object o) { return "foo"; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static String go(A a) { return a.foo("hello"); }
                            }
                            """);

            p.run(ctx -> RenameMethodRefactor.run(ctx, "A", "foo", "describe", null, true));

            assertThat(p.source("com/example/Main.java")).contains("a.describe(\"hello\")");
            p.assertCompiles();
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("rename-class")
    class RenameClass {

        @Test
        @DisplayName("renaming onto a name taken by a single-type import rebinds to the wrong class")
        void refusesCaptureByImport() {
            TestProject p = project()
                    .add("p/Gadget.java", """
                            package p;
                            public class Gadget {
                                public static String tag() { return "p.Gadget"; }
                            }
                            """)
                    .add("q/Widget.java", """
                            package q;
                            public class Widget {
                                public static String tag() { return "q.Widget"; }
                            }
                            """)
                    .add("p/Client.java", """
                            package p;
                            import q.Widget;
                            public class Client {
                                public static String go() { return Gadget.tag() + "/" + Widget.tag(); }
                            }
                            """);

            // Before the fix this succeeded and BOTH calls silently went to q.Widget.
            assertThat(p.expectRefused(ctx ->
                    RenameClassRefactor.run(ctx, "Gadget", "Widget", true)))
                    .hasMessageContaining("already means");
        }

        @Test
        @DisplayName("renaming onto an enclosing TYPE PARAMETER breaks the build")
        void refusesCaptureByTypeParameter() {
            TestProject p = project()
                    .add("com/example/Gadget.java", """
                            package com.example;
                            public class Gadget { }
                            """)
                    .add("com/example/Box.java", """
                            package com.example;
                            public class Box<T> {
                                Gadget g = new Gadget();
                            }
                            """);

            assertThat(p.expectRefused(ctx ->
                    RenameClassRefactor.run(ctx, "Gadget", "T", true)))
                    .hasMessageContaining("already means");
        }

        @Test
        @DisplayName("an ordinary rename with no existing binding must stay allowed")
        void allowsRenameWithNoCapture() {
            TestProject p = project()
                    .add("p/Gadget.java", """
                            package p;
                            public class Gadget {
                                public static String tag() { return "p.Gadget"; }
                            }
                            """)
                    .add("p/Client.java", """
                            package p;
                            public class Client {
                                public static String go() { return Gadget.tag(); }
                            }
                            """);

            p.run(ctx -> RenameClassRefactor.run(ctx, "Gadget", "Doohickey", true));

            assertThat(p.source("p/Client.java")).contains("Doohickey.tag()");
            p.assertCompiles();
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("extract-variable")
    class ExtractVariable {

        @Test
        @DisplayName("the introduced name must not shadow a field for the rest of the block")
        void refusesShadowingAField() {
            TestProject p = project()
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                static int limit = 5;
                                static int compute(int n) { return n + 3; }
                                public static String go() {
                                    int a = compute(3);
                                    return "a=" + a + " limit=" + limit;
                                }
                            }
                            """);

            Path file = dir.resolve("src/main/java/com/example/Main.java");
            // `compute(3)` on line 6.
            assertThat(p.expectRefused(ctx ->
                    ExtractVariableRefactor.run(ctx, file, 6, 17, 6, 26, "limit", true)))
                    .hasMessageContaining("already means");
        }

        @Test
        @DisplayName("a fresh name must stay allowed")
        void allowsFreshName() {
            TestProject p = project()
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                static int limit = 5;
                                static int compute(int n) { return n + 3; }
                                public static String go() {
                                    int a = compute(3);
                                    return "a=" + a + " limit=" + limit;
                                }
                            }
                            """);

            Path file = dir.resolve("src/main/java/com/example/Main.java");
            p.run(ctx -> ExtractVariableRefactor.run(ctx, file, 6, 17, 6, 26, "computed", true));

            assertThat(p.source("com/example/Main.java")).contains("var computed = compute(3);");
            p.assertCompiles();
        }
    }
}
