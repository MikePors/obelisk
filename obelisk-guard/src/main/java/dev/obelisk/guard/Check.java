package dev.obelisk.guard;

/**
 * The identity of every safety check in obelisk.
 *
 * <p>A check's identity used to exist nowhere in the code: it was implied by
 * the prose of its error message, and tests asserted on fragments of that
 * prose. Three separate tests were found passing for the wrong reason
 * because two checks' messages happened to share a phrase, and each took a
 * review round or a mutation run to notice.
 *
 * <p>An enum is the identity because <b>javac itself guarantees the
 * constants are distinct</b> — two checks cannot share an ID, at compile
 * time, with no tooling required. {@link Guard} and its annotation
 * processor add the other half of that guarantee: that no two check METHODS
 * claim the same constant, and that every check declares one.
 *
 * <p>EVERY {@code RefactorException} carries one. There is deliberately no
 * message-only constructor, so "this failure isn't really a check" is not a
 * shortcut anyone can take -- an earlier version drew that line by hand,
 * exempting lookup and validation failures, and the line turned out to be
 * exactly the kind of hand-drawn boundary that has been wrong repeatedly in
 * this codebase. Completeness is now a compile-time property.
 *
 * <p>The constant name is the contract. Renaming one is a breaking change
 * for anything asserting on it. Grouping is for readers only.
 */
public enum Check {
    REJECT_ALL_LITERAL_ARGUMENTS_CONSTANT_PROMOTION,
    REJECT_ASSERT_POSITION,
    REJECT_COMPOUND_ASSIGNMENT_REORDERING,
    REJECT_CONSTANT_EXPRESSION_PROMOTION,
    REJECT_DECLARING_TYPE_STATIC_INITIALIZATION_EFFECT,
    REJECT_DEFERRED_EVALUATION_CONSTRUCTS,
    REJECT_DUPLICATE_FIELD_NAME,
    REJECT_DUPLICATE_SIGNATURE,
    REJECT_DUPLICATE_TYPE_NAME,
    REJECT_EXISTING_TARGET_FILE,
    REJECT_FORWARD_REFERENCE,
    REJECT_FREE_REFERENCES,
    REJECT_HIERARCHY_HIDING,
    REJECT_HOIST_ACROSS_TYPE_BOUNDARY,
    REJECT_HOIST_OUT_OF_RESOURCE_SPECIFICATION,
    REJECT_INACCESSIBLE_FIELD,
    REJECT_LVALUE_POSITION,
    REJECT_METHOD_REFERENCE_USE,
    REJECT_MUTATING_OR_CALL_EXPRESSIONS,
    REJECT_NAME_COLLISION,
    REJECT_NESTED_SELF_CALL,
    REJECT_NEW_NAME_ALREADY_BOUND,
    REJECT_NEW_NAME_ALREADY_BOUND_AT_REFERENCE,
    REJECT_NEW_NAME_ALREADY_VISIBLE,
    REJECT_NEW_NAME_DECLARED_BY_SUBTYPE,
    REJECT_NON_PRIMITIVE_OPERANDS,
    REJECT_NON_ROOT_TARGET,
    REJECT_NON_VALUE_EXPRESSION,
    REJECT_PARAMETER_TYPE_CONVERSION,
    REJECT_PARAMETER_WRITES,
    REJECT_POLY_EXPRESSION_ARGUMENT,
    REJECT_RECURRING_CONTROL_POSITION,
    REJECT_RETURN_TYPE_CONVERSION,
    REJECT_SELF_RECURSION,
    REJECT_SHADOWING_COLLISION,
    REJECT_STATEMENT_NOT_AT_LINE_START,
    REJECT_STATEMENT_POSITION,
    REJECT_STATIC_INITIALIZATION_EFFECT_ON,
    REJECT_SYNCHRONIZED,
    REJECT_TARGET_TYPED_INITIALIZER,
    REJECT_THROWS_CLAUSE,
    REJECT_UNANCHORABLE_STATEMENT,
    REJECT_UNHOISTABLE_POSITION,
    REJECT_UNSAFE_ARGUMENT_SUBSTITUTION,
    REJECT_UNSAFE_RECEIVER,
    REJECT_UNSUITABLE_INITIALIZER,
    REJECT_UNSUPPORTED_SHAPE,
    REJECT_VIRTUAL_DISPATCH_RISK,
    VERIFY_BINDINGS_PRESERVED,
    VERIFY_QUALIFIED_CALLS,
    VERIFY_EVERYTHING_STILL_RESOLVES,
    VERIFY_REPAIRED_REFERENCES,
    VERIFY_REPAIRS_BIND_TO_ORIGINAL_DECLARATION,

    // --- Target lookup: the thing you named could not be identified ---
    CLASS_NOT_FOUND,
    CLASS_NAME_AMBIGUOUS,
    METHOD_NOT_FOUND,
    METHOD_NAME_OVERLOADED,
    NO_OVERLOAD_MATCHES_PARAMS,
    PARAMS_FILTER_AMBIGUOUS,
    FIELD_NOT_FOUND,
    EXPRESSION_NOT_FOUND,
    EXPRESSION_NOT_IN_A_STATEMENT,
    TARGET_FIELD_NOT_A_FIELD,

    // --- Argument validation: what you asked for is malformed ---
    INVALID_IDENTIFIER,
    INVALID_PARAMS_FILTER,
    FILE_NOT_IN_PROJECT,

    // --- Resolution: the symbol solver could not answer ---
    TARGET_METHOD_UNRESOLVABLE,
    TARGET_FIELD_UNRESOLVABLE,
    TARGET_TYPE_UNRESOLVABLE,
    CALL_UNRESOLVABLE,
    METHOD_REFERENCE_UNRESOLVABLE,
    CALL_TYPE_UNRESOLVABLE,

    // --- Environment: something outside the source tree failed ---
    CLASSPATH_RESOLUTION_FAILED,
    CLASSPATH_OUTPUT_UNREADABLE,
    CLASSPATH_PROCESS_FAILED,
    CLASSPATH_PROCESS_INTERRUPTED,
    CLASSPATH_DIRECTORY_UNREADABLE,
    NO_SOURCE_ROOTS,
    SOURCE_FILE_UNPARSEABLE,
    WRITE_FAILED,

    // --- Internal invariants: a bug in obelisk, not in the input ---
    INTERNAL_ERROR,
}
