package dev.obelisk.core;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Discovers a Maven project's runtime/compile classpath by shelling out to
 * {@code mvn dependency:build-classpath}, so symbol resolution stays in sync
 * with the project's real build rather than a hand-maintained config file.
 *
 * <p>The default path resolves the target module in isolation, which is fast
 * and read-only but fails outright if the module depends on an uninstalled
 * sibling in the same multi-module reactor. When that happens and the
 * project genuinely is part of a reactor, {@link #resolve} falls back to a
 * single reactor-aware invocation: {@code mvn -pl <module> -am compile
 * dependency:build-classpath} run from the reactor root. Both the compile
 * (needed so upstream siblings' {@code target/classes} actually has bytecode
 * in it -- a bare classpath query wouldn't populate that) and the classpath
 * query have to happen in the SAME Maven process for reactor-sibling
 * substitution to apply: it's tied to a project having been built within the
 * CURRENT session, not to whether its output directory merely exists on disk
 * from an earlier, separate invocation (confirmed empirically -- splitting
 * this into two separate `mvn` calls reintroduces the original failure even
 * though the second call's classpath query runs immediately after a
 * successful compile). This fallback only triggers when the fast path fails
 * and a reactor is detected -- it never runs, and nothing gets compiled, for
 * an ordinary single-module project or one where every dependency is already
 * installed.
 */
public final class ClasspathResolver {

    private ClasspathResolver() {
    }

    public static List<String> resolve(Path projectDir) {
        if (!Files.exists(projectDir.resolve("pom.xml"))) {
            return List.of();
        }
        try {
            return runMavenClasspath(projectDir, List.of(), "mvn dependency:build-classpath");
        } catch (RefactorException standaloneFailure) {
            Optional<Path> reactorRoot = findReactorRoot(projectDir);
            if (reactorRoot.isEmpty()) {
                throw standaloneFailure;
            }
            Path root = reactorRoot.get();
            String modulePath = relativeModulePath(root, projectDir);
            try {
                return runMavenClasspath(root, List.of("-pl", modulePath, "-am", "compile"),
                        "mvn -pl " + modulePath + " -am compile dependency:build-classpath (reactor-aware fallback)");
            } catch (RefactorException reactorFailure) {
                throw new RefactorException("Classpath resolution failed.\nStandalone attempt: "
                        + standaloneFailure.getMessage() + "\nReactor-aware fallback (reactor root " + root
                        + "): " + reactorFailure.getMessage(), reactorFailure);
            }
        }
    }

    /**
     * Walks up from {@code projectDir} looking for the outermost ancestor pom
     * that lists the directory below it as a {@code <module>} -- i.e. the
     * root of the multi-module reactor {@code projectDir} belongs to, if any.
     * Climbs past nested reactors (a reactor root that is itself a module of
     * a further-out parent) rather than stopping at the first match.
     */
    private static Optional<Path> findReactorRoot(Path projectDir) {
        Path child = projectDir.toAbsolutePath().normalize();
        Path bestRoot = null;
        while (true) {
            Path parent = child.getParent();
            if (parent == null) {
                break;
            }
            Path parentPom = parent.resolve("pom.xml");
            if (!Files.isRegularFile(parentPom)) {
                break;
            }
            Path currentChild = child;
            boolean listsChild = parseModules(parentPom).stream()
                    .anyMatch(module -> parent.resolve(module).normalize().equals(currentChild));
            if (!listsChild) {
                break;
            }
            bestRoot = parent;
            child = parent;
        }
        return Optional.ofNullable(bestRoot);
    }

    private static List<String> parseModules(Path pomFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pomFile.toFile());
            NodeList moduleNodes = doc.getElementsByTagName("module");
            List<String> modules = new ArrayList<>();
            for (int i = 0; i < moduleNodes.getLength(); i++) {
                modules.add(moduleNodes.item(i).getTextContent().trim());
            }
            return modules;
        } catch (Exception e) {
            // Malformed or unreadable pom -- treat as "declares no modules"
            // rather than failing the whole resolution over it.
            return List.of();
        }
    }

    private static String relativeModulePath(Path reactorRoot, Path projectDir) {
        Path relative = reactorRoot.relativize(projectDir.toAbsolutePath().normalize());
        return relative.toString().replace(java.io.File.separatorChar, '/');
    }

    /** Runs {@code dependency:build-classpath} (plus any extra args) and returns the resolved entries. */
    private static List<String> runMavenClasspath(Path workingDir, List<String> args, String description) {
        try {
            Path outputFile = Files.createTempFile("obelisk-classpath", ".txt");
            try {
                List<String> command = new ArrayList<>();
                command.add("mvn");
                command.add("-q");
                command.add("-B");
                command.addAll(args);
                command.add("dependency:build-classpath");
                command.add("-Dmdep.outputFile=" + outputFile.toAbsolutePath());
                runProcess(workingDir, command, description);
                return readClasspathFile(outputFile);
            } finally {
                Files.deleteIfExists(outputFile);
            }
        } catch (IOException e) {
            throw new RefactorException("Failed to read classpath output for '" + description + "': "
                    + e.getMessage(), e);
        }
    }

    private static List<String> readClasspathFile(Path outputFile) throws IOException {
        String contents = Files.readString(outputFile).strip();
        if (contents.isEmpty()) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        for (String entry : contents.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static void runProcess(Path workingDir, List<String> command, String description) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RefactorException(description + " failed (exit " + exitCode + "):\n" + output);
            }
        } catch (IOException e) {
            throw new RefactorException("Failed to run '" + description + "' in " + workingDir
                    + " (is Maven installed and on PATH?): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RefactorException("Interrupted while running '" + description + "'", e);
        }
    }
}
