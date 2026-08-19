package dev.obelisk.core.refactor;

import dev.obelisk.core.testsupport.TestProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capture REPAIR for rename-field: a reference that a same-named local would
 * capture is qualified rather than refused.
 *
 * <p>This is the repair half of the strategy in CLAUDE.md. Renaming a field
 * onto a name that some enclosing method already uses for a parameter used to
 * be refused outright, which rules out the entirely ordinary
 * {@code void setTotal(int count)} shape.
 */
class CaptureRepairTest {

    @TempDir
    Path dir;

    private TestProject project() {
        return new TestProject(dir);
    }

    @Test
    @DisplayName("a reference a parameter would capture is qualified with 'this'")
    void qualifiesReferenceCapturedByParameter() {
        TestProject p = project()
                .add("com/example/Config.java", """
                        package com.example;
                        public class Config {
                            private int total = 10;
                            public int use(int count) { return total + count; }
                            public int plain() { return total * 2; }
                        }
                        """)
                .add("com/example/Main.java", """
                        package com.example;
                        public class Main {
                            public static String go() {
                                Config c = new Config();
                                return c.use(5) + "," + c.plain();
                            }
                        }
                        """);

        p.run(ctx -> RenameFieldRefactor.run(ctx, "Config", "total", "count", true));

        String after = p.source("com/example/Config.java");
        // Repaired where the parameter would capture it...
        assertThat(after).contains("return this.count + count;");
        // ...and left alone where nothing would.
        assertThat(after).contains("return count * 2;");
        p.assertCompiles();
    }

    @Test
    @DisplayName("only the captured reference is rewritten, not every same-named one")
    void doesNotOverQualifyUnaffectedReferences() {
        TestProject p = project().add("com/example/Config.java", """
                package com.example;
                public class Config {
                    private int total = 10;
                    public int a(int count) { return total; }
                    public int b() { return total; }
                    public int c() { return total; }
                }
                """);

        p.run(ctx -> RenameFieldRefactor.run(ctx, "Config", "total", "count", true));

        String after = p.source("com/example/Config.java");
        // JavaParser's Node.equals is structural, so an identity-based set is
        // required here; a List.contains check rewrote all three.
        assertThat(after).containsOnlyOnce("this.count");
        p.assertCompiles();
    }

    @Test
    @DisplayName("a STATIC field is qualified with its declaring type, not 'this'")
    void qualifiesStaticFieldWithDeclaringType() {
        TestProject p = project().add("com/example/Config.java", """
                package com.example;
                public class Config {
                    private static int total = 10;
                    public static int use(int count) { return total + count; }
                }
                """);

        p.run(ctx -> RenameFieldRefactor.run(ctx, "Config", "total", "count", true));

        assertThat(p.source("com/example/Config.java")).contains("Config.count + count");
        p.assertCompiles();
    }

    @Test
    @DisplayName("a pattern binding captures just like a parameter")
    void qualifiesReferenceCapturedByPatternBinding() {
        TestProject p = project().add("com/example/Config.java", """
                package com.example;
                public class Config {
                    private int total = 10;
                    public int use(Object o) {
                        if (o instanceof Integer count) {
                            return total + count;
                        }
                        return total;
                    }
                }
                """);

        p.run(ctx -> RenameFieldRefactor.run(ctx, "Config", "total", "count", true));

        assertThat(p.source("com/example/Config.java")).contains("this.count + count");
        p.assertCompiles();
    }

    @Test
    @DisplayName("hierarchy hiding is still refused -- there is no single safe qualifier")
    void stillRefusesHierarchyHiding() {
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

        // Unlike a local shadowing a field, the correct qualifier here depends
        // on where the target sits relative to the hiding declaration, so
        // this stays a refusal rather than a hopeful rewrite.
        assertThat(p.expectRefused(ctx ->
                RenameFieldRefactor.run(ctx, "Child", "total", "count", true)))
                .isNotNull();
    }
}
