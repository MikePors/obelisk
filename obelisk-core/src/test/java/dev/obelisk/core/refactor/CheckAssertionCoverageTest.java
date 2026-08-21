package dev.obelisk.core.refactor;

import dev.obelisk.guard.Check;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@link Check} is either pinned by a test that asserts its identity,
 * or listed below with the reason it cannot be.
 *
 * <p>Why this is a test and not a note: the gap was found by grepping once,
 * by hand, and a hand count decays the moment someone adds a constant. This
 * codebase has been burned three times by treating a one-off measurement as
 * a standing fact -- CLAUDE.md's rule is to "prefer an invariant that makes
 * the partial state fail the build", because that turns "did I get them
 * all?" from a judgement into a build error.
 *
 * <p>{@code CheckIdIntegrityTest} proves each constant is USED by production
 * code. This proves each is EXERCISED by a test. They are different
 * properties: a check can be thrown from exactly the right place and never
 * once be reached by the suite, which is the state most of this list was in.
 *
 * <p><b>On the exemption list.</b> A hand-drawn exemption is precisely the
 * shortcut that has gone wrong here before -- an earlier {@code
 * RefactorException} exempted "lookup and validation" failures from carrying
 * an identity at all, and that boundary had to be removed. So each entry
 * below states a reason that is checkable by someone else, and the four
 * categories are deliberately narrow. If an entry's reason is "it is hard to
 * write", it does not belong here; nine constants that looked like this list
 * turned out to be ordinary fixtures (see {@code
 * LookupAndValidationChecksTest}).
 */
class CheckAssertionCoverageTest {

    /**
     * Reachable only when an EXTERNAL process or the filesystem fails.
     * Fixtures here deliberately ship no {@code pom.xml}, so no {@code mvn}
     * subprocess ever runs -- which is what keeps the suite hermetic and
     * three seconds long. Exercising these means giving that up, or
     * injecting faults into {@code ClasspathResolver}.
     */
    private static final Set<String> ENVIRONMENT = Set.of(
            "CLASSPATH_RESOLUTION_FAILED",
            "CLASSPATH_OUTPUT_UNREADABLE",
            "CLASSPATH_PROCESS_FAILED",
            "CLASSPATH_PROCESS_INTERRUPTED",
            "CLASSPATH_DIRECTORY_UNREADABLE",
            "WRITE_FAILED");

    /**
     * Reachable only when the symbol solver fails on source that parsed
     * cleanly. The refactors mostly WARN and continue on an unresolvable
     * reference rather than refusing, so reaching the throw means
     * constructing a fixture the solver chokes on -- which pins JavaParser's
     * current behaviour rather than obelisk's, and would break on a version
     * bump for reasons that say nothing about this code.
     */
    private static final Set<String> RESOLVER_FAILURE = Set.of(
            "TARGET_METHOD_UNRESOLVABLE",
            "TARGET_FIELD_UNRESOLVABLE",
            "TARGET_TYPE_UNRESOLVABLE",
            "CALL_UNRESOLVABLE",
            "CALL_TYPE_UNRESOLVABLE",
            "METHOD_REFERENCE_UNRESOLVABLE",
            // Fires when a pre-existing reference to the NEW name cannot be
            // resolved, so whether the rename would rebind it is unknowable.
            "SHADOWED_REFERENCE_UNRESOLVABLE",
            // Its own two throw sites are both resolver failures: the target
            // class not resolving, and an ancestor with no type declaration.
            // The hazard it exists for is refused by
            // REJECT_STATIC_INITIALIZATION_EFFECT_ON, which IS pinned.
            "REJECT_DECLARING_TYPE_STATIC_INITIALIZATION_EFFECT");

    /** A bug in obelisk, not something any input can produce. */
    private static final Set<String> INTERNAL_INVARIANT = Set.of(
            "INTERNAL_ERROR",
            "ARGUMENT_COUNT_MISMATCH");

    /**
     * Unreachable while every precondition holds -- a broader check refuses
     * the case first, or the state it guards cannot be constructed.
     * {@code tools/mutation-allowlist.txt} carries the same set with the
     * subsuming check named for each.
     *
     * <p>Four of these are nonetheless VERIFIED, by
     * {@code tools/repair-mutation-check.sh}: it breaks the repair each one
     * guards and requires the verifier to notice. That is how
     * {@code VERIFY_QUALIFIED_CALLS} was caught checking the qualifier it
     * was handed rather than the one emitted. The two that guard no
     * particular repair are still genuinely untested, and saying so is the
     * point of this list.
     */
    private static final Set<String> SUBSUMED_OR_BACKSTOP = Set.of(
            "REJECT_SELF_RECURSION",
            "REJECT_PARAMETER_WRITES",
            "REJECT_DEFERRED_EVALUATION_CONSTRUCTS",
            "REJECT_DUPLICATE_SIGNATURE",
            // findField only ever returns a declarator from a FieldDeclaration,
            // so the not-a-field branch cannot be reached today. It guards the
            // day that is widened -- see LookupAndValidationChecksTest.
            "TARGET_FIELD_NOT_A_FIELD",
            "VERIFY_BINDINGS_PRESERVED",
            "VERIFY_EVERYTHING_STILL_RESOLVES",
            "VERIFY_QUALIFIED_CALLS",
            "VERIFY_REPAIRS_BIND_TO_ORIGINAL_DECLARATION",
            // Verified the same way, and it earned the entry: fault
            // injection caught it comparing declarations without checking
            // legality BEFORE it ever shipped.
            "VERIFY_SHADOWED_REFERENCES",
            // Cross-file, so structural rather than resolution-based -- see
            // its Javadoc. Verified by fault injection all the same.
            "VERIFY_QUALIFIED_TYPE_REFERENCES");

    private static final Map<String, Set<String>> EXEMPT = Map.of(
            "needs an external process or filesystem fault", ENVIRONMENT,
            "needs the symbol solver to fail on parseable source", RESOLVER_FAILURE,
            "an internal invariant no input can trigger", INTERNAL_INVARIANT,
            "subsumed by a broader check, or a backstop", SUBSUMED_OR_BACKSTOP);

    @Test
    @DisplayName("every Check is asserted by a test, or exempt for a stated reason")
    void everyCheckIsAssertedOrExempt() {
        Set<String> unpinned = new TreeSet<>(Arrays.stream(Check.values()).map(Enum::name).toList());
        unpinned.removeAll(assertedInTests());
        EXEMPT.values().forEach(unpinned::removeAll);

        assertThat(unpinned)
                .as("""
                        These Checks are not asserted by any test and are not exempt. A check nothing \
                        exercises is indistinguishable from one that does not work: the suite is green \
                        either way. Add a test asserting the ID via expectRefused(Check.X, ...), or add \
                        the constant to one of the exemption sets in this file WITH a reason someone \
                        else can check.""")
                .isEmpty();
    }

    /**
     * The exemption list must not outlive its reasons. A constant that has
     * since been deleted, or that a test now pins, leaves a stale entry that
     * quietly widens the exemption for whatever is added next.
     */
    @Test
    @DisplayName("no exemption is stale")
    void exemptionsAreAllStillNeeded() {
        Set<String> declared = Arrays.stream(Check.values()).map(Enum::name).collect(java.util.stream.Collectors
                .toCollection(LinkedHashSet::new));
        Set<String> asserted = assertedInTests();

        Set<String> gone = new TreeSet<>();
        Set<String> nowTested = new TreeSet<>();
        EXEMPT.values().forEach(set -> set.forEach(name -> {
            if (!declared.contains(name)) {
                gone.add(name);
            } else if (asserted.contains(name)) {
                nowTested.add(name);
            }
        }));

        assertThat(gone).as("Exempted Check constants that no longer exist.").isEmpty();
        assertThat(nowTested)
                .as("These are exempted but a test now asserts them -- remove them from the exemption "
                        + "list so it keeps meaning what it says.")
                .isEmpty();
    }

    // ------------------------------------------------------------------

    private static final Pattern ASSERTED = Pattern.compile("Check\\.([A-Z_]+)");

    /**
     * Constants named anywhere under the test sources. Deliberately textual
     * rather than reflective: what matters is that a test MENTIONS the
     * identity, and the mention is what a reviewer greps for.
     */
    private static Set<String> assertedInTests() {
        Set<String> found = new LinkedHashSet<>();
        Path root = Path.of("src", "test", "java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                // This file lists constants in order to exempt them; counting
                // its own text would make every exemption self-satisfying.
                if (file.getFileName().toString().equals("CheckAssertionCoverageTest.java")) {
                    continue;
                }
                Matcher m = ASSERTED.matcher(Files.readString(file));
                while (m.find()) {
                    found.add(m.group(1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }
}
