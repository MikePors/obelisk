package dev.obelisk.core.refactor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces that every phrase a refusal test asserts on identifies exactly ONE
 * throw site.
 *
 * <p>Three separate tests in this suite were found passing for the wrong
 * reason: each asserted a short fragment of an error message that a
 * DIFFERENT check also happened to produce, so the test went green without
 * ever reaching the check it named. Each took a review round or a mutation
 * run to notice.
 *
 * <p>The fix is not a heavier assertion mechanism -- an earlier proposal to
 * derive the originating check from the exception's stack trace was rejected
 * as inferring from an implementation detail (which method the throw happens
 * to sit in), brittle under exactly the extract-into-helper refactoring this
 * codebase does constantly. Message assertions are fine; they just have to be
 * DISTINCTIVE, and nothing was enforcing that.
 *
 * <p>So this test does the enforcing. It reads the production sources for
 * {@code RefactorException} throw sites, reads the test sources for
 * {@code hasMessageContaining} phrases, and fails if any phrase could have
 * come from more than one site. It catches both directions of drift: a new
 * test asserting something too generic, and a message reworded until it
 * collides with another.
 */
class MessageDistinctivenessTest {

    private static final Pattern THROW_SITE =
            Pattern.compile("RefactorException\\((.*?)\\);", Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ASSERTED_PHRASE =
            Pattern.compile("hasMessageContaining\\(\"((?:[^\"\\\\]|\\\\.)*)\"\\)");

    @Test
    @DisplayName("no asserted phrase is producible by more than one check")
    void assertedPhrasesAreNotAmbiguous() {
        List<String> throwSites = messageTemplates(sourceRoot("main"));
        assertThat(throwSites)
                .as("should have found the RefactorException throw sites; is the source layout as expected?")
                .isNotEmpty();

        List<String> ambiguous = new ArrayList<>();
        for (Path file : javaFiles(sourceRoot("test"))) {
            if (file.getFileName().toString().equals("MessageDistinctivenessTest.java")
                    || file.toString().contains("testsupport")) {
                continue;
            }
            Matcher m = ASSERTED_PHRASE.matcher(read(file));
            while (m.find()) {
                String phrase = m.group(1).replace("\\\"", "\"");
                long matches = throwSites.stream().filter(site -> site.contains(phrase)).count();
                if (matches > 1) {
                    ambiguous.add(file.getFileName() + ": \"" + phrase + "\" (" + matches + " checks)");
                }
            }
        }

        assertThat(ambiguous)
                .as("""
                        Each of these phrases appears in the message of MORE THAN ONE check, so a test asserting \
                        it can be satisfied by a check other than the one it means to exercise. That has happened \
                        three times here, each costing a review round or a mutation run to notice. Assert instead \
                        on a clause unique to the intended check -- usually from the half of its message that \
                        explains WHY rather than WHAT.""")
                .isEmpty();
    }

    /** Flattens each throw site's concatenated string literals into one searchable line. */
    private static List<String> messageTemplates(Path root) {
        List<String> templates = new ArrayList<>();
        for (Path file : javaFiles(root)) {
            String text = read(file);
            Matcher site = THROW_SITE.matcher(text);
            while (site.find()) {
                StringBuilder message = new StringBuilder();
                Matcher literal = STRING_LITERAL.matcher(site.group(1));
                while (literal.find()) {
                    message.append(literal.group(1));
                }
                templates.add(message.toString().replaceAll("\\s+", " "));
            }
        }
        return templates;
    }

    /**
     * Locates a source root by walking up from the working directory, so the
     * test works whether Maven runs it from the module or the reactor root.
     * Fails loudly rather than skipping if the layout is not as expected --
     * a silently-skipped enforcement test is worse than none.
     */
    private static Path sourceRoot(String which) {
        Path candidate = Path.of("src", which, "java", "dev", "obelisk", "core");
        for (Path base : List.of(Path.of(""), Path.of("obelisk-core"))) {
            Path resolved = base.resolve(candidate);
            if (Files.isDirectory(resolved)) {
                return resolved;
            }
        }
        throw new IllegalStateException("Could not locate the " + which + " source root from "
                + Path.of("").toAbsolutePath());
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
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
