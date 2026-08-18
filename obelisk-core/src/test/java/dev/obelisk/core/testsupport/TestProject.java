package dev.obelisk.core.testsupport;

import dev.obelisk.core.ProjectContext;
import dev.obelisk.core.RefactorException;
import dev.obelisk.core.refactor.RefactorResult;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A throwaway Java project on disk, for exercising a refactor end to end
 * against real sources rather than a hand-built AST.
 *
 * <p>Deliberately writes NO {@code pom.xml}: {@code ClasspathResolver}
 * returns an empty classpath when there isn't one, which keeps every test
 * hermetic and fast (no {@code mvn} subprocess, no network, no reliance on
 * the developer's local repository). JDK types still resolve, via the
 * {@code ReflectionTypeSolver} that {@code ProjectContext} always installs
 * -- which covers everything these tests need ({@code String}, {@code
 * Runnable}, {@code List}, {@code IntConsumer}, ...).
 *
 * <p>{@link #assertCompiles()} runs the real {@code javac} in-process via
 * {@link ToolProvider}. That matters more than it might look: across eight
 * rounds of review, "the refactored project still compiles" was the single
 * most effective oracle for catching a bad transformation, because the
 * failure mode this tool is most prone to is output that is syntactically
 * plausible but not actually legal Java.
 */
public final class TestProject {

    private final Path root;
    private final Path sourceRoot;

    public TestProject(Path root) {
        this.root = root;
        this.sourceRoot = root.resolve("src/main/java");
    }

    /**
     * Adds a source file. {@code relativePath} is relative to
     * {@code src/main/java}, e.g. {@code "com/example/Util.java"}.
     */
    public TestProject add(String relativePath, String source) {
        Path file = sourceRoot.resolve(relativePath);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    /** Runs {@code action} against a freshly-loaded context, expecting it to succeed. */
    public RefactorResult run(RefactorAction action) {
        try (ProjectContext ctx = ProjectContext.load(root)) {
            return action.run(ctx);
        }
    }

    /**
     * Runs {@code action} expecting it to be REFUSED, and returns the
     * exception so the caller can assert on WHY. Most of this suite is
     * refusal cases -- each one encodes a hazard that was found, by review
     * or by repro, to silently produce wrong output or an unbuildable
     * project if allowed through.
     */
    public RefactorException expectRefused(RefactorAction action) {
        return assertThrows(RefactorException.class, () -> {
            try (ProjectContext ctx = ProjectContext.load(root)) {
                action.run(ctx);
            }
        });
    }

    /** The current on-disk text of a source file, relative to {@code src/main/java}. */
    public String source(String relativePath) {
        try {
            return Files.readString(sourceRoot.resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Fails the test if the project's sources don't compile with the real javac. */
    public void assertCompiles() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            fail("No system Java compiler available -- tests must run on a JDK, not a JRE");
        }
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            sources = walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path classes = root.resolve("test-classes");
        try {
            Files.createDirectories(classes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> units =
                    files.getJavaFileObjectsFromFiles(sources.stream().map(Path::toFile).toList());
            boolean ok = compiler.getTask(null, files, diagnostics,
                    List.of("-d", classes.toString(), "-proc:none"), null, units).call();
            if (!ok) {
                List<String> errors = new ArrayList<>();
                diagnostics.getDiagnostics().forEach(d -> errors.add(d.toString()));
                fail("Refactored project no longer compiles:\n" + String.join("\n", errors));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Asserts the project does NOT compile -- used to prove a fixture's hazard is real before refactoring. */
    public void assertDoesNotCompile() {
        try {
            assertCompiles();
        } catch (AssertionError expected) {
            return;
        }
        fail("Expected this project NOT to compile, but it did");
    }

    @FunctionalInterface
    public interface RefactorAction {
        RefactorResult run(ProjectContext ctx);
    }
}
