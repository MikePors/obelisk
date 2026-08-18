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

Implemented: `rename-method`, `rename-class`, `rename-field`, and
`extract-variable`, as CLI subcommands, against Maven projects.

Not yet implemented: any other refactor kind (extract-method, move, inline,
...), Gradle project support, and the MCP server itself.

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
