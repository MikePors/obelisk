package dev.obelisk.core.refactor;

import dev.obelisk.core.testsupport.TestProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regressions from the review of the fixes themselves.
 *
 * <p>Most of these are cases where an earlier fix was landed at the SHAPE a
 * reproduction demonstrated rather than at the level the hazard lives -- a
 * pattern that has now recurred often enough in this codebase to be worth
 * pinning explicitly, one test per shape that slipped through.
 */
class ReviewRoundFixesTest {

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

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("compound assignment: the RHS can write the target itself")
    class CompoundAssignmentSelfWrite {

        @Test
        @DisplayName("x += x++ reorders even though the target is a local")
        void refusesIncrementOfTargetInRhs() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        public static int go() {
                            int x = 1;
                            x += x++;
                            return x;
                        }
                    }
                    """);
            // Narrowing the check to non-local targets let this through:
            // hoisting x++ moved it before the implicit read, 2 -> 3.
            assertThat(p.expectRefused(extract(p, "x++", "v")))
                    .hasMessageContaining("compound assignment");
        }

        @Test
        @DisplayName("x += (x = 10) likewise")
        void refusesNestedAssignmentToTargetInRhs() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        public static int go() {
                            int x = 1;
                            x += (x = 10);
                            return x;
                        }
                    }
                    """);
            assertThat(p.expectRefused(extract(p, "(x = 10)", "v")))
                    .hasMessageContaining("compound assignment");
        }

        @Test
        @DisplayName("a local target the RHS cannot touch must stay allowed")
        void allowsPlainLocalAccumulator() {
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
            p.run(extract(p, "compute(3)", "part"));
            assertThat(p.source("com/example/Main.java")).contains("var part = compute(3);");
            p.assertCompiles();
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("local classes are subtypes too")
    class LocalClasses {

        @Test
        @DisplayName("a LOCAL subclass declaring the new name is an accidental override")
        void refusesAccidentalOverrideByLocalSubclass() {
            TestProject p = project()
                    .add("com/example/Base.java", """
                            package com.example;
                            public class Base {
                                public String hello() { return "base-hello"; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static String go() {
                                    class Child extends Base {
                                        public String greet() { return "child-greet"; }
                                    }
                                    Base b = new Child();
                                    return b.hello();
                                }
                            }
                            """);
            // getAllAncestors() returns empty for a local class, so this
            // slipped through even after the scan was broadened to reach
            // anonymous and enum-constant bodies: go() silently went from
            // "base-hello" to "child-greet".
            assertThat(p.expectRefused(ctx ->
                    RenameMethodRefactor.run(ctx, "Base", "hello", "greet", null, true)))
                    .hasMessageContaining("subtype");
        }


        @Test
        @DisplayName("a LOCAL class's inherited <clinit> effect is still detected")
        void refusesInlineOnLocalClassWithInheritedStaticBlock() {
            TestProject p = project()
                    .add("com/example/Base.java", """
                            package com.example;
                            public class Base {
                                static { System.out.println("registered"); }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static boolean go(int[] args) {
                                    class Local extends Base {
                                        static boolean pos(int n) { return n > 0; }
                                    }
                                    int v = args.length;
                                    return Local.pos(v);
                                }
                            }
                            """);
            // Found by auditing every getAllAncestors() call site rather than
            // by review: the static-initialization check walked ancestors with
            // the raw API, so a local class reported none and Base's <clinit>
            // silently stopped running after inlining.
            assertThat(p.expectRefused(ctx ->
                    InlineMethodRefactor.run(ctx, "Local", "pos", null, true)))
                    .hasMessageContaining("com.example.Base");
        }

        @Test
        @DisplayName("renaming a LOCAL class's method onto an inherited name is refused too")
        void refusesLocalClassMethodOntoInheritedName() {
            TestProject p = project()
                    .add("com/example/Base.java", """
                            package com.example;
                            public class Base {
                                public String greet() { return "base-greet"; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static String go() {
                                    class Child extends Base {
                                        public String hello() { return "child-hello"; }
                                    }
                                    Child c = new Child();
                                    return c.greet() + "/" + c.hello();
                                }
                            }
                            """);
            assertThat(p.expectRefused(ctx ->
                    RenameMethodRefactor.run(ctx, "Child", "hello", "greet", null, true)))
                    .isNotNull();
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("return position must not cross a lambda boundary")
    class LambdaReturns {

        @Test
        @DisplayName("a return inside a lambda answers to the lambda, not the enclosing method")
        void refusesExtractionFromLambdaReturn() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    import java.util.function.Supplier;
                    public class Main {
                        public static int go() {
                            Supplier<Byte> s = () -> {
                                return 5;
                            };
                            return s.get();
                        }
                    }
                    """);
            // findAncestor(CallableDeclaration) walks past the lambda and
            // answered with go()'s int return type, so this was accepted and
            // emitted a lambda body incompatible with Supplier<Byte>.
            assertThat(p.expectRefused(extract(p, "5", "t")))
                    .isNotNull();
        }

        @Test
        @DisplayName("an ordinary method return is still checked")
        void stillChecksOrdinaryMethodReturns() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        public static byte go() {
                            return 5;
                        }
                    }
                    """);
            assertThat(p.expectRefused(extract(p, "5", "t")))
                    .hasMessageContaining("isn't assignable");
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("generic calls: only refuse what context actually infers")
    class GenericInference {

        @Test
        @DisplayName("a type parameter pinned by an ARGUMENT is safe to extract")
        void allowsArgumentInferredGenericCall() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    import java.util.Objects;
                    public class Main {
                        public static String go(String raw) {
                            String s = Objects.requireNonNull(raw);
                            return s;
                        }
                    }
                    """);
            // Refusing every generic call whose return type mentions a type
            // parameter blocked this entire (safe, very common) population.
            p.run(extract(p, "Objects.requireNonNull(raw)", "checked"));
            assertThat(p.source("com/example/Main.java"))
                    .contains("var checked = Objects.requireNonNull(raw);");
            p.assertCompiles();
        }

        @Test
        @DisplayName("a type parameter inferable only from the TARGET is still refused")
        void stillRefusesTargetInferredGenericCall() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    import java.util.Collections;
                    import java.util.List;
                    public class Main {
                        public static int go() {
                            List<String> b = Collections.emptyList();
                            return b.size();
                        }
                    }
                    """);
            assertThat(p.expectRefused(extract(p, "Collections.emptyList()", "empty")))
                    .hasMessageContaining("inferred from the context");
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("rename-field shadowing (was wrongly allowlisted as subsumed)")
    class FieldShadowing {

        @Test
        @DisplayName("a LOCAL/PARAMETER at a reference site shadows the renamed field")
        void refusesShadowingByParameterAtReferenceSite() {
            TestProject p = project()
                    .add("com/example/Base.java", """
                            package com.example;
                            public class Base {
                                protected int total = 10;
                            }
                            """)
                    .add("com/example/Child.java", """
                            package com.example;
                            public class Child extends Base {
                                public int use(int count) { return total + count; }
                            }
                            """);
            // The declaration-site check cannot see this: `count` is a
            // parameter, in scope only at the REFERENCE. Disabling
            // rejectShadowingCollision silently rewrote the body to
            // `count + count`, changing the result from 15 to 10.
            assertThat(p.expectRefused(ctx ->
                    RenameFieldRefactor.run(ctx, "Base", "total", "count", true)))
                    .isNotNull();
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("interface members are implicitly public (JLS 9.3 / 9.5)")
    class ImplicitlyPublicInterfaceMembers {

        @Test
        @DisplayName("a constant read through a parameter of interface type is accessible")
        void allowsInterfaceConstantReadThroughParameter() {
            TestProject p = project()
                    .add("com/example/Limits.java", """
                            package com.example;
                            public interface Limits {
                                int MAX = 10;
                            }
                            """)
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int cap(Limits l, int n) { return l.MAX + n; }
                                public static int go(Limits l, int n) { return cap(l, n); }
                            }
                            """);
            // Reports accessSpecifier() == NONE despite being implicitly
            // public, so the accessibility check refused a legal inline.
            p.run(ctx -> InlineMethodRefactor.run(ctx, "Util", "cap", null, true));
            assertThat(p.source("com/example/Util.java")).contains("l.MAX + n");
            p.assertCompiles();
        }

        @Test
        @DisplayName("a field read that isn't a plain field can't be verified, so it's refused")
        void refusesUnverifiableFieldAccess() {
            TestProject p = project()
                    .add("com/example/Level.java", """
                            package com.example;
                            enum Level { HIGH, LOW }
                            """)
                    .add("com/example/Util.java", """
                            package com.example;
                            final class Util {
                                static Level pick(Level l) { return l.HIGH; }
                                static Level go(Level l) { return pick(l); }
                            }
                            """);
            // An enum constant exposes no declaring type, so its
            // accessibility is unverifiable. This used to return silently.
            assertThat(p.expectRefused(ctx ->
                    InlineMethodRefactor.run(ctx, "Util", "pick", null, true)))
                    .isNotNull();
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("anonymous class bodies are scopes too")
    class AnonymousClassScopes {

        @Test
        @DisplayName("an anonymous class's OWN method shadows an unqualified call after the rename")
        void refusesShadowingByAnonymousClassOwnMethod() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static String log(String s) { return "Util.log:" + s; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            import static com.example.Util.log;
                            public class Main {
                                public static String go() {
                                    Object o = new Object() {
                                        String report(String s) { return "anon.report:" + s; }
                                        @Override public String toString() { return log("x"); }
                                    };
                                    return o.toString();
                                }
                            }
                            """);
            // An anonymous class body is not a TypeDeclaration, so
            // findAncestor(TypeDeclaration.class) skipped past it to Main.
            // Output silently went from "Util.log:x" to "anon.report:x".
            assertThat(p.expectRefused(ctx ->
                    RenameMethodRefactor.run(ctx, "Util", "log", "report", null, true)))
                    .hasMessageContaining("anonymous class");
        }

        @Test
        @DisplayName("a name the anonymous class does not declare stays allowed")
        void allowsFreeNameThroughAnonymousClass() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static String log(String s) { return "Util.log:" + s; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            import static com.example.Util.log;
                            public class Main {
                                public static String go() {
                                    Object o = new Object() {
                                        @Override public String toString() { return log("x"); }
                                    };
                                    return o.toString();
                                }
                            }
                            """);
            p.run(ctx -> RenameMethodRefactor.run(ctx, "Util", "log", "trace", null, true));
            assertThat(p.source("com/example/Main.java")).contains("return trace(\"x\")");
            p.assertCompiles();
        }
    }
}
