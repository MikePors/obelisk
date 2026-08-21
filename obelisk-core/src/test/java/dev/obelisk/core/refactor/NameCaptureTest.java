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
        @DisplayName("an inherited binding the rename would shadow escapes to super.count")
        void repairsCaptureOfInheritedField() {
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

            // Before the check existed this succeeded and sum() silently went
            // 11 -> 20. It was then REFUSED for several rounds. It is now
            // REPAIRED: the inherited reference escapes to `super.count`, so
            // it keeps meaning Base.count, and sum() still returns 11.
            p.run(ctx -> RenameFieldRefactor.run(ctx, "Child", "total", "count", true));

            assertThat(p.source("com/example/Child.java"))
                    .contains("return super.count + this.count;");
            p.assertCompiles();
        }

        @Test
        @DisplayName("a static-imported binding the rename would shadow escapes to Consts.count")
        void repairsCaptureOfStaticImport() {
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

            // Before the check existed this succeeded and calc() silently
            // went 107 -> 14. Now repaired rather than refused: a STATIC
            // binding takes the declaring type's name, not `super` -- which
            // does not work for statics at all (CLAUDE.md).
            p.run(ctx -> RenameFieldRefactor.run(ctx, "Calc", "total", "count", true));

            assertThat(p.source("com/example/Calc.java"))
                    .contains("return Consts.count + this.count;");
            p.assertCompiles();
        }


        @Test
        @DisplayName("an enum CONSTANT shares a name space with fields")
        void refusesCollisionWithEnumConstant() {
            TestProject p = project()
                    .add("com/example/Color.java", """
                            package com.example;
                            public enum Color {
                                RED, GREEN;
                                private int shade = 3;
                                public int shade() { return shade; }
                            }
                            """);

            // Before the fix this emitted `private int RED = 3;`, which
            // does not compile.
            assertThat(p.expectRefused(Check.REJECT_DUPLICATE_FIELD_NAME, ctx ->
                    RenameFieldRefactor.run(ctx, "Color", "shade", "RED", true)))
                    // Deliberately asserts on wording UNIQUE to the
                    // enum-constant check. Asserting on "enum constant" alone
                    // passed even with that check disabled, because
                    // rejectNewNameAlreadyBound also refuses this case and its
                    // message happens to contain the same words -- so the test
                    // could not tell the two checks apart.
                    .hasMessageContaining("shares a name space with its fields");
        }

        /**
         * The repair rewrites a reference into an explicit form reaching the
         * SAME declaration. When the shadowed binding is not a field at all
         * there is no such form, so this stays a refusal -- rule (a) in
         * CLAUDE.md, not a gap in the repair.
         */
        @Test
        @DisplayName("a shadowed binding that is not a field has no qualified form")
        void refusesWhenShadowedBindingIsNotAField() {
            TestProject p = project()
                    .add("com/example/Holder.java", """
                            package com.example;
                            public enum Holder {
                                LIMIT;
                            }
                            """)
                    .add("com/example/Box.java", """
                            package com.example;
                            import static com.example.Holder.LIMIT;
                            public class Box {
                                private int total = 4;
                                public int use() { return total + LIMIT.ordinal(); }
                            }
                            """);

            assertThat(p.expectRefused(Check.REJECT_NEW_NAME_ALREADY_BOUND, ctx ->
                    RenameFieldRefactor.run(ctx, "Box", "total", "LIMIT", true)))
                    .hasMessageContaining("there is no qualified form that would keep reaching it");
        }

        /**
         * An instance field reached from an ENCLOSING type, not a
         * superclass. {@code super.x} does not reach it and a type name does
         * not either -- the correct form is {@code Outer.this.x}, which this
         * repair does not synthesise. Rule (b): the fact needed is not one
         * this repair knows how to name.
         */
        @Test
        @DisplayName("an instance binding that is not inherited is refused, not guessed at")
        void refusesShadowedInstanceFieldFromEnclosingType() {
            TestProject p = project()
                    .add("com/example/Outer.java", """
                            package com.example;
                            public class Outer {
                                protected int count = 5;
                                public class Inner {
                                    private int total = 2;
                                    public int sum() { return count + this.total; }
                                }
                            }
                            """);

            assertThat(p.expectRefused(Check.REJECT_SHADOWED_BINDING_NOT_QUALIFIABLE, ctx ->
                    RenameFieldRefactor.run(ctx, "Inner", "total", "count", true)))
                    .hasMessageContaining("which is not inherited from a superclass");
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
            assertThat(p.expectRefused(Check.REJECT_NEW_NAME_ALREADY_VISIBLE, ctx ->
                    RenameMethodRefactor.run(ctx, "A", "foo", "bar", null, true)))
                    .hasMessageContaining("overload resolution picks the most specific");
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

            assertThat(p.expectRefused(Check.REJECT_NEW_NAME_ALREADY_VISIBLE, ctx ->
                    RenameMethodRefactor.run(ctx, "Child", "hello", "greet", null, true)))
                    .hasMessageContaining("overload resolution picks the most specific");
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

            assertThat(p.expectRefused(Check.REJECT_NEW_NAME_DECLARED_BY_SUBTYPE, ctx ->
                    RenameMethodRefactor.run(ctx, "Base", "hello", "greet", null, true)))
                    .hasMessageContaining("subtype");
        }


        @Test
        @DisplayName("an ANONYMOUS subclass declaring the new name is an accidental override too")
        void refusesAccidentalOverrideByAnonymousSubclass() {
            TestProject p = project()
                    .add("com/example/Base.java", """
                            package com.example;
                            public class Base {
                                public String hello() { return "Base.hello"; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static String go() {
                                    Base b = new Base() {
                                        public String greet() { return "anon.greet"; }
                                    };
                                    return b.hello();
                                }
                            }
                            """);

            // An anonymous class body isn't a TypeDeclaration, so a
            // TypeDeclaration-based sweep missed it: go() silently went from
            // "Base.hello" to "anon.greet".
            assertThat(p.expectRefused(Check.REJECT_NEW_NAME_DECLARED_BY_SUBTYPE, ctx ->
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

            // REPAIRED, not refused: the call is qualified so it keeps
            // reaching Util.log. Without that it would silently bind to
            // Base.report, since Java resolves the enclosing class hierarchy
            // before static imports.
            p.run(ctx -> RenameMethodRefactor.run(ctx, "Util", "log", "report", null, true));
            assertThat(p.source("com/example/Sub.java")).contains("com.example.Util.report(\"x\")");
            p.assertCompiles();
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
            assertThat(p.expectRefused(Check.REJECT_NEW_NAME_ALREADY_BOUND_AT_REFERENCE, ctx ->
                    RenameClassRefactor.run(ctx, "Gadget", "Widget", true)))
                    .hasMessageContaining("at a place that references");
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

            assertThat(p.expectRefused(Check.REJECT_NEW_NAME_ALREADY_BOUND_AT_REFERENCE, ctx ->
                    RenameClassRefactor.run(ctx, "Gadget", "T", true)))
                    .hasMessageContaining("at a place that references");
        }


        @Test
        @DisplayName("an IMPORT of the new name in a referencing file collides after the rename")
        void refusesCaptureByImportInAnotherFile() {
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
                    .add("p/Unused.java", """
                            package p;
                            import p.Gadget;
                            import q.Widget;
                            public class Unused {
                                public static String go() { return Widget.tag(); }
                            }
                            """);

            // The import of Gadget is itself rewritten, producing
            // `import p.Widget; import q.Widget;` -- a duplicate single-type
            // import that javac rejects.
            assertThat(p.expectRefused(Check.REJECT_NEW_NAME_ALREADY_BOUND_AT_REFERENCE, ctx ->
                    RenameClassRefactor.run(ctx, "Gadget", "Widget", true)))
                    .hasMessageContaining("at a place that references");
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
            assertThat(p.expectRefused(Check.REJECT_NAME_COLLISION, ctx ->
                    ExtractVariableRefactor.run(ctx, file, 6, 17, 6, 26, "limit", true)))
                    .hasMessageContaining("shadow it for the rest of the enclosing block");
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
