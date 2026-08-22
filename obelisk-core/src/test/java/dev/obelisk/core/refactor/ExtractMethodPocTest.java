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
 * The proof of concept for issue #16: is parameter-passing a REPAIR?
 *
 * <p>The candidate assessment said no -- that it is a precondition, and
 * that extract-method is therefore an almost purely refusal-shaped refactor
 * not worth building. These tests are the argument that half of that is
 * wrong. Extraction discards the implicit link "this name is in scope", and
 * a parameter is the explicit construct restoring it: the data-flow
 * instance of what a qualifier does for a binding.
 *
 * <p>The tests that ALLOW an extraction carry the weight here. This
 * codebase's standing risk is over-refusal, and a slice that refuses
 * everything would technically pass a suite of refusal tests while proving
 * nothing.
 */
class ExtractMethodPocTest {

    @TempDir
    Path dir;

    private TestProject project() {
        return new TestProject(dir);
    }

    private Path file() {
        return dir.resolve("src/main/java/com/example/Main.java");
    }

    private TestProject.RefactorAction extract(int startLine, int endLine, String name) {
        return ctx -> ExtractMethodRefactor.run(ctx, file(), startLine, endLine, name, true);
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("the repair: values the moved code can no longer see become parameters")
    class ParameterSynthesis {

        @Test
        @DisplayName("a local the region reads becomes a parameter, and the call passes it")
        void synthesisesAParameter() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        public static int go(int seed) {
                            int base = seed * 2;
                            int scaled = base + 10;
                            System.out.println(scaled);
                            return base;
                        }
                    }
                    """);

            // Lines 5-6: `int scaled = ...;` and the println. Both read
            // `base`, which is declared outside the range.
            p.run(extract(5, 6, "report"));

            String after = p.source("com/example/Main.java");
            assertThat(after).contains("private static void report(int base) {");
            assertThat(after).contains("report(base);");
            p.assertCompiles();
        }

        @Test
        @DisplayName("several live-ins become several parameters, in first-use order")
        void synthesisesSeveralParameters() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        public static void go() {
                            int width = 3;
                            String label = "box";
                            System.out.println(label + width);
                            System.out.println(width);
                        }
                    }
                    """);

            p.run(extract(6, 7, "print"));

            String after = p.source("com/example/Main.java");
            assertThat(after).contains("private static void print(String label, int width) {");
            assertThat(after).contains("print(label, width);");
            p.assertCompiles();
        }

        @Test
        @DisplayName("a field is NOT passed -- the moved code still reaches it")
        void doesNotPassFields() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        static int limit = 5;
                        public static void go() {
                            int n = 2;
                            System.out.println(limit);
                            System.out.println(n);
                        }
                    }
                    """);

            p.run(extract(6, 7, "print"));

            String after = p.source("com/example/Main.java");
            assertThat(after).contains("private static void print(int n) {");
            assertThat(after).doesNotContain("int limit, ");
            p.assertCompiles();
        }

        @Test
        @DisplayName("an instance method extracts without 'static'")
        void extractsFromAnInstanceMethod() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        public void go() {
                            int n = 2;
                            System.out.println(n);
                            System.out.println(n + 1);
                        }
                    }
                    """);

            p.run(extract(5, 6, "print"));

            String after = p.source("com/example/Main.java");
            assertThat(after).contains("private void print(int n) {");
            assertThat(after).doesNotContain("private static void print");
            p.assertCompiles();
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("the outward half: one value carried back out")
    class ReturnValueSynthesis {

        @Test
        @DisplayName("a variable declared inside and used after becomes the return value")
        void synthesisesAReturnValue() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        public static int go(int seed) {
                            int doubled = seed * 2;
                            int total = doubled + 1;
                            return total;
                        }
                    }
                    """);

            p.run(extract(5, 5, "combine"));

            String after = p.source("com/example/Main.java");
            assertThat(after).contains("private static int combine(int doubled) {");
            assertThat(after).contains("return total;");
            assertThat(after).contains("int total = combine(doubled);");
            p.assertCompiles();
        }

        @Test
        @DisplayName("two values still needed afterwards is refused, not guessed at")
        void refusesTwoLiveOutValues() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        public static int go(int seed) {
                            int a = seed + 1;
                            int b = seed + 2;
                            return a + b;
                        }
                    }
                    """);

            assertThat(p.expectRefused(Check.MULTIPLE_LIVE_OUT_VALUES, extract(4, 5, "compute")))
                    .hasMessageContaining("A method returns one value");
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("the boundary of the slice")
    class SliceBoundary {

        @Test
        @DisplayName("a return leaving the range is refused, and says why")
        void refusesNonLocalReturn() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        public static int go(int n) {
                            if (n > 0) {
                                return n;
                            }
                            return 0;
                        }
                    }
                    """);

            assertThat(p.expectRefused(Check.REJECT_NON_LOCAL_EXIT, extract(4, 6, "guard")))
                    .hasMessageContaining("needs branching at the call site");
        }

        @Test
        @DisplayName("a break leaving the range is refused")
        void refusesEscapingBreak() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        public static void go() {
                            for (int i = 0; i < 3; i++) {
                                System.out.println(i);
                                break;
                            }
                        }
                    }
                    """);

            assertThat(p.expectRefused(Check.REJECT_NON_LOCAL_EXIT, extract(5, 6, "body")))
                    .hasMessageContaining("leaves this range");
        }

        /**
         * A loop wholly inside the range takes its own {@code break} with
         * it, so nothing escapes -- the case that would be lost if the
         * check tested for the NODE KIND rather than for whether the jump's
         * target travels with it. That is the recurring bug class in
         * CLAUDE.md, and this test is what stops it being written that way.
         */
        @Test
        @DisplayName("a break whose own loop is inside the range must stay allowed")
        void allowsBreakWhoseLoopTravelsWithIt() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        public static void go(int limit) {
                            for (int i = 0; i < limit; i++) {
                                if (i == 2) {
                                    break;
                                }
                            }
                            System.out.println("done");
                        }
                    }
                    """);

            p.run(extract(4, 8, "loop"));

            String after = p.source("com/example/Main.java");
            assertThat(after).contains("private static void loop(int limit) {");
            p.assertCompiles();
        }

        @Test
        @DisplayName("assigning a live-in that is read afterwards is refused")
        void refusesWriteToLiveIn() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        public static int go() {
                            int total = 0;
                            total = total + 5;
                            return total;
                        }
                    }
                    """);

            assertThat(p.expectRefused(Check.REJECT_WRITE_TO_LIVE_IN, extract(5, 5, "bump")))
                    .hasMessageContaining("Java passes by value");
        }

        @Test
        @DisplayName("a lambda in the range is refused -- its type is reported from its target")
        void refusesPolyExpression() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    import java.util.function.IntSupplier;
                    public class Main {
                        public static void go() {
                            int n = 1;
                            IntSupplier s = () -> n;
                            System.out.println(s.getAsInt());
                        }
                    }
                    """);

            assertThat(p.expectRefused(Check.REJECT_POLY_EXPRESSION_IN_REGION, extract(6, 7, "run")))
                    .hasMessageContaining("reports from its target rather than from itself");
        }

        @Test
        @DisplayName("a range covering no whole statement is refused")
        void refusesRangeWithNoWholeStatements() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        public static void go() {
                            int n = 1;
                            System.out.println(n);
                        }
                    }
                    """);

            // Line 3 is the method header -- no statement lies wholly inside it.
            assertThat(p.expectRefused(Check.INVALID_REGION, extract(3, 3, "part")))
                    .hasMessageContaining("must cover complete statements");
        }

        @Test
        @DisplayName("statements outside any method are refused")
        void refusesRegionOutsideAMethod() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        static int n;
                        static {
                            n = 1;
                        }
                    }
                    """);

            assertThat(p.expectRefused(Check.REGION_NOT_IN_A_METHOD, extract(5, 5, "init")))
                    .hasMessageContaining("only supports statements in a method");
        }

        @Test
        @DisplayName("a name the enclosing type already uses for a method is refused")
        void refusesDuplicateName() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        static void print() { }
                        public static void go() {
                            int n = 1;
                            System.out.println(n);
                        }
                    }
                    """);

            assertThat(p.expectRefused(Check.REJECT_DUPLICATE_EXTRACTED_NAME, extract(6, 6, "print")))
                    .hasMessageContaining("will not add an overload");
        }
    }
}
