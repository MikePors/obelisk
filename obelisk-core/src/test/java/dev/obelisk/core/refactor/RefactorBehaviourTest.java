package dev.obelisk.core.refactor;

import dev.obelisk.core.testsupport.TestProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour the refusal-check suite does not cover: what the refactors
 * actually PRODUCE, and the promises they make about writing to disk.
 *
 * <p>{@code tools/mutation-check.sh} measures only whether each {@code
 * reject*} check is exercised. A refactor could pass every one of those and
 * still emit wrong output, destroy formatting, or write files during a dry
 * run -- none of which any existing test would notice.
 */
class RefactorBehaviourTest {

    @TempDir
    Path dir;

    private TestProject project() {
        return new TestProject(dir);
    }

    private Path file(String relativePath) {
        return dir.resolve("src/main/java/" + relativePath);
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("--dry-run must not touch the disk")
    class DryRun {

        @Test
        void renameMethodDryRunLeavesFilesUnchanged() {
            TestProject p = project()
                    .add("com/example/Greeter.java", """
                            package com.example;
                            public class Greeter {
                                public String greet() { return "hi"; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static String go() { return new Greeter().greet(); }
                            }
                            """);
            String before = p.source("com/example/Greeter.java");
            String callerBefore = p.source("com/example/Main.java");

            RefactorResult result = p.run(ctx ->
                    RenameMethodRefactor.run(ctx, "Greeter", "greet", "salute", null, false));

            assertThat(result.applied()).isFalse();
            assertThat(result.diffs()).isNotEmpty();
            assertThat(p.source("com/example/Greeter.java")).isEqualTo(before);
            assertThat(p.source("com/example/Main.java")).isEqualTo(callerBefore);
        }

        @Test
        @DisplayName("rename-class dry run must not rename the FILE either")
        void renameClassDryRunLeavesTheFileInPlace() {
            TestProject p = project().add("com/example/Formatter.java", """
                    package com.example;
                    public class Formatter { }
                    """);

            RefactorResult result = p.run(ctx ->
                    RenameClassRefactor.run(ctx, "Formatter", "TextFormatter", false));

            assertThat(result.applied()).isFalse();
            assertThat(Files.exists(file("com/example/Formatter.java"))).isTrue();
            assertThat(Files.exists(file("com/example/TextFormatter.java"))).isFalse();
        }

        @Test
        void extractVariableDryRunLeavesFileUnchanged() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main {
                        static int compute(int n) { return n + 3; }
                        public static int go() {
                            return compute(3) * 2;
                        }
                    }
                    """);
            String before = p.source("com/example/Main.java");
            int[] r = p.rangeOf("com/example/Main.java", "compute(3)");

            p.run(ctx -> ExtractVariableRefactor.run(
                    ctx, file("com/example/Main.java"), r[0], r[1], r[2], r[3], "c", false));

            assertThat(p.source("com/example/Main.java")).isEqualTo(before);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("formatting and surrounding text are preserved")
    class Formatting {

        @Test
        @DisplayName("comments, blank lines and indentation survive a rename")
        void preservesCommentsAndLayout() {
            TestProject p = project().add("com/example/Greeter.java", """
                    package com.example;

                    /** Says hello. */
                    public class Greeter {

                        // a deliberately odd layout
                        public    String   greet( ) {
                            return "hi";   // trailing comment
                        }
                    }
                    """);

            p.run(ctx -> RenameMethodRefactor.run(ctx, "Greeter", "greet", "salute", null, true));

            String after = p.source("com/example/Greeter.java");
            assertThat(after)
                    .contains("/** Says hello. */")
                    .contains("// a deliberately odd layout")
                    .contains("// trailing comment")
                    .contains("public    String   salute( )")
                    .contains("\n\n");
        }

        @Test
        @DisplayName("CRLF line endings are not silently rewritten to LF")
        void preservesCrlfLineEndings() {
            String crlf = "package com.example;\r\n"
                    + "public class Greeter {\r\n"
                    + "    public String greet() { return \"hi\"; }\r\n"
                    + "}\r\n";
            TestProject p = project().add("com/example/Greeter.java", crlf);

            p.run(ctx -> RenameMethodRefactor.run(ctx, "Greeter", "greet", "salute", null, true));

            String after = p.source("com/example/Greeter.java");
            assertThat(after).contains("salute");
            assertThat(after.replace("\r\n", "")).doesNotContain("\n");
        }

        @Test
        @DisplayName("only the targeted occurrence changes, not same-named unrelated members")
        void doesNotTouchUnrelatedSameNamedMembers() {
            TestProject p = project()
                    .add("com/example/Greeter.java", """
                            package com.example;
                            public class Greeter {
                                public String greet() { return "hi"; }
                            }
                            """)
                    .add("com/example/Other.java", """
                            package com.example;
                            public class Other {
                                public String greet() { return "unrelated"; }
                                public String use() { return greet(); }
                            }
                            """);

            p.run(ctx -> RenameMethodRefactor.run(ctx, "Greeter", "greet", "salute", null, true));

            assertThat(p.source("com/example/Other.java"))
                    .contains("public String greet()")
                    .contains("return greet();")
                    .doesNotContain("salute");
            p.assertCompiles();
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("references the renames must follow")
    class ReferenceKinds {

        @Test
        void renamesStaticImportsAndTheirUses() {
            TestProject p = project()
                    .add("com/example/Util.java", """
                            package com.example;
                            public final class Util {
                                public static int twice(int v) { return v * 2; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            import static com.example.Util.twice;
                            public class Main {
                                public static int go(int n) { return twice(n); }
                            }
                            """);

            p.run(ctx -> RenameMethodRefactor.run(ctx, "Util", "twice", "doubled", null, true));

            assertThat(p.source("com/example/Main.java"))
                    .contains("import static com.example.Util.doubled;")
                    .contains("return doubled(n);");
            p.assertCompiles();
        }

        @Test
        void renamesMethodReferences() {
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

            p.run(ctx -> RenameMethodRefactor.run(ctx, "Util", "twice", "doubled", null, true));

            assertThat(p.source("com/example/Main.java")).contains("Util::doubled");
            p.assertCompiles();
        }

        @Test
        @DisplayName("an override family is renamed together, not left half-updated")
        void renamesTheWholeOverrideFamily() {
            TestProject p = project()
                    .add("com/example/Shape.java", """
                            package com.example;
                            public interface Shape {
                                double area();
                            }
                            """)
                    .add("com/example/Square.java", """
                            package com.example;
                            public class Square implements Shape {
                                @Override
                                public double area() { return 4.0; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static double go(Shape s) { return s.area(); }
                            }
                            """);

            p.run(ctx -> RenameMethodRefactor.run(ctx, "Shape", "area", "surface", null, true));

            assertThat(p.source("com/example/Shape.java")).contains("double surface()");
            assertThat(p.source("com/example/Square.java")).contains("public double surface()");
            assertThat(p.source("com/example/Main.java")).contains("s.surface()");
            p.assertCompiles();
        }

        @Test
        @DisplayName("rename-class follows imports, constructors and nested references")
        void renameClassFollowsConstructorsAndImports() {
            TestProject p = project()
                    .add("com/example/Widget.java", """
                            package com.example;
                            public class Widget {
                                public Widget() { }
                                public static Widget make() { return new Widget(); }
                            }
                            """)
                    .add("com/other/Main.java", """
                            package com.other;
                            import com.example.Widget;
                            public class Main {
                                public static Widget go() {
                                    Widget w = new Widget();
                                    return w;
                                }
                            }
                            """);

            p.run(ctx -> RenameClassRefactor.run(ctx, "Widget", "Gadget", true));

            assertThat(p.source("com/example/Gadget.java"))
                    .contains("public class Gadget")
                    .contains("public Gadget() { }")
                    .contains("return new Gadget();");
            assertThat(p.source("com/other/Main.java"))
                    .contains("import com.example.Gadget;")
                    .contains("Gadget w = new Gadget();");
            p.assertCompiles();
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("target lookup")
    class TargetLookup {

        @Test
        void reportsAMissingClassClearly() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main { }
                    """);

            assertThat(p.expectRefused(ctx ->
                    RenameMethodRefactor.run(ctx, "Nope", "x", "y", null, true)))
                    .hasMessageContaining("Nope");
        }

        @Test
        @DisplayName("an ambiguous simple name is refused rather than guessed")
        void refusesAmbiguousSimpleName() {
            TestProject p = project()
                    .add("a/Thing.java", """
                            package a;
                            public class Thing { public void go() { } }
                            """)
                    .add("b/Thing.java", """
                            package b;
                            public class Thing { public void go() { } }
                            """);

            assertThat(p.expectRefused(ctx ->
                    RenameMethodRefactor.run(ctx, "Thing", "go", "run", null, true)))
                    .isNotNull();
        }

        @Test
        void reportsAMissingMethodClearly() {
            TestProject p = project().add("com/example/Main.java", """
                    package com.example;
                    public class Main { }
                    """);

            assertThat(p.expectRefused(ctx ->
                    RenameMethodRefactor.run(ctx, "Main", "absent", "y", null, true)))
                    .hasMessageContaining("absent");
        }
    }
}
