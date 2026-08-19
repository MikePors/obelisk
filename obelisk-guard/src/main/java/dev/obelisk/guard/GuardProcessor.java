package dev.obelisk.guard;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time enforcement of check identity.
 *
 * <p>Two rules, both errors rather than warnings:
 * <ol>
 * <li><b>No two check methods claim the same {@link Check}.</b> The enum
 *     already makes the IDs themselves distinct; this stops two different
 *     checks pointing at one ID, which would put the wrong-reason-test
 *     problem straight back.
 * <li><b>Every {@code reject*}/{@code verify*} method declares a
 *     {@link Guard}.</b> Without this the guarantee erodes silently as new
 *     checks are added -- which is exactly how the original problem arose.
 * </ol>
 *
 * <p>Rule 2 keys off the naming convention deliberately. That convention is
 * already load-bearing elsewhere ({@code tools/mutation-check.sh} treats
 * {@code reject*}/{@code verify*} as the set of checks it must be able to
 * disable), so tying compile-time enforcement to it keeps one rule rather
 * than two that can drift apart.
 */
// "*": a processor is only invoked when one of its supported annotations
// is actually present, so keying on @Guard would mean the "every check is
// annotated" rule never runs on a file that has no annotations yet --
// precisely the file it needs to police.
@SupportedAnnotationTypes("*")
public final class GuardProcessor extends AbstractProcessor {

    private final Map<String, String> claimedBy = new HashMap<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
        for (Element element : env.getElementsAnnotatedWith(Guard.class)) {
            ExecutableElement method = (ExecutableElement) element;
            String id = method.getAnnotation(Guard.class).value().name();
            String here = method.getEnclosingElement().getSimpleName() + "." + method.getSimpleName();
            String previous = claimedBy.putIfAbsent(id, here);
            if (previous != null && !previous.equals(here)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Check." + id + " is already claimed by " + previous
                                + ". Every check needs its own identity -- sharing one reintroduces the "
                                + "ambiguity that identities exist to remove.", method);
            }
        }

        for (Element root : env.getRootElements()) {
            for (Element enclosed : root.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.METHOD) {
                    continue;
                }
                String name = enclosed.getSimpleName().toString();
                boolean isCheck = name.startsWith("reject") || name.startsWith("verify");
                if (isCheck && enclosed.getAnnotation(Guard.class) == null) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            name + " looks like a safety check but declares no @Guard. Give it a Check "
                                    + "constant, or rename it if it does not refuse anything -- a predicate that "
                                    + "reports a fact is not a check.", enclosed);
                }
            }
        }
        return false;
    }
}
