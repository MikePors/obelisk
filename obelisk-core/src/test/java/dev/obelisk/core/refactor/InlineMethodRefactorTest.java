package dev.obelisk.core.refactor;

import dev.obelisk.core.RefactorException;
import dev.obelisk.core.testsupport.TestProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression suite for {@link InlineMethodRefactor}.
 *
 * <p>Nearly every test here is a REFUSAL case, and each one encodes a
 * specific hazard that was found -- by independent review, then confirmed by
 * a hand-built reproduction -- to make this refactor emit code that either
 * fails to compile or, worse, compiles cleanly and silently behaves
 * differently. They are grouped by hazard category rather than by review
 * round, since the categories are what future changes are likely to break.
 *
 * <p>The tests that ALLOW an inline matter just as much: eight rounds of
 * tightening restrictions carry a standing risk of over-refusal, and several
 * of these pin down cases that are genuinely safe and must stay allowed
 * (notably {@link StaticInitialization#interfaceWithoutDefaultMethodIsFine}
 * and the whole of {@link Repair}).
 */
class InlineMethodRefactorTest {

    @TempDir
    Path dir;

    private TestProject project() {
        return new TestProject(dir);
    }

    private static TestProject.RefactorAction inline(String className, String methodName) {
        return ctx -> InlineMethodRefactor.run(ctx, className, methodName, null, true);
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("happy paths (must stay allowed)")
    class HappyPath {

        @Test
        void inlinesPureArithmeticAndRemovesTheDeclaration() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int square(int a) { return a * a; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static void main(String[] args) {
                                    int a = args.length;
                                    System.out.println(Util.square(a));
                                }
                            }
                            """);

            RefactorResult result = p.run(inline("Util", "square"));

            assertThat(result.changedFiles()).hasSize(2);
            assertThat(p.source("com/example/Main.java")).contains("System.out.println((a * a))");
            assertThat(p.source("com/example/Util.java")).doesNotContain("square");
            p.assertCompiles();
        }

        @Test
        void inlinesAtEveryCallSiteAcrossFiles() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int twice(int a) { return a * 2; }
                            }
                            """)
                    .add("com/example/One.java", """
                            package com.example;
                            public class One {
                                public static int go(int n) { return Util.twice(n); }
                            }
                            """)
                    .add("com/example/Two.java", """
                            package com.example;
                            public class Two {
                                public static int go(int n) { return Util.twice(n) + Util.twice(n + 1); }
                            }
                            """);

            p.run(inline("Util", "twice"));

            assertThat(p.source("com/example/One.java")).contains("(n * 2)");
            assertThat(p.source("com/example/Two.java")).contains("(n * 2)").contains("((n + 1) * 2)");
            p.assertCompiles();
        }

        @Test
        @DisplayName("substituted argument is parenthesised so precedence is preserved")
        void wrapsSubstitutedArgumentsToPreservePrecedence() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int half(int x) { return x / 2; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static int go(int b) { return Util.half(b + 2); }
                            }
                            """);

            p.run(inline("Util", "half"));

            // Not `b + 2 / 2`, which parses as `b + 1`.
            assertThat(p.source("com/example/Main.java")).contains("((b + 2) / 2)");
            p.assertCompiles();
        }

        @Test
        @DisplayName("a body that IS just a parameter substitutes the argument, not the parameter's name")
        void identityMethodSubstitutesTheArgument() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int id(int x) { return x; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static int go(int y) { return Util.id(y); }
                            }
                            """);

            p.run(inline("Util", "id"));

            assertThat(p.source("com/example/Main.java")).contains("return y;").doesNotContain("return x;");
            p.assertCompiles();
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("class initialization (JLS 12.4)")
    class StaticInitialization {

        @Test
        @DisplayName("declaring class has a static initializer block")
        void ownStaticBlock() {
            TestProject p = project()
                    .add("com/example/Flags.java", """
                            package com.example;
                            public final class Flags {
                                static { System.out.println("registered"); }
                                public static boolean positive(int n) { return n > 0; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static boolean go(int n) { return Flags.positive(n); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Flags", "positive")))
                    .hasMessageContaining("'static' initializer block");
        }

        @Test
        @DisplayName("static initializer inherited from a SUPERCLASS is still reachable")
        void inheritedStaticBlock() {
            TestProject p = project()
                    .add("com/example/Base.java", """
                            package com.example;
                            public class Base {
                                static { System.out.println("registered"); }
                            }
                            """)
                    .add("com/example/Util.java", """
                            package com.example;
                            public class Util extends Base {
                                public static boolean positive(int n) { return n > 0; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static boolean go(int n) { return Util.positive(n); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "positive")))
                    .hasMessageContaining("com.example.Base")
                    .hasMessageContaining("inherits from");
        }

        @Test
        @DisplayName("superinterface WITH a default method is initialized too (JLS 12.4.2 step 7)")
        void interfaceWithDefaultMethod() {
            TestProject p = project()
                    .add("com/example/Registry.java", """
                            package com.example;
                            public class Registry {
                                public static Object register() { return new Object(); }
                            }
                            """)
                    .add("com/example/Marker.java", """
                            package com.example;
                            public interface Marker {
                                Object REGISTERED = Registry.register();
                                default int marker() { return 1; }
                            }
                            """)
                    .add("com/example/Util.java", """
                            package com.example;
                            public class Util implements Marker {
                                public static boolean positive(int n) { return n > 0; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static boolean go(int n) { return Util.positive(n); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "positive")))
                    .hasMessageContaining("com.example.Marker");
        }

        @Test
        @DisplayName("superinterface WITHOUT a default method is never initialized, so it must stay allowed")
        void interfaceWithoutDefaultMethodIsFine() {
            TestProject p = project()
                    .add("com/example/Plain.java", """
                            package com.example;
                            public interface Plain {
                                int arity();
                            }
                            """)
                    .add("com/example/Util.java", """
                            package com.example;
                            public class Util implements Plain {
                                public int arity() { return 1; }
                                public static int square(int a) { return a * a; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static int go(int n) { return Util.square(n); }
                            }
                            """);

            p.run(inline("Util", "square"));

            assertThat(p.source("com/example/Main.java")).contains("(n * n)");
            p.assertCompiles();
        }

        @Test
        @DisplayName("a non-constant static field is itself a <clinit> effect")
        void nonConstantStaticField() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                static final Object HANDLE = new Object();
                                public static boolean positive(int n) { return n > 0; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static boolean go(int n) { return Util.positive(n); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "positive")))
                    .hasMessageContaining("HANDLE");
        }

        @Test
        @DisplayName("an enum-constant initializer is undeterminable, so must count as NOT constant")
        void enumConstantStaticFieldIsNotTreatedAsConstant() {
            TestProject p = project()
                    .add("com/example/Level.java", """
                            package com.example;
                            public enum Level {
                                HIGH, LOW;
                                static { System.out.println("registered"); }
                            }
                            """)
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                static final Level DEFAULT = Level.HIGH;
                                public static boolean positive(int n) { return n > 0; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static boolean go(int n) { return Util.positive(n); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "positive")))
                    .hasMessageContaining("DEFAULT");
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("repair: static-field references are re-qualified, not refused")
    class Repair {

        @Test
        @DisplayName("an unqualified constant is re-qualified with its declaring type")
        void requalifiesStaticConstant() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static final int SCALE = 2;
                                public static int scaled(int x) { return SCALE * x; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static int go(int n) { return Util.scaled(n); }
                            }
                            """);

            p.run(inline("Util", "scaled"));

            assertThat(p.source("com/example/Main.java")).contains("com.example.Util.SCALE * n");
            p.assertCompiles();
        }

        @Test
        @DisplayName("a call site with its OWN same-named field still gets the original binding")
        void repairDefeatsShadowingAtTheCallSite() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static final int SCALE = 2;
                                public static int scaled(int x) { return SCALE * x; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                static final int SCALE = 1000;
                                public static int go(int n) { return Util.scaled(n); }
                            }
                            """);

            p.run(inline("Util", "scaled"));

            // Naive substitution would produce `SCALE * n`, silently binding to
            // Main's own SCALE = 1000 and changing the result.
            assertThat(p.source("com/example/Main.java")).contains("com.example.Util.SCALE * n");
            p.assertCompiles();
        }

        @Test
        @DisplayName("an inherited constant is qualified with the ANCESTOR that declares it")
        void requalifiesWithDeclaringAncestor() {
            TestProject p = project()
                    .add("com/example/Base.java", """
                            package com.example;
                            public class Base {
                                public static final int SCALE = 2;
                            }
                            """)
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util extends Base {
                                public static int scaled(int x) { return SCALE * x; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                static final int SCALE = 1000;
                                public static int go(int n) { return Util.scaled(n); }
                            }
                            """);

            p.run(inline("Util", "scaled"));

            assertThat(p.source("com/example/Main.java")).contains("com.example.Base.SCALE * n");
            p.assertCompiles();
        }

        @Test
        @DisplayName("interface constants are implicitly public static final (JLS 9.3)")
        void repairsInterfaceConstant() {
            TestProject p = project()
                    .add("com/example/Limits.java", """
                            package com.example;
                            public interface Limits {
                                int SCALE = 2;
                            }
                            """)
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util implements Limits {
                                public static int scaled(int x) { return SCALE * x; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static int go(int n) { return Util.scaled(n); }
                            }
                            """);

            p.run(inline("Util", "scaled"));

            assertThat(p.source("com/example/Main.java")).contains("com.example.Limits.SCALE * n");
            p.assertCompiles();
        }

        @Test
        @DisplayName("a call site in another package needs no import")
        void repairWorksAcrossPackages() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static final int SCALE = 2;
                                public static int scaled(int x) { return SCALE * x; }
                            }
                            """)
                    .add("com/other/Main.java", """
                            package com.other;
                            import com.example.Util;
                            public class Main {
                                static final int SCALE = 1000;
                                public static int go(int n) { return Util.scaled(n); }
                            }
                            """);

            p.run(inline("Util", "scaled"));

            assertThat(p.source("com/other/Main.java")).contains("com.example.Util.SCALE * n");
            p.assertCompiles();
        }

        @Test
        @DisplayName("a private constant is unrepairable -- other call sites could not read it")
        void refusesPrivateConstant() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                private static final int SCALE = 2;
                                public static int scaled(int x) { return SCALE * x; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static int go(int n) { return Util.scaled(n); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "scaled")))
                    .hasMessageContaining("SCALE");
        }

        @Test
        @DisplayName("an INSTANCE field is unrepairable -- there is no receiver to qualify with")
        void refusesInstanceField() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public int scale = 2;
                                private int scaledImpl(int x) { return scale * x; }
                                public int use(int x) { return scaledImpl(x); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "scaledImpl")))
                    .hasMessageContaining("scale");
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("argument evaluation semantics")
    class ArgumentEvaluation {

        @Test
        @DisplayName("a parameter used twice would duplicate a non-trivial argument's evaluation")
        void refusesDuplicatedEvaluationOfNonTrivialArgument() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int square(int x) { return x * x; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static int go(int b) { return Util.square(b + 1); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "square")))
                    .hasMessageContaining("referenced more than once");
        }

        @Test
        @DisplayName("a plain local read IS safe to duplicate, so that must stay allowed")
        void allowsDuplicationOfPlainLocalRead() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int square(int x) { return x * x; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static int go(int b) { return Util.square(b); }
                            }
                            """);

            p.run(inline("Util", "square"));

            assertThat(p.source("com/example/Main.java")).contains("(b * b)");
            p.assertCompiles();
        }

        @Test
        @DisplayName("an unused parameter would DROP its argument's side effect entirely")
        void refusesDroppedSideEffectingArgument() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int pick(int unused, int b) { return b * 2; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                static int noisy() { return 1; }
                                public static int go(int n) { return Util.pick(noisy(), n); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "pick")))
                    .hasMessageContaining("unused");
        }

        @Test
        @DisplayName("a static-field argument is NOT side-effect-free -- reading it can run <clinit>")
        void refusesDroppedStaticFieldArgument() {
            TestProject p = project()
                    .add("com/example/Flags.java", """
                            package com.example;
                            public final class Flags {
                                public static final Object TOKEN = new Object();
                            }
                            """)
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int pick(Object unused, int b) { return b * 2; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static int go(int n) { return Util.pick(Flags.TOKEN, n); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "pick")))
                    .hasMessageContaining("unused");
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("compile-time constant promotion (JLS 15.29)")
    class ConstantPromotion {

        @Test
        @DisplayName("a body referencing no parameter would be identical at every call site")
        void refusesParameterFreeBody() {
            TestProject p = project()
                    .add("com/example/Flags.java", """
                            package com.example;
                            public final class Flags {
                                public static boolean enabled() { return true; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static boolean go() { return Flags.enabled(); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Flags", "enabled")))
                    .hasMessageContaining("doesn't reference any of its own parameters");
        }

        @Test
        @DisplayName("all-literal arguments make the substituted result a constant")
        void refusesAllLiteralArguments() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int twice(int x) { return x * 2; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static int go() { return Util.twice(3); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "twice")))
                    .hasMessageContaining("every argument actually referenced by the method body");
        }

        @Test
        @DisplayName("a unary-minus literal is a constant expression too, not just a bare literal")
        void refusesNegatedLiteralArgument() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int twice(int x) { return x * 2; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static int go() { return Util.twice(-3); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "twice")))
                    .hasMessageContaining("every argument actually referenced by the method body");
        }

        @Test
        @DisplayName("a ternary of constants is a constant expression (JLS 15.29)")
        void refusesConstantTernaryArgument() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int twice(int x) { return x * 2; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                static final boolean DEBUG = true;
                                public static int go() { return Util.twice(DEBUG ? 1 : 0); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "twice")))
                    .hasMessageContaining("every argument actually referenced by the method body");
        }

        @Test
        @DisplayName("a reference to a final constant variable is a constant expression")
        void refusesConstantVariableArgument() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int twice(int x) { return x * 2; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                static final int BASE = 3;
                                public static int go() { return Util.twice(BASE); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "twice")))
                    .hasMessageContaining("every argument actually referenced by the method body");
        }

        @Test
        @DisplayName("a non-constant argument on an UNREFERENCED parameter must not mask the promotion")
        void decoyArgumentDoesNotMaskPromotion() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int pick(int unused, int b) { return b * 2; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static int go(int x) { return Util.pick(x, 3); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "pick")))
                    .hasMessageContaining("every argument actually referenced by the method body");
        }

        @Test
        @DisplayName("a plain non-final local is NOT constant, so that must stay allowed")
        void allowsNonConstantArgument() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int twice(int x) { return x * 2; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static int go() {
                                    int n = 3;
                                    return Util.twice(n);
                                }
                            }
                            """);

            p.run(inline("Util", "twice"));

            assertThat(p.source("com/example/Main.java")).contains("(n * 2)");
            p.assertCompiles();
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("implicit conversions with no AST node of their own")
    class ImplicitConversions {

        @Test
        @DisplayName("return-type widening would change downstream arithmetic")
        void refusesReturnWidening() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static double half(int x) { return x; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static double go(int n) { return Util.half(n) / 2; }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "half")))
                    .hasMessageContaining("declared return type");
        }

        @Test
        @DisplayName("a boxed operand implies unboxing, which has no AST node the call ban can see")
        void refusesBoxedOperand() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static Integer sum(Integer a, Integer b) { return a + b; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static Integer go(Integer x, Integer y) { return Util.sum(x, y); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "sum")))
                    .hasMessageContaining("isn't primitive");
        }

        @Test
        @DisplayName("string concatenation implies toString(), same invisible-invocation problem")
        void refusesStringConcatenation() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static String join(Object a, Object b) { return a + "|" + b; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static String go(Object x, Object y) { return Util.join(x, y); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "join")))
                    .hasMessageContaining("isn't primitive");
        }

        @Test
        @DisplayName("a parameter-side boxing conversion can change overload selection")
        void refusesParameterBoxing() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static Object identity(Object o) { return o; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static Object go(int n) { return Util.identity(n); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "identity")))
                    .hasMessageContaining("declared\ntype".replace("\n", " "));
        }

        @Test
        @DisplayName("a lambda argument is a poly expression -- its type comes from context")
        void refusesLambdaArgument() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static Runnable wrap(Runnable r) { return r; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static Object go() {
                                    Object o = Util.wrap(() -> System.out.println("hi"));
                                    return o;
                                }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "wrap")))
                    .hasMessageContaining("lambda or method reference");
        }

        @Test
        @DisplayName("a method-reference argument is a poly expression too")
        void refusesMethodReferenceArgument() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static Runnable wrap(Runnable r) { return r; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                static void task() { }
                                public static Object go() {
                                    Object o = Util.wrap(Main::task);
                                    return o;
                                }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "wrap")))
                    .hasMessageContaining("lambda or method reference");
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("free references that would rebind at the call site")
    class FreeReferences {

        @Test
        void refusesUnqualifiedSiblingCall() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int base() { return 2; }
                                public static int scaled(int x) { return base() * x; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static int go(int n) { return Util.scaled(n); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "scaled")))
                    .hasMessageContaining("without a qualifier");
        }

        @Test
        void refusesTypeReference() {
            TestProject p = project()
                    .add("com/example/Point.java", """
                            package com.example;
                            public class Point {
                                public final int x;
                                public Point(int x) { this.x = x; }
                            }
                            """)
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int wrapped(int v) { return new Point(v).x; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static int go(int n) { return Util.wrapped(n); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "wrapped")))
                    .hasMessageContaining("references a type");
        }

        @Test
        void refusesThisReference() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                private int scale = 2;
                                private int scaledImpl(int x) { return this.scale * x; }
                                public int use(int x) { return scaledImpl(x); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "scaledImpl")))
                    .hasMessageContaining("'this'");
        }

        @Test
        @DisplayName("a public field on a NON-public declaring type is inaccessible (JLS 6.6.1)")
        void refusesFieldOnNonPublicType() {
            TestProject p = project()
                    .add("com/example/Box.java", """
                            package com.example;
                            class Box {
                                public int value = 1;
                            }
                            """)
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                static int read(Box b) { return b.value; }
                                public static int use(Box b) { return read(b); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "read")))
                    .hasMessageContaining("declaring type isn't public");
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("call-site positions where a value expression isn't legal")
    class StatementPosition {

        @Test
        void refusesBareExpressionStatement() {
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
                                public static void go(int n) { Util.twice(n); }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "twice")))
                    .hasMessageContaining("bare statement");
        }

        @Test
        void refusesForLoopUpdateClause() {
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
                                public static void go(int n) {
                                    for (int i = 0; i < 3; Util.twice(n)) { }
                                }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "twice")))
                    .hasMessageContaining("initialization/update clause");
        }

        @Test
        @DisplayName("a lambda body is refused: void-compatible targets need a statement expression")
        void refusesLambdaBody() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int twice(int v) { return v * 2; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            import java.util.function.IntConsumer;
                            public class Main {
                                public static void go() {
                                    IntConsumer c = v -> Util.twice(v);
                                    c.accept(21);
                                }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "twice")))
                    .hasMessageContaining("sole) body of a lambda");
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("method shapes this refactor doesn't support")
    class MethodShape {

        @Test
        void refusesNonStaticNonPrivateMethod() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public class Util {
                                public int twice(int v) { return v * 2; }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "twice")))
                    .hasMessageContaining("only static or private methods");
        }

        @Test
        void refusesMultiStatementBody() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int twice(int v) {
                                    int r = v * 2;
                                    return r;
                                }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "twice")))
                    .hasMessageContaining("single 'return <expr>;'");
        }

        @Test
        void refusesSynchronizedMethod() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static synchronized int twice(int v) { return v * 2; }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "twice")))
                    .hasMessageContaining("synchronized");
        }

        @Test
        void refusesThrowsClause() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int twice(int v) throws Exception { return v * 2; }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "twice")))
                    .hasMessageContaining("'throws' clause");
        }

        @Test
        void refusesVarargs() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int first(int... v) { return v[0]; }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "first")))
                    .hasMessageContaining("varargs");
        }

        @Test
        void refusesGenericMethod() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static <T> T identity(T t) { return t; }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "identity")))
                    .hasMessageContaining("type parameters");
        }

        @Test
        void refusesMethodReferencedByMethodReference() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int twice(int v) { return v * 2; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            import java.util.function.IntUnaryOperator;
                            public class Main {
                                public static IntUnaryOperator go() { return Util::twice; }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "twice")))
                    .hasMessageContaining("can't be textually inlined");
        }

        @Test
        @DisplayName("an unresolvable name-matching call refuses the whole all-or-nothing operation")
        void refusesWhenAnOverloadIsAmbiguous() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int twice(int v) { return v * 2; }
                                public static long twice(long v) { return v * 2; }
                            }
                            """);

            assertThat(p.expectRefused(inline("Util", "twice")))
                    .hasMessageContaining("Disambiguate with --params.");
        }
    }
}
