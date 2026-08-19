package dev.obelisk.core;

import dev.obelisk.guard.Check;

import java.util.Optional;

/**
 * Raised whenever obelisk cannot safely carry out a refactor and must fail
 * loudly.
 *
 * <p>Carries the {@link Check} that refused, where one did. That identity is
 * the contract a test should assert on; the message is presentation, free to
 * be reworded without breaking anything.
 *
 * <p>It exists because for a long time a check's identity lived nowhere in
 * the code -- it was implied by the prose of its message, and tests asserted
 * on fragments of that prose. Three tests were found passing for the wrong
 * reason when two checks' messages happened to share a phrase. Message
 * distinctness is still enforced (see {@code MessageDistinctivenessTest}),
 * but it is no longer the only thing standing between a test and a false
 * pass.
 *
 * <p>Absent for failures that are not a safety check refusing: IO errors,
 * resolver failures, and argument validation. Those genuinely have no check
 * identity, and inventing one would be worse than admitting it.
 */
public class RefactorException extends RuntimeException {

    private final transient Check check;

    public RefactorException(String message) {
        this(null, message, null);
    }

    public RefactorException(String message, Throwable cause) {
        this(null, message, cause);
    }

    public RefactorException(Check check, String message) {
        this(check, message, null);
    }

    public RefactorException(Check check, String message, Throwable cause) {
        super(message, cause);
        this.check = check;
    }

    /** The check that refused, or empty when this is not a check failure. */
    public Optional<Check> check() {
        return Optional.ofNullable(check);
    }
}
