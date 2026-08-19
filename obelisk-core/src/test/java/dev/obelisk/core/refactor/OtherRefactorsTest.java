package dev.obelisk.core.refactor;

import dev.obelisk.core.testsupport.TestProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Baseline coverage for the four refactors that predate
 * {@link InlineMethodRefactor}.
 *
 * <p>Deliberately narrower than {@link InlineMethodRefactorTest}: these
 * pin down the core contract of each refactor (the rename actually
 * propagates to call sites, the result still compiles, and the headline
 * refusal still refuses) rather than re-deriving a full hazard catalogue.
 * The bugs previously found in these refactors were fixed before this suite
 * existed and their reproductions weren't kept, so this is a floor to build
 * on, not a claim of thorough coverage.
 */
class OtherRefactorsTest {

    @TempDir
    Path dir;

    private TestProject project() {
        return new TestProject(dir);
    }

    // ------------------------------------------------------------------
    @Nested
    class RenameMethod {

        @Test
        void renamesDeclarationAndEveryCallSite() {
            TestProject p = project()
                    .add("com/example/Greeter.java", """
                            package com.example;
                            public class Greeter {
                                public String greet(String who) { return "hi " + who; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static String go() { return new Greeter().greet("bob"); }
                            }
                            """);

            RefactorResult result = p.run(ctx ->
                    RenameMethodRefactor.run(ctx, "Greeter", "greet", "salute", null, true));

            assertThat(result.changedFiles()).hasSize(2);
            assertThat(p.source("com/example/Greeter.java")).contains("String salute(String who)");
            assertThat(p.source("com/example/Main.java")).contains(".salute(\"bob\")");
            p.assertCompiles();
        }

        @Test
        @DisplayName("an ambiguous overload needs --params rather than guessing")
        void refusesAmbiguousOverload() {
            TestProject p = project()
                    .add("com/example/Greeter.java", """
                            package com.example;
                            public class Greeter {
                                public String greet(String who) { return who; }
                                public String greet(int who) { return "" + who; }
                            }
                            """);

            assertThat(p.expectRefused(ctx ->
                    RenameMethodRefactor.run(ctx, "Greeter", "greet", "salute", null, true)))
                    .hasMessageContaining("Disambiguate with --params, e.g.");
        }

        @Test
        @DisplayName("--params disambiguates and renames only the chosen overload")
        void disambiguatesWithParams() {
            TestProject p = project()
                    .add("com/example/Greeter.java", """
                            package com.example;
                            public class Greeter {
                                public String greet(String who) { return who; }
                                public String greet(int who) { return "" + who; }
                            }
                            """);

            p.run(ctx -> RenameMethodRefactor.run(ctx, "Greeter", "greet", "salute", "int", true));

            assertThat(p.source("com/example/Greeter.java"))
                    .contains("String salute(int who)")
                    .contains("String greet(String who)");
            p.assertCompiles();
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class RenameClass {

        @Test
        void renamesTypeReferencesAndTheFileItself() {
            TestProject p = project()
                    .add("com/example/Formatter.java", """
                            package com.example;
                            public class Formatter {
                                public String format(String s) { return s.trim(); }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static String go() {
                                    Formatter f = new Formatter();
                                    return f.format(" x ");
                                }
                            }
                            """);

            RefactorResult result = p.run(ctx ->
                    RenameClassRefactor.run(ctx, "Formatter", "TextFormatter", true));

            assertThat(result.renamedFiles()).hasSize(1);
            assertThat(p.source("com/example/TextFormatter.java")).contains("public class TextFormatter");
            assertThat(p.source("com/example/Main.java"))
                    .contains("TextFormatter f = new TextFormatter()")
                    .doesNotContain("Formatter f = new Formatter()");
            p.assertCompiles();
        }

        @Test
        void refusesRenameThatCollidesWithAnExistingType() {
            TestProject p = project()
                    .add("com/example/Formatter.java", """
                            package com.example;
                            public class Formatter { }
                            """)
                    .add("com/example/TextFormatter.java", """
                            package com.example;
                            public class TextFormatter { }
                            """);

            assertThat(p.expectRefused(ctx ->
                    RenameClassRefactor.run(ctx, "Formatter", "TextFormatter", true)))
                    .hasMessageContaining("TextFormatter");
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class RenameField {

        @Test
        void renamesDeclarationAndEveryRead() {
            TestProject p = project()
                    .add("com/example/Config.java", """
                            package com.example;
                            public class Config {
                                public int timeout = 30;
                                public int doubled() { return timeout * 2; }
                            }
                            """)
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static int go() { return new Config().timeout; }
                            }
                            """);

            p.run(ctx -> RenameFieldRefactor.run(ctx, "Config", "timeout", "timeoutMillis", true));

            assertThat(p.source("com/example/Config.java"))
                    .contains("int timeoutMillis = 30")
                    .contains("return timeoutMillis * 2");
            assertThat(p.source("com/example/Main.java")).contains(".timeoutMillis");
            p.assertCompiles();
        }

        @Test
        void refusesCollisionWithAnExistingField() {
            TestProject p = project()
                    .add("com/example/Config.java", """
                            package com.example;
                            public class Config {
                                public int timeout = 30;
                                public int timeoutMillis = 30000;
                            }
                            """);

            assertThat(p.expectRefused(ctx ->
                    RenameFieldRefactor.run(ctx, "Config", "timeout", "timeoutMillis", true)))
                    .hasMessageContaining("timeoutMillis");
        }
    }

    // ------------------------------------------------------------------
    @Nested
    class ExtractVariable {

        @Test
        @DisplayName("extracts into a 'var' local, leaving the inferred type to the compiler")
        void extractsTheSelectedExpressionIntoALocal() {
            TestProject p = project()
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static int go(int a) {
                                    return a * 2 + 1;
                                }
                            }
                            """);

            Path file = dir.resolve("src/main/java/com/example/Main.java");
            // `a * 2` on line 4: columns are 1-based and the end column is inclusive.
            p.run(ctx -> ExtractVariableRefactor.run(ctx, file, 4, 16, 4, 20, "doubled", true));

            assertThat(p.source("com/example/Main.java"))
                    .contains("var doubled = a * 2;")
                    .contains("return doubled + 1;");
            p.assertCompiles();
        }

        @Test
        void refusesASelectionThatIsNotAnExpression() {
            TestProject p = project()
                    .add("com/example/Main.java", """
                            package com.example;
                            public class Main {
                                public static int go(int a) {
                                    return a * 2 + 1;
                                }
                            }
                            """);

            Path file = dir.resolve("src/main/java/com/example/Main.java");
            assertThat(p.expectRefused(ctx ->
                    ExtractVariableRefactor.run(ctx, file, 4, 9, 4, 14, "x", true)))
                    .isNotNull();
        }
    }
}
