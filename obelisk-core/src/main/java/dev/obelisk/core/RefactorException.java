package dev.obelisk.core;

import dev.obelisk.guard.Check;

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
 * <p>There is deliberately NO message-only constructor. An earlier version
 * had one, on the reasoning that IO errors, resolver failures and argument
 * validation are not "safety checks refusing" and so have no identity --
 * but that is a hand-drawn boundary of exactly the kind this codebase keeps
 * getting wrong, and it left a category in which a test could not pin down
 * what had failed. Every failure now names itself, and the compiler
 * enforces it.
 */
public class RefactorException extends RuntimeException {

    private final transient Check check;

    public RefactorException(Check check, String message) {
        this(check, message, null);
    }

    public RefactorException(Check check, String message, Throwable cause) {
        super(message, cause);
        this.check = java.util.Objects.requireNonNull(check, "every RefactorException must name its Check");
    }

    /** The check that refused. Never empty -- every failure carries one. */
    public Check check() {
        return check;
    }
}
