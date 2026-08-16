package dev.obelisk.core;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A parsed, symbol-resolvable view of a Maven project: every {@code .java} file
 * under its source roots, wired up to a type solver that knows about the
 * project's real dependencies (see {@link ClasspathResolver}).
 */
public final class ProjectContext {

    private final Path projectDir;
    private final List<Path> sourceRoots;
    private final Map<Path, CompilationUnit> unitsByFile;

    private ProjectContext(Path projectDir, List<Path> sourceRoots, Map<Path, CompilationUnit> unitsByFile) {
        this.projectDir = projectDir;
        this.sourceRoots = sourceRoots;
        this.unitsByFile = unitsByFile;
    }

    public static ProjectContext load(Path projectDir) {
        List<Path> sourceRoots = discoverSourceRoots(projectDir);
        if (sourceRoots.isEmpty()) {
            throw new RefactorException(
                    "No source roots found under " + projectDir + " (expected src/main/java and/or src/test/java)");
        }

        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        for (Path sourceRoot : sourceRoots) {
            typeSolver.add(new JavaParserTypeSolver(sourceRoot));
        }
        for (String entry : ClasspathResolver.resolve(projectDir)) {
            Path entryPath = Path.of(entry);
            if (Files.isRegularFile(entryPath) && entry.endsWith(".jar")) {
                try {
                    typeSolver.add(new JarTypeSolver(entryPath));
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to load classpath jar: " + entry, e);
                }
            } else if (Files.isDirectory(entryPath)) {
                // ClasspathResolver currently resolves this single module in
                // isolation (not reactor-aware), so an uninstalled sibling
                // module fails there outright rather than surfacing as a
                // directory entry here -- this branch is dormant today. It's
                // kept for when that changes (reactor-aware resolution, or
                // Gradle support), since a directory of .class files is valid
                // read-only resolution context either way: obelisk never
                // renames anything outside the requested project's own source
                // roots, so loading them here doesn't put them at risk of
                // being mutated.
                try {
                    URL url = entryPath.toUri().toURL();
                    ClassLoader classLoader = new URLClassLoader(new URL[]{url}, ProjectContext.class.getClassLoader());
                    typeSolver.add(new ClassLoaderTypeSolver(classLoader));
                } catch (MalformedURLException e) {
                    throw new RefactorException("Failed to load classpath directory: " + entry, e);
                }
            }
        }

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        ParserConfiguration config = new ParserConfiguration()
                .setSymbolResolver(symbolSolver)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        // Use a JavaParser instance scoped to this call rather than the
        // StaticJavaParser global/static configuration: obelisk will eventually
        // run as a long-lived MCP server handling multiple projects, possibly
        // concurrently, and a shared static parser config would let one
        // in-flight load() clobber another's symbol resolver.
        JavaParser parser = new JavaParser(config);

        Map<Path, CompilationUnit> unitsByFile = new LinkedHashMap<>();
        for (Path sourceRoot : sourceRoots) {
            for (Path javaFile : findJavaFiles(sourceRoot)) {
                ParseResult<CompilationUnit> parseResult;
                try {
                    parseResult = parser.parse(javaFile);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                    throw new RefactorException(
                            "Failed to parse " + javaFile + ": " + parseResult.getProblems());
                }
                CompilationUnit cu = parseResult.getResult().get();
                LexicalPreservingPrinter.setup(cu);
                unitsByFile.put(javaFile, cu);
            }
        }

        return new ProjectContext(projectDir, sourceRoots, unitsByFile);
    }

    private static List<Path> discoverSourceRoots(Path projectDir) {
        return Stream.of("src/main/java", "src/test/java")
                .map(projectDir::resolve)
                .filter(Files::isDirectory)
                .toList();
    }

    private static List<Path> findJavaFiles(Path sourceRoot) {
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path projectDir() {
        return projectDir;
    }

    public List<Path> sourceRoots() {
        return sourceRoots;
    }

    public Map<Path, CompilationUnit> unitsByFile() {
        return unitsByFile;
    }
}
