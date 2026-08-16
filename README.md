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

Implemented: `rename-method`, as a CLI subcommand, against Maven projects.

Not yet implemented: any other refactor kind (extract-method, extract-variable,
move, inline, ...), Gradle project support, and the MCP server itself.

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
| `--dry-run`      | no       | Print the unified diff without writing any files           |

Drop `--dry-run` to apply the changes to disk. obelisk prints a unified diff
per changed file either way, plus any warnings for call sites it couldn't
resolve (and therefore left untouched).

### What it renames

- the method declaration itself
- every overriding declaration found elsewhere in the project (subclasses,
  implementing classes, at any depth) — `--class` must name the *root*
  declaration (the interface/superclass method); renaming from a subclass's
  override does not walk back up to rename the interface method or sibling
  overrides in other subclasses
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
  class, obelisk refuses to guess which overload you meant. There is no
  parameter-list disambiguation yet.
- **Invalid or colliding `--to`**: rejects non-identifiers/keywords, a name
  that would duplicate an existing method signature on the same class, and
  renaming an unqualified call into a name its enclosing class already
  declares (which would silently shadow the intended target instead of
  producing the renamed call).
- **Unparseable source**: if any file under the project's source roots fails
  to parse, obelisk reports the file and reason and stops.

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
  Renaming a subclass's override doesn't walk back up to also rename the
  interface/superclass method or sibling overrides in other subclasses —
  point `--class` at the root declaration to rename the whole family.
- Multi-module Maven projects: classpath resolution runs against a single
  module in isolation (not reactor-aware). If a sibling module dependency
  hasn't been `mvn install`ed to the local repo, resolution fails outright
  with a clear error rather than silently degrading. Install sibling modules
  first (`mvn install -DskipTests` from the reactor root) before running
  obelisk against a module that depends on them.
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
