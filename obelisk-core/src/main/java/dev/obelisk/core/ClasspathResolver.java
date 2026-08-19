package dev.obelisk.core;

import dev.obelisk.guard.Check;
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
 *
 * <p>Unlike the fast path, the fallback is NOT read-only: it compiles the
 * module and its upstream reactor dependencies, writing real build output.
 * This happens even under {@code --dry-run}, since resolving symbols at all
 * requires it -- a notice is printed to stderr when it triggers so this
 * isn't a silent surprise.
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
            // A retry can't help if mvn itself couldn't be started, or if we
            // were interrupted (runProcess restores the interrupt flag before
            // throwing in that case) -- rethrow immediately rather than
            // spawning a second, heavier attempt that's doomed either way.
            if (Thread.currentThread().isInterrupted() || standaloneFailure.getCause() instanceof IOException) {
                throw standaloneFailure;
            }
            Optional<Path> reactorRoot = findReactorRoot(projectDir);
            if (reactorRoot.isEmpty()) {
                throw standaloneFailure;
            }
            Path root = reactorRoot.get();
            String modulePath = relativeModulePath(root, projectDir);
            System.err.println("obelisk: sibling module dependency not resolvable in isolation; falling back to "
                    + "a reactor build (mvn -pl " + modulePath + " -am compile) from " + root
                    + " -- this compiles upstream modules and may take a while.");
            try {
                List<String> entries = runMavenClasspath(root, List.of("-pl", modulePath, "-am", "compile"),
                        "mvn -pl " + modulePath + " -am compile dependency:build-classpath (reactor-aware fallback)");
                if (entries.isEmpty()) {
                    System.err.println("obelisk: warning: reactor-aware classpath resolution for " + modulePath
                            + " returned zero entries -- if this module has real dependencies, verify manually "
                            + "with 'mvn -pl " + modulePath + " -am dependency:build-classpath' from " + root + ".");
                }
                return entries;
            } catch (RefactorException reactorFailure) {
                RefactorException combined = new RefactorException(Check.CLASSPATH_RESOLUTION_FAILED, "Classpath resolution failed.\nStandalone "
                        + "attempt: " + standaloneFailure.getMessage() + "\nReactor-aware fallback (reactor root "
                        + root + "): " + reactorFailure.getMessage(), reactorFailure);
                combined.addSuppressed(standaloneFailure);
                throw combined;
            }
        }
    }

    /**
     * Walks up from {@code projectDir} looking for the outermost ancestor pom
     * that (transitively) lists it as a {@code <module>} -- i.e. the root of
     * the multi-module reactor {@code projectDir} belongs to, if any.
     *
     * <p>A {@code <module>} entry can be a multi-segment relative path (e.g.
     * {@code <module>group/module-b</module>}) with no {@code pom.xml} of its
     * own in the intervening directory, so this keeps climbing past ancestors
     * that don't have a pom (or whose pom doesn't list the current reference
     * point) rather than stopping at the first miss -- it only updates the
     * reference point (and the best-known root) on an actual match, so an
     * unrelated pom.xml higher up the tree that doesn't list anything
     * relevant is climbed past harmlessly.
     */
    private static Optional<Path> findReactorRoot(Path projectDir) {
        Path child = projectDir.toAbsolutePath().normalize();
        Path bestRoot = null;
        Path candidate = child.getParent();
        while (candidate != null) {
            Path candidatePom = candidate.resolve("pom.xml");
            if (Files.isRegularFile(candidatePom)) {
                Path currentCandidate = candidate;
                Path matchTarget = child;
                boolean listsChild = parseModules(candidatePom).stream()
                        .anyMatch(module -> currentCandidate.resolve(module).normalize().equals(matchTarget));
                if (listsChild) {
                    bestRoot = candidate;
                    child = candidate;
                }
            }
            candidate = candidate.getParent();
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
            // rather than failing the whole resolution over it, but this
            // would silently disable the reactor-aware fallback with no clue
            // why, so make it visible.
            System.err.println("obelisk: warning: could not parse " + pomFile + " while looking for a reactor "
                    + "root (" + e.getMessage() + "); treating it as declaring no modules.");
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
            throw new RefactorException(Check.CLASSPATH_OUTPUT_UNREADABLE, "Failed to read classpath output for '" + description + "': "
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
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);
            process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RefactorException(Check.CLASSPATH_PROCESS_FAILED, description + " failed (exit " + exitCode + "):\n" + output);
            }
        } catch (IOException e) {
            throw new RefactorException(Check.CLASSPATH_PROCESS_FAILED, "Failed to run '" + description + "' in " + workingDir
                    + " (is Maven installed and on PATH?): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            if (process != null) {
                // Don't leave the child mvn process running in the background
                // after we've given up on it.
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new RefactorException(Check.CLASSPATH_PROCESS_INTERRUPTED, "Interrupted while running '" + description + "'", e);
        }
    }
}
