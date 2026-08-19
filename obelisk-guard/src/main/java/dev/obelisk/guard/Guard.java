package dev.obelisk.guard;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares which {@link Check} a safety-check method implements.
 *
 * <p>Enforced at COMPILE TIME by {@code GuardProcessor}: every method named
 * {@code reject*}/{@code verify*} must carry one, and no two may claim the
 * same {@link Check}. Together with the enum -- whose constants javac
 * already guarantees are distinct -- that makes "no two checks share an ID"
 * a property of the build rather than something a test hopes to notice.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Guard {
    Check value();
}
