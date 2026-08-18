# obelisk

A structural refactoring tool for Java projects. obelisk parses a project with
[JavaParser](https://github.com/javaparser/javaparser) + JavaSymbolSolver,
resolves symbols against the project's real Maven classpath, and applies
refactors (rename, extract, etc.) directly to the AST — then writes the
result back out with original formatting preserved.

Two intended uses:

1. **CLI** — a standalone replacement for IDE refactoring tools, usable on its
   own from a terminal.
2. **MCP server** *(planned, not yet implemented)* — lets an AI coding agent
   invoke a refactor as a single structured tool call instead of rewriting
   files by hand, which is both cheaper (fewer tokens) and more reliable than
   asking a model to emit a full diff of a renamed symbol across a codebase.

## Status

Implemented: `rename-method`, `rename-class`, `rename-field`,
`extract-variable`, and `inline-method`, as CLI subcommands, against Maven
projects.

Not yet implemented: any other refactor kind (extract-method, move, ...),
Gradle project support, and the MCP server itself.

See [Known limitations](#known-limitations) below before using this on
anything you care about.

## Requirements

- Java 21+
- Maven (both to build obelisk itself, and because obelisk currently shells
  out to `mvn dependency:build-classpath` on the *target* project to resolve
  its dependencies)

## Building

```sh
mvn clean package
```

This produces a runnable shaded jar at `obelisk-cli/target/obelisk.jar`.

## CLI usage

```sh
java -jar obelisk-cli/target/obelisk.jar rename-method \
  --project-dir /path/to/your/maven/project \
  --class LoginController \
  --from verifyInput \
  --to validateInput \
  --dry-run
```

| Option          | Required | Description                                              |
|-----------------|----------|------------------------------------------------------------|
| `--project-dir` | no       | Path to the Maven project root (default: current directory) |
| `--class`       | yes      | Simple name of the class/interface/enum/record declaring the method |
| `--from`        | yes      | Current method name                                       |
| `--to`          | yes      | New method name                                            |
| `--params`      | no*      | Comma-separated parameter type names (simple or fully-qualified), e.g. `String,int`. Use `""` for a zero-arg overload. *Required if `--from` is overloaded on the target class. |
| `--dry-run`      | no       | Print the unified diff without writing any files           |

If `--from` is overloaded and `--params` is omitted, obelisk refuses and
lists the available overloads' signatures so you can pick one.

Drop `--dry-run` to apply the changes to disk. obelisk prints a unified diff
per changed file either way, plus any warnings for call sites it couldn't
resolve (and therefore left untouched).

### What it renames

- the method declaration itself
- every overriding declaration found elsewhere in the project (subclasses,
  implementing classes, anonymous classes, enum constant bodies, at any
  depth, including through generic type substitution) — `--class` must name
  the *root* declaration; pointing at a method that itself overrides
  something further up is refused rather than silently renaming only part
  of the family
- every call site (`foo.bar()`, `bar()` self-calls, `this.bar()`,
  `super.bar()`) that resolves back to the target declaration or any of its
  overrides
- method references (`Foo::bar`)
- matching static imports (`import static com.example.Foo.bar;`), including
  ones that reach the method through a subclass name

### What it refuses to do (fails loudly instead of guessing)

- **Ambiguous class name**: if `--class` matches more than one *top-level*
  type in the project, obelisk stops rather than picking one. (Nested types
  with the same simple name as an unrelated top-level type — e.g. an inner
  `Config` class next to a top-level `Config` class — do *not* trigger this;
  top-level matches win.)
- **Overloaded methods**: if the method name is overloaded on the target
  class and `--params` isn't given (or doesn't uniquely match one overload),
  obelisk refuses to guess which overload you meant.
- **Invalid or colliding `--to`**: rejects non-identifiers/keywords, a name
  that would duplicate an existing method signature on the same class, and
  renaming an unqualified call into a name its enclosing class already
  declares (which would silently shadow the intended target instead of
  producing the renamed call).
- **Unparseable source**: if any file under the project's source roots fails
  to parse, obelisk reports the file and reason and stops.

### rename-class

```sh
java -jar obelisk-cli/target/obelisk.jar rename-class \
  --project-dir /path/to/your/maven/project \
  --class Formatter \
  --to TextFormatter \
  --dry-run
```

| Option          | Required | Description                                              |
|-----------------|----------|------------------------------------------------------------|
| `--project-dir` | no       | Path to the Maven project root (default: current directory) |
| `--class`       | yes      | Simple name of the class/interface/enum/record/annotation to rename |
| `--to`          | yes      | New type name                                              |
| `--dry-run`      | no       | Print the unified diff without writing any files           |

#### What it renames

- the type declaration itself, plus its own constructors (and a record's
  compact constructor, if present)
- every type usage obelisk can resolve back to it: field/local/parameter
  types, return types, generic type arguments, `extends`/`implements`
  clauses, casts, `instanceof`, `new Foo()`, and `Foo::method` references
- matching non-static and static imports (a static import of the type's own
  member gets its qualifier rewritten too)
- static-member qualifiers (`Foo.CONSTANT`, `Foo.staticMethod()`) where `Foo`
  really is the renamed type, not a same-named local variable/field
- annotation usages (`@Foo`), if the renamed type is itself an annotation
- if the target is a top-level type whose file is named after it, the file
  itself is renamed to match (this happens even under `--dry-run`'s
  underlying diff output, which reports it as `Rename file: ... -> ...`
  separately from the content diffs; the actual file move only happens
  without `--dry-run`)

#### What it refuses to do

- **Ambiguous class name**: same rule as `rename-method` (top-level matches
  win over nested types of the same simple name).
- **Invalid or colliding `--to`**: rejects non-identifiers/keywords, and a
  name that would collide with a sibling type -- another top-level type in
  the same package, or another member type in the same enclosing type.
- **Unparseable source**: same as `rename-method`.

#### Known limitations specific to rename-class

- A static import of the class itself as a nested member (e.g.
  `import static Outer.Inner;`) is not renamed.
- If the target is a top-level type but its file either isn't named after it
  or contains more than one top-level type, the file is left as-is (with a
  warning) rather than guessing which file to rename.

### rename-field

```sh
java -jar obelisk-cli/target/obelisk.jar rename-field \
  --project-dir /path/to/your/maven/project \
  --class Widget \
  --from count \
  --to amount \
  --dry-run
```

| Option          | Required | Description                                              |
|-----------------|----------|------------------------------------------------------------|
| `--project-dir` | no       | Path to the Maven project root (default: current directory) |
| `--class`       | yes      | Simple name of the class/interface/enum/record declaring the field |
| `--from`        | yes      | Current field name                                         |
| `--to`          | yes      | New field name                                              |
| `--dry-run`      | no       | Print the unified diff without writing any files           |

#### What it renames

- the field declaration itself (only that variable, if declared alongside
  others in one statement, e.g. `private int a, b;`)
- every read/write obelisk can resolve back to it: unqualified references,
  `this.field`/`super.field`, and qualified access through an instance or
  (for static fields) the class name -- including access from a subclass
  that inherits it, whether qualified or not

#### What it refuses to do

- **Ambiguous class name**: same rule as `rename-method`/`rename-class`.
- **Invalid or colliding `--to`**: rejects non-identifiers/keywords, a name
  that would duplicate another field already declared on the same class,
  and renaming an unqualified field reference into a name that a local
  variable/parameter already uses in the same method/constructor/
  initializer (which would silently shadow the field after rename).
- **Unparseable source**: same as `rename-method`.

#### Known limitations specific to rename-field

- A subclass field that *hides* (fields don't override -- hiding is
  resolved statically by declared type, not dynamically) the renamed field
  is a distinct field and is deliberately left untouched.
- Record components aren't fields in JavaParser's AST model (they're
  constructor-like parameters that happen to also generate an implicit
  field/accessor), so `rename-field` can't target one -- it fails with
  "no field found", same as pointing at any other nonexistent field.

### extract-variable

Unlike the rename refactors, `extract-variable` addresses its target by
exact source position rather than by name -- "extract this expression" is
inherently about one specific occurrence in one specific file, not a
project-wide search.

```sh
java -jar obelisk-cli/target/obelisk.jar extract-variable \
  --project-dir /path/to/your/maven/project \
  --file src/main/java/com/example/demo/Formatter.java \
  --start-line 5 --start-column 28 \
  --end-line 5 --end-column 36 \
  --name total \
  --dry-run
```

| Option           | Required | Description                                              |
|------------------|----------|------------------------------------------------------------|
| `--project-dir`  | no       | Path to the Maven project root (default: current directory) |
| `--file`         | yes      | Path to the source file (absolute, or relative to `--project-dir`) |
| `--start-line`, `--start-column` | yes | 1-based position of the expression's first character |
| `--end-line`, `--end-column`     | yes | 1-based position of the expression's last character (inclusive) |
| `--name`         | yes      | Name for the new local variable                             |
| `--dry-run`      | no       | Print the unified diff without writing any files           |

The range must EXACTLY match a single expression's boundaries (obelisk
refuses to guess "the smallest/largest expression touching this range").
This is precise but presumes the caller already knows the expression's
exact position -- a natural fit for an AI coding agent that just
read/parsed the file, less convenient for typing by hand.

#### What it does

Declares `var name = <expression>;` immediately before the statement
containing the expression, and replaces that one occurrence with a
reference to `name`.

#### What it refuses to do

- **Only one occurrence, ever**: never replaces "every identical occurrence
  in the block" -- deciding that's safe in general requires data-flow
  analysis (did anything the expression depends on change between
  occurrences?) this tool doesn't attempt.
- **Braceless bodies**: the containing statement must be a direct child of
  a `{ }` block (add braces around a braceless `if`/`while`/`for` body
  first) and must be the first thing on its own source line (so its
  indentation can be copied verbatim rather than guessed).
- **Unsuitable `var` initializers**: refuses to extract a bare `null`
  literal, a lambda, or a bare method reference -- none of those are legal
  on their own as a `var` initializer.
- **Semantics-changing hoists**: refuses to extract from the right-hand
  side of a short-circuit `&&`/`||` (may not currently evaluate at all),
  either branch of a ternary (evaluates conditionally), the body of an
  expression-bodied lambda (evaluation is deferred to invocation time), a
  `for`/`while`/`do-while` loop's own condition or update expression
  (re-evaluated every iteration, not just once), or an `assert`'s
  condition/message (conditional on failure, and on assertions being
  enabled at all -- disabled by default on the JVM) -- in each case,
  hoisting the evaluation earlier would change behavior.
- **Forward references**: refuses to extract an expression that references
  a name declared elsewhere in the same statement (a `for`-loop's own init
  variable, or an earlier declarator in a multi-variable declaration) --
  hoisting it would place the reference before its own declaration.
- **Write targets**: refuses to extract an expression that IS the
  left-hand side of an assignment or the operand of `++`/`--` -- would
  silently turn the write into a no-op.
- **Non-value expressions**: refuses an annotation, a whole variable
  declaration, or any expression whose type is `void`.
- **Invalid or colliding `--name`**: rejects non-identifiers/keywords and a
  name that collides with an existing local variable or parameter already
  in scope (checked across every enclosing method/constructor/
  initializer/lambda, not just the nearest one).
- **Argument evaluation order**: not refused, just worth knowing --
  extracting one argument out of several in a call whose OTHER arguments
  also have side effects changes their relative evaluation order (only
  relevant when multiple arguments both have side effects, already
  unusual code).

### inline-method

```sh
java -jar obelisk-cli/target/obelisk.jar inline-method \
  --project-dir /path/to/your/maven/project \
  --class Util \
  --from square \
  --dry-run
```

| Option          | Required | Description                                              |
|-----------------|----------|------------------------------------------------------------|
| `--project-dir` | no       | Path to the Maven project root (default: current directory) |
| `--class`       | yes      | Simple name of the class/interface/enum/record declaring the method |
| `--from`        | yes      | Name of the method to inline                                |
| `--params`      | no*      | Disambiguates an overloaded method, same as `rename-method`. |
| `--dry-run`      | no       | Print the unified diff without writing any files           |

#### What it does

Replaces every call site obelisk can resolve back to the method with the
method's own body (substituting parameters with that call site's actual
argument expressions), removes any now-dangling static import of it, and
removes the method declaration itself -- all-or-nothing: if any use can't
be safely inlined, the whole operation is refused rather than partially
applied. This scan only covers the single Maven module `--project-dir`
points at (its own `src/main/java`/`src/test/java`); a `public static`
method called only from a *sibling* module isn't visible to it, so
removing such a method would break that sibling's build (loudly, as a
compile error, but not something this refactor can detect up front).

#### What it refuses to do

- **Multi-statement or void methods**: only a method whose entire body is a
  single `return <expr>;` statement is supported.
- **Anything but a PURE expression over its own parameters**: no method
  calls, no lambdas or method references, no assignments or `++`/`--`
  anywhere in the body -- this is deliberately a much blunter restriction
  than a long list of individually-patched special cases. Three separate
  rounds of review each found real, silently-wrong-output bugs in a version
  of this refactor that tried to allow method calls and lambdas with
  case-by-case safety checks (an argument's evaluation reordering relative
  to the body's own side effects; a lambda in the body capturing a
  substituted argument by name instead of the thing it was bound to; a
  lambda deferring a field read to whenever it actually runs instead of the
  original call's eager, once-only evaluation) -- pure textual substitution
  has no concept of capture-avoiding substitution or of verifying
  evaluation order/timing is preserved, so the surface needing that
  reasoning at all was removed rather than kept patching new instances of
  the same two bug categories. A LATER review round found that even this
  restriction still let two implicit conversions through -- string
  concatenation's implicit `toString()` and unboxing -- since neither has
  an AST node of its own for the method-call ban to catch, so every
  operator's operands must now also be primitive-typed (see below). What
  remains inlinable: parameters, literals, and PRIMITIVE-typed operators
  over them (arithmetic, comparisons, ternaries), plus field/array reads
  through a parameter -- narrow, but free of side effects, evaluation-
  order/capture risk, and implicit-conversion risk alike. (A `switch`
  expression in the return statement is refused too, but only because
  JavaParser's resolver can't compute its type here at all, not because
  it's inherently unsafe.)
- **A non-primitive operand**: `a + b` where either side's type isn't
  primitive (e.g. `Integer`, `String`, `Object`) is refused -- confirmed
  via repro for both implicit unboxing (which can throw `NullPointerException`,
  and whose timing relative to other arguments isn't guaranteed preserved)
  and implicit `toString()`/string concatenation (whose evaluation order
  relative to other arguments isn't guaranteed preserved either) -- neither
  conversion has an AST node the method-call ban above can see. A ternary's
  own condition and both branches, and an array index, are checked the same
  way (they can trigger the identical implicit unboxing without a
  `BinaryExpr`/`UnaryExpr` node either).
- **An inaccessible field read through a parameter**: `param.value` is
  otherwise allowed, but only if `value` is `public` *and* its declaring
  type (and every type enclosing it) is also `public` -- JLS 6.6.1
  requires accessibility of both. Confirmed via repro for both halves: a
  package-private field breaks the build the same way regardless of its
  class's own visibility, and a *public* field on a *package-private*
  class breaks the build too, even when the call site never names that
  class directly (e.g. it's only obtained via a public factory method's
  return type).
- **Would become a compile-time constant expression**: refused if the
  return expression references none of the method's own parameters at all
  (so every call site would substitute to an IDENTICAL expression), or if
  a specific call's arguments are ALL compile-time constant expressions
  (JLS 15.29 -- not just literals, but also things like a unary-minus
  literal, a parenthesized constant, or a reference to a `final` variable
  whose own initializer is itself constant) -- either way the result is
  built purely from constants/primitive-operators, which Java's compiler
  treats as a genuine compile-time constant, unlike the original method
  call. Confirmed via repro to change loop/branch reachability analysis
  (`while (Flags.enabled())` compiling to an unconditional `while (true)`),
  and -- the most dangerous variant found across every round of review --
  to silently promote a `static final` field initialized from an inlined
  call into a TRUE compile-time constant: the change compiles cleanly with
  no error, but the field is now baked directly into every OTHER class's
  *compiled bytecode* instead of being read at runtime, an ABI change
  invisible in the source diff. The constant-expression check also covers
  the conditional operator `? :` (when its condition and both branches are
  all constant), and only actually examines the arguments bound to
  parameters the return expression *references* -- an argument bound to an
  unreferenced parameter can't stop the referenced ones from still promoting
  the result to a constant, so it's excluded from the check rather than
  used to (wrongly) prove the call safe.
- **A method whose declaring class has its own static-initialization
  effect**: refused if the class has a `static` initializer block, a
  `static` field whose initializer isn't itself a compile-time constant, or
  is simply an enum -- invoking a method on a class triggers that class's
  one-time `<clinit>` (JLS 12.4.1) if it hasn't already run; deleting every
  call site removes the one thing that was still guaranteeing that happens.
  Confirmed via repro: a class with a `static { ... }` logging block and a
  trivial one-line static method stopped printing that block's output
  entirely once every call to the method was inlined away, even though the
  change compiled cleanly. This is checked on the declaring type *and* every
  ancestor whose own `<clinit>` its initialization cascades into -- the
  superclass chain, plus any superinterface that declares a `default` method
  (JLS 12.4.2 step 7; an interface *without* one is never initialized on a
  subtype's account, so it's correctly still allowed). Confirmed via repro
  that checking only the declaring type's own members missed a `static`
  block inherited from a superclass entirely. An ancestor with no source
  available to inspect is refused rather than assumed inert, except
  `java.lang.Object`.
- **A lambda or method-reference argument**: refused, because a *poly
  expression*'s type comes from the context it sits in rather than from the
  expression itself (JLS 15.27.3/15.13.2) -- so the parameter-type check
  below can't catch anything wrong with it (resolving a lambda's type
  returns its target type, i.e. the parameter's own declared type, making
  that comparison pass by construction). Confirmed via repro that
  `Object o = Util.wrap(() -> ...)` inlined to `Object o = (() -> ...)`,
  reported success and exit 0, and left the project unable to compile
  ("Object is not a functional interface") -- and where the surrounding
  context is an overload set rather than a plain assignment, the same
  substitution can silently select a *different overload* instead of
  failing loudly.
- **A side-effecting argument bound to an unreferenced or duplicated
  parameter**: the side-effect-free whitelist for such an argument covers
  local variables/parameters, literals, and `this` -- explicitly *not*
  fields or enum constants, since reading either can trigger its declaring
  class/enum's own static initializer, the same hazard as the point above,
  just reached through an *argument* instead of the inlined method's own
  declaring class.
- **`synchronized` or a `throws` clause**: the monitor acquisition, or a
  call site's `catch` for a declared-but-never-actually-thrown exception,
  are both declaration-level effects this refactor's body-only analysis
  can't see -- refused rather than silently dropped.
- **Anything but `static` or `private`**: any other instance method is
  refused outright, even if nothing currently overrides it -- it's still
  subject to virtual dispatch, and a call site typed as an ancestor
  class/interface could dispatch to this exact override at runtime without
  ever showing up in this refactor's call-site scan (which only finds
  calls that themselves resolve to this exact signature). `static` and
  `private` methods are never subject to virtual dispatch at all, so
  they're the only safe case in this version.
- **Self-recursion, generics, varargs**: a method that calls itself, has
  its own type parameters, or has a varargs parameter is refused.
- **A body referencing anything but its own parameters**: a field or
  constant (by unqualified name), an explicit `this`/`super` reference, or
  any type reference at all (`new`, a cast -- including a primitive cast
  like `(int) x`, an array type/array creation, `instanceof`, `.class`, a
  generic type argument) -- all of these resolve against the method's
  *declaring* class/file today; spliced into a call site elsewhere, they'd
  resolve against the *call site's* scope, imports, or receiver instead --
  refused rather than risk silently picking up an unrelated same-named
  symbol, a different type, or rebinding `this` to the caller's own
  instance.
- **An implicit type conversion**: refused if the method's declared return
  type differs from its return expression's own type, or if a parameter's
  declared type differs from a call's actual argument type (both sides
  require an exact match, modulo unwrapping a generic-inference wildcard
  artifact first) -- substituting the bare, un-converted expression can
  silently change arithmetic results (`int` vs `double` division) or which
  overload gets picked downstream (`List.remove(int)` vs `remove(Object)`,
  reachable through either the return value or a pass-through parameter).
- **A body that writes to its own parameter**: an assignment (`a = b`) or
  `++`/`--` on a parameter is refused -- substituting the call's argument
  expression into that write position would either mutate the caller's own
  variable or produce illegal Java.
- **An unsafe receiver**: for a non-static method, any call site with an
  explicit receiver other than a bare `this` is refused -- inlining would
  rebind the body's own unqualified references to the call site's instance
  instead of the original one. For a static method, any call site qualified
  by something other than a plain class name (e.g. `expr.staticMethod()`)
  is refused -- Java still evaluates and discards that qualifier
  expression, which deleting the whole call would silently drop.
- **Evaluation-semantics-changing argument substitution**: for each
  parameter, unless it's referenced in the body exactly once,
  unconditionally and eagerly (not inside a ternary branch, a
  short-circuit `&&`/`||`, a lambda body, a nested method/anonymous-class
  body, or a `switch` expression arm), and in declaration order, its
  call-site argument must be one of a narrow set of trivially-safe shapes
  (a plain LOCAL VARIABLE/PARAMETER -- not a field, since reading a field
  can trigger that class's static initializer, confirmed via repro to
  silently drop an initializer's side effect when its field read was the
  argument for an unused parameter -- a literal, or `this`) -- otherwise
  inlining is
  refused, since it could duplicate, drop, reorder, or conditionally/
  lazily (re-)evaluate (or re-throw an exception from) an argument that the
  original call always evaluated exactly once, eagerly, in order. For a
  call with MORE THAN ONE parameter, every argument needs to be one of
  those safe shapes regardless of its own parameter's reference pattern --
  confirmed via repro that a parameter referenced exactly once, in order
  (normally exempt from this check) still let a SIBLING argument's own
  assignment land between two reads of a different, duplicated parameter,
  changing the result; a single-parameter call has no sibling argument to
  interfere with, so keeps the narrower check.
- **A call used as a bare statement, a `for`-loop's own init/update
  clause, the body of a lambda, or a discarded `switch`-statement arm**:
  `foo();` with the result discarded, `for (...; ...; foo())`, `() ->
  foo()`, or `case 1 -> foo();` inside an ordinary `switch` statement are
  all refused -- the substituted value expression generally isn't legal
  Java in any of those positions. (A `switch` *expression*'s arrow-arm
  value is *not* treated as discarded, even though it looks similar to the
  refused `switch`-statement case in the AST. A lambda body IS refused
  even in the common value-compatible shape like `.map(v -> foo(v))`,
  since reliably telling that apart from a void-compatible lambda, where
  the substituted value expression would NOT be legal, needs the lambda's
  functional-interface target type -- an earlier version tried to allow
  the value-compatible case and was confirmed via repro to break the build
  for the void-compatible one.)
- **Nested self-calls**: a call to the method inside another call to the
  same method's own arguments is refused.
- **Method references**: if the method is referenced via `Foo::method`
  anywhere, the whole operation is refused (can't be textually inlined the
  way a call site can).
- **Anything unresolvable**: unlike the other refactors' "warn and leave
  unchanged" stance, a call or method reference that name-matches but
  can't be resolved refuses the whole operation -- since the declaration
  is being deleted, silently leaving even one real (but unresolved) use
  behind would break the build with no easy way back.

## MCP server (planned)

Not built yet. The intended design: each refactor kind becomes its own MCP
tool with a typed JSON schema (rather than one generic tool with a
free-form "refactor kind" string), so an agent calls something like:

```json
{
  "tool": "rename_method",
  "arguments": {
    "projectDir": "/path/to/project",
    "class": "LoginController",
    "from": "verifyInput",
    "to": "validateInput"
  }
}
```

which maps directly onto the same `RenameMethodRefactor` used by the CLI.
The server will need to move off the CLI's current use of a fresh
`ProjectContext` per invocation and think about caching/concurrency across
requests for multiple projects — see [Known limitations](#known-limitations).

## Known limitations

- Maven only; no Gradle classpath resolution yet.
- Override propagation only goes downward from the type named in `--class`.
  If the method you point at itself overrides something further up the
  hierarchy, obelisk refuses outright (naming the root to target instead)
  rather than silently renaming only part of the family.
- Multi-module Maven projects: classpath resolution tries the target module
  in isolation first (fast, read-only). If that fails because of an
  uninstalled sibling module *and* the project is part of a detected
  reactor, obelisk automatically falls back to a reactor-aware resolution —
  it compiles the module and its upstream reactor dependencies and re-queries
  the classpath, so no manual `mvn install` step is needed. This fallback
  only triggers on failure; it never runs (and nothing gets compiled) for an
  ordinary single-module project or one where every dependency is already
  installed. Unlike the fast path, this fallback is not read-only and not
  fast — it prints a notice to stderr when it triggers, since it happens
  even under `--dry-run` (resolving symbols at all requires it).
- No handling yet for renames referenced only in comments, Javadoc
  `{@link}`/`{@code}` tags, or string literals (e.g. reflection).
- Every CLI invocation reparses the whole project from scratch — fine for
  occasional use, but something the MCP server will want to avoid for
  repeated calls against the same project.

## Project layout

```
obelisk-core/   Parsing, symbol resolution, and refactor implementations
obelisk-cli/    picocli-based CLI wrapping obelisk-core
samples/demo-project/  Fixture Maven project used to manually verify refactors
```
