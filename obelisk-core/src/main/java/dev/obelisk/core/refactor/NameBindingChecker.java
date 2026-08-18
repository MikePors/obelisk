package dev.obelisk.core.refactor;

import com.github.javaparser.ast.Node;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedEnumConstantDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.symbolsolver.resolution.SymbolSolver;

import java.util.Optional;

/**
 * Answers one question, shared by every rename-shaped refactor: <b>at this
 * point in the code, what does {@code name} already bind to?</b>
 *
 * <p>This exists because a review of the four pre-{@code inline-method}
 * refactors found that seven of its twelve findings were the same bug in
 * different clothes: <b>each refactor checked the collision surface of the
 * OLD name, and none checked what the NEW name already binds to at each
 * affected site.</b> Renaming {@code Child.total} to {@code count} when
 * {@code Base.count} exists compiles perfectly and silently changes what
 * every bare {@code count} in that class refers to; the same shape recurs
 * for methods (an existing overload, or an accidental override), for types
 * (a single-type import outranking a same-package type), and for a variable
 * introduced by extract-variable shadowing a field.
 *
 * <p>Checking a declaration's own siblings -- which is what those refactors
 * did -- is a syntactic stand-in for the semantic property that actually
 * matters, which is scope-sensitive and differs per reference site. So this
 * asks the symbol solver directly, at the site, using the same resolution
 * machinery the compiler-shaped parts of this codebase already rely on.
 *
 * <p><b>Polarity note.</b> "Not solved" is treated as "nothing binds here",
 * i.e. safe to proceed. That is the correct default rather than a hazard:
 * for essentially every rename the new name binds to nothing anywhere, so
 * treating unresolvable as "might bind" would refuse every rename and make
 * the tool useless. This is the opposite polarity from
 * {@code InlineMethodRefactor}'s constant-expression check, where unknown
 * must mean "possibly constant" -- and the reason that file now passes the
 * unknown-answer in explicitly rather than baking one in.
 */
final class NameBindingChecker {

    private NameBindingChecker() {
    }

    /**
     * What VALUE (local, parameter, field -- including an inherited one --
     * enum constant, or static-imported field) does {@code name} already
     * bind to at {@code site}? Empty if nothing does.
     */
    static Optional<String> valueBindingAt(TypeSolver typeSolver, Node site, String name) {
        try {
            SymbolReference<?> reference = new SymbolSolver(typeSolver).solveSymbol(name, site);
            if (!reference.isSolved()) {
                return Optional.empty();
            }
            return Optional.of(describe(reference.getCorrespondingDeclaration()));
        } catch (RuntimeException e) {
            // The solver throws rather than returning "unsolved" for some
            // shapes; both mean the same thing here -- it could not find
            // anything under that name.
            return Optional.empty();
        }
    }

    /**
     * What TYPE (a sibling type, an imported one, a same-package one, a
     * {@code java.lang} one, or an enclosing TYPE PARAMETER) does {@code
     * name} already bind to at {@code site}? Empty if nothing does.
     */
    static Optional<String> typeBindingAt(TypeSolver typeSolver, Node site, String name) {
        try {
            SymbolReference<? extends ResolvedTypeDeclaration> reference =
                    new SymbolSolver(typeSolver).solveType(name, site);
            if (!reference.isSolved()) {
                return Optional.empty();
            }
            return Optional.of(describe(reference.getCorrespondingDeclaration()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Is any method named {@code name} visible on {@code type} -- declared
     * on it, or inherited from any ancestor?
     *
     * <p>Deliberately ignores parameter types rather than modelling JLS
     * 15.12.2 applicability. Renaming {@code foo(Object)} to {@code bar}
     * when {@code bar(String)} exists was confirmed by repro to silently
     * redirect {@code a.foo("hello")} to a completely different method
     * body, so "a different signature is just a legal new overload" is not
     * a safe assumption. Modelling applicability properly would mean
     * reimplementing overload resolution; refusing on a bare name match is
     * over-cautious in the direction this codebase always chooses, since an
     * over-refusal is a nuisance and an under-refusal is silent breakage.
     */
    static Optional<String> visibleMethodOn(ResolvedReferenceTypeDeclaration type, String name,
                                             String excludeQualifiedSignature) {
        try {
            for (var declared : type.getDeclaredMethods()) {
                if (declared.getName().equals(name)
                        && !declared.getQualifiedSignature().equals(excludeQualifiedSignature)) {
                    return Optional.of("a method '" + declared.getQualifiedSignature() + "'");
                }
            }
            for (var ancestor : type.getAllAncestors()) {
                var declaration = ancestor.getTypeDeclaration().orElse(null);
                if (declaration == null) {
                    continue;
                }
                for (var inherited : declaration.getDeclaredMethods()) {
                    if (inherited.getName().equals(name)
                            && !inherited.getQualifiedSignature().equals(excludeQualifiedSignature)) {
                        return Optional.of("an inherited method '" + inherited.getQualifiedSignature() + "'");
                    }
                }
            }
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** Human-readable description of what a name was found to bind to, for error messages. */
    private static String describe(ResolvedDeclaration declaration) {
        if (declaration instanceof ResolvedFieldDeclaration field) {
            String owner;
            try {
                owner = field.declaringType().getQualifiedName();
            } catch (RuntimeException e) {
                owner = "an unknown type";
            }
            return "a field '" + field.getName() + "' declared on " + owner;
        }
        if (declaration instanceof ResolvedEnumConstantDeclaration enumConstant) {
            return "the enum constant '" + enumConstant.getName() + "'";
        }
        if (declaration instanceof ResolvedParameterDeclaration parameter) {
            return "the parameter '" + parameter.getName() + "'";
        }
        if (declaration instanceof ResolvedTypeParameterDeclaration typeParameter) {
            return "the type parameter '" + typeParameter.getName() + "'";
        }
        if (declaration instanceof ResolvedTypeDeclaration type) {
            try {
                return "the type '" + type.getQualifiedName() + "'";
            } catch (RuntimeException e) {
                return "a type named '" + type.getName() + "'";
            }
        }
        return "a local variable '" + declaration.getName() + "'";
    }
}
