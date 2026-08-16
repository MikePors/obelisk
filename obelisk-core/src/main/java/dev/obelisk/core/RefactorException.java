package dev.obelisk.core;

/** Raised whenever obelisk cannot safely carry out a refactor and must fail loudly. */
public class RefactorException extends RuntimeException {

    public RefactorException(String message) {
        super(message);
    }

    public RefactorException(String message, Throwable cause) {
        super(message, cause);
    }
}
