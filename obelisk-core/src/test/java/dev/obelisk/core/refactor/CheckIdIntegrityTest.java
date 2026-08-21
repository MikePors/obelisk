package dev.obelisk.core.refactor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces the half of check-identity integrity that the annotation
 * processor structurally cannot.
 *
 * <p>{@code GuardProcessor} guarantees that no two {@code @Guard}-annotated
 * METHODS claim the same {@link dev.obelisk.guard.Check}, and that every
 * {@code reject*}/{@code verify*} method declares one. That is everything an
 * annotation processor can see: {@code javax.lang.model} exposes
 * declarations, not statements, so which {@code Check} a
 * {@code new RefactorException(Check.X, ...)} actually passes is invisible
 * to it.
 *
 * <p>That gap let a real bug through. A bulk assignment script mis-scanned
 * for enclosing methods and gave three unrelated throw sites
 * {@code INVALID_IDENTIFIER} -- so messages like "Could not determine the
 * fully-qualified name" were labelled an invalid-identifier error, while
 * three constants sat unused. It compiled, passed the whole suite, and was
 * only found by a manual sweep. A wrong identity is worse than none, because
 * it actively misleads.
 */
class CheckIdIntegrityTest {

    /**
     * The one {@link dev.obelisk.guard.Check} deliberately shared across
     * unrelated methods: an internal-invariant failure is the same kind of
     * event wherever it happens, and inventing a constant per site would be
     * noise.
     */
    private static final Set<String> INTENTIONALLY_SHARED = Set.of("INTERNAL_ERROR");

    /**
     * A method DECLARATION, which here always carries an access or static
     * modifier. Requiring one matters: without it the pattern also matches an
     * indented CALL statement, because the return-type character class can
     * absorb the leading indentation -- which made this test report three
     * phantom violations on its first run.
     *
     * <p>The return-type class admits SPACES, which looks wrong next to that
     * warning and is not: a generic return type contains one
     * ({@code Map<NameExpr, Expression>}), and excluding spaces meant such a
     * declaration silently did not match at all. Every throw site inside the
     * method was then attributed to whichever method matched BEFORE it, and
     * this test reported a check as shared between two methods that do not
     * share it. The leading-modifier anchor is what keeps indentation out,
     * so the space is safe here -- another instance of matching on a
     * syntactic shape rather than the property meant.
     */
    private static final Pattern DECLARATION = Pattern.compile(
            "^ {4}(?:public |private |protected |static |final )+[\\w<>,\\[\\].? ]+ +(\\w+)\\(");
    private static final Pattern GUARD = Pattern.compile("@Guard\\(Check\\.([A-Z_]+)\\)");
    private static final Pattern THROWN = Pattern.compile("RefactorException\\(Check\\.([A-Z_]+)");

    @Test
    @DisplayName("every Check constant is actually used")
    void everyConstantIsUsed() {
        Set<String> declared = declaredConstants();
        Set<String> used = new LinkedHashSet<>(throwSitesByCheck().keySet());
        used.addAll(guardedMethods().values());

        assertThat(declared)
                .as("""
                        These Check constants are declared but never used. Usually it means a throw site was \
                        given the WRONG constant -- the one intended for it went unused while some other \
                        identity got attached to the message. A wrong identity is worse than none.""")
                .allSatisfy(constant -> assertThat(used).contains(constant));
    }

    @Test
    @DisplayName("a throw site inside a guarded check passes that check's own id")
    void throwSitesMatchTheirEnclosingGuard() {
        List<String> mismatches = new ArrayList<>();
        for (Path file : javaFiles(sourceRoot())) {
            String enclosing = null;
            String guard = null;
            for (String line : read(file).split("\n")) {
                Matcher g = GUARD.matcher(line);
                if (g.find()) {
                    guard = g.group(1);
                    continue;
                }
                Matcher d = DECLARATION.matcher(line);
                if (d.find()) {
                    enclosing = guard;
                    guard = null;
                }
                Matcher t = THROWN.matcher(line);
                if (t.find() && enclosing != null && !t.group(1).equals(enclosing)
                        && !INTENTIONALLY_SHARED.contains(t.group(1))) {
                    mismatches.add(file.getFileName() + ": a @Guard(" + enclosing + ") method throws Check."
                            + t.group(1));
                }
            }
        }
        assertThat(mismatches)
                .as("A check's own throw sites should carry its own identity, or the ID stops meaning "
                        + "'this check refused'.")
                .isEmpty();
    }

    @Test
    @DisplayName("a Check is not spread across unrelated methods")
    void checksAreNotSharedAcrossMethods() {
        Map<String, Set<String>> byCheck = throwSitesByCheck();
        Map<String, Set<String>> shared = new LinkedHashMap<>();
        byCheck.forEach((check, methods) -> {
            if (methods.size() > 1 && !INTENTIONALLY_SHARED.contains(check)) {
                shared.put(check, methods);
            }
        });
        assertThat(shared)
                .as("""
                        Each of these ids is thrown from more than one differently-named method, so it no longer \
                        identifies one failure. (The same method name repeated across refactor classes is fine -- \
                        that is one logical failure with several copies.) Either give the sites distinct \
                        constants, or add the id to INTENTIONALLY_SHARED with a reason.""")
                .isEmpty();
    }

    // ------------------------------------------------------------------

    /** Check id -> the distinct method NAMES that throw it. */
    private static Map<String, Set<String>> throwSitesByCheck() {
        Map<String, Set<String>> byCheck = new LinkedHashMap<>();
        for (Path file : javaFiles(sourceRoot())) {
            String enclosing = "<top level>";
            for (String line : read(file).split("\n")) {
                Matcher d = DECLARATION.matcher(line);
                if (d.find() && !line.contains("new ")) {
                    enclosing = d.group(1);
                }
                Matcher t = THROWN.matcher(line);
                if (t.find() && !"RefactorException".equals(enclosing)) {
                    byCheck.computeIfAbsent(t.group(1), k -> new LinkedHashSet<>()).add(enclosing);
                }
            }
        }
        return byCheck;
    }

    /** Method name -> the Check its @Guard declares. */
    private static Map<String, String> guardedMethods() {
        Map<String, String> guarded = new LinkedHashMap<>();
        for (Path file : javaFiles(sourceRoot())) {
            String pending = null;
            for (String line : read(file).split("\n")) {
                Matcher g = GUARD.matcher(line);
                if (g.find()) {
                    pending = g.group(1);
                    continue;
                }
                Matcher d = DECLARATION.matcher(line);
                if (d.find() && pending != null) {
                    guarded.put(d.group(1), pending);
                    pending = null;
                }
            }
        }
        return guarded;
    }

    private static Set<String> declaredConstants() {
        Path check = Path.of("..", "obelisk-guard", "src", "main", "java", "dev", "obelisk", "guard", "Check.java");
        if (!Files.exists(check)) {
            check = Path.of("obelisk-guard", "src", "main", "java", "dev", "obelisk", "guard", "Check.java");
        }
        if (!Files.exists(check)) {
            throw new IllegalStateException("Could not locate Check.java from " + Path.of("").toAbsolutePath());
        }
        Set<String> constants = new LinkedHashSet<>();
        Matcher m = Pattern.compile("^\\s{4}([A-Z][A-Z_]*),", Pattern.MULTILINE).matcher(read(check));
        while (m.find()) {
            constants.add(m.group(1));
        }
        return constants;
    }

    private static Path sourceRoot() {
        for (Path base : List.of(Path.of(""), Path.of("obelisk-core"))) {
            Path resolved = base.resolve(Path.of("src", "main", "java", "dev", "obelisk", "core"));
            if (Files.isDirectory(resolved)) {
                return resolved;
            }
        }
        throw new IllegalStateException("Could not locate the main source root from "
                + Path.of("").toAbsolutePath());
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
