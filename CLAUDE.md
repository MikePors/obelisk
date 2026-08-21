# obelisk — working notes

Structural refactoring CLI for Java, built on JavaParser + its symbol solver.
Five refactors: `rename-method`, `rename-class`, `rename-field`,
`extract-variable`, `inline-method`.

Full history and reasoning: `docs/refactoring-safety-prior-art.md`.
This file is the short version — the things that keep getting re-learned.

---

## The primary strategy is VALIDATE **AND** REPAIR

Not "validate". Not "refuse when unsure". **Transform, repair what broke,
verify it, and fail only when repair is impossible.**

This comes from the dependency-preservation work (Schäfer & de Moor); the
research notes are in `docs/` §3 and §6. The payoff is *repair* — it replaces
a refusal with a working refactoring. Validation on its own is the half that
was already there, and adding more of it does not make the tool more useful.

**This has drifted to refusal-only twice.** Score: **3 repair methods, 48
`reject*` methods.** When in doubt, that ratio is the smell.

### What repair actually is

Repair is not "add a qualifier". Qualifying a name is the **binding
instance** of a general move, and it is the instance that shipped first only
because the first two bugs that forced it (rename-field, rename-method
capture) were both binding bugs:

> A transformation discards an **implicit link** between two points in the
> program. Repair means finding explicit syntax that restores the same link,
> then re-running the analysis that would have found the link to verify it
> still points at the original target.

`docs/` §3 quotes Schäfer & de Moor saying the framework "generalises to
control flow, data flow, and synchronisation", and §6 describes it as
"'locking' references to declarations and then synthesising concrete
references". Reading that as binding-only is OUR narrowing, not theirs.

| Dependency | The implicit link | The explicit construct that restores it |
|---|---|---|
| Binding *(shipped)* | name → declaration | a qualifier (`Util.SCALE`, `this.count`) |
| Data flow, inward | a use → its defining scope | a **parameter** carrying the value in |
| Data flow, outward | a later use → a value assigned inside the excised region | a **return value**, assigned back at the call site |
| Receiver | an implicit `this` → the object whose state the method reads | an **explicit receiver** taken from a reference already in scope |
| Declared type | a variable's static type → the members invoked through it | a **narrower declared type** that still declares them |

So a parameter IS a repair, not a precondition. A receiver IS a repair, not
a different kind of thing.

### The decision rule

Repair is possible when the broken dependency can be restored **at the same
use site, from facts already determinable at that site**, without changing
what is reachable from anywhere else.

It is impossible when either:

| | Why no syntax fixes it | Examples |
|---|---|---|
| **(a)** Restoring it would mean suppressing semantics the language attaches **irreversibly to the original construct** | There is no reference form meaning "behave as if this rule does not apply here" | an override relationship; a `<clinit>` trigger; constant folding; an implicit conversion |
| **(b)** **No fact is in scope** to draw on — the site does not contain, and cannot contain without inventing new data flow, what the repair needs | Synthesising it would be invention, not restoration | no reference to the receiver anywhere in scope; encoding a non-local exit, which needs a change to the CALLER's control shape; choosing a supertype that must satisfy many sites jointly |

This predicts the answers instead of listing them. Check (a) then (b); if
neither fires, **write the repair**.

### The tell that you are drifting

You are about to add a `reject*` method whose name contains **Bound,
Shadow, Capture, Collision, Visible, Subtype**. Those are all *binding*
problems. Binding problems are the repair case. Stop and write the repair.

### Known backlog — refusals that should become repairs

**Done:** rename-field's local/parameter/pattern capture (qualify the
reference: `this.count`, or `Config.count` for a static); rename-method's
shadowed unqualified call to a STATIC target (qualify with the declaring
type's FQN); inline-method's static member references; rename-field's
shadowed-binding escape (#1 — note the direction is INVERTED from the
capture repair: there the renamed field is captured, here it is the captor,
so it is the OTHER declaration's references that get qualified);
rename-class's qualify-the-reference repair (#2 — type references,
static-member qualifiers, and dropping an import that would collide);
extract-variable's escape-the-new-local repair (#3 — and note it splices
TEXT, never the AST, so both repair and verification are textual).

**Still refusals, repairable:** none of the three on the original backlog.
The next candidates are the PoCs in #16–#18, whose repairs are data-flow
and receiver-shaped rather than binding-shaped.

**Fails (a) — genuinely NOT repairable, leave them:**
`rejectNewNameDeclaredBySubtype` and `rejectNewNameAlreadyVisible`'s override
half. You cannot qualify away an override relationship: no reference form
says "resolve statically to this one, ignore polymorphism".

**Fails (b):** rename-field's hierarchy-hiding clause — the correct qualifier
depends on where the target sits relative to the hider, so there are several
truthful answers and no single one determinable at the site.

A shadowed call to an INSTANCE method fails (b) **only when no receiver is in
scope**. That refusal was written as though the whole case were hopeless; the
restricted case, where a reference to the right object already exists at the
call site, is ordinary repair. Same for move-instance-method: PoC in #17,
and #19 spikes whether the no-receiver-in-scope half is really unrepairable.

Doing this also fixes a real problem with the verification pass: it currently
survives mutation because, with three repairs in the codebase, it has almost
nothing to guard. **Verification and repair are a pair.** Build the repair and
verification becomes load-bearing and testable.

Caveats worth respecting: a qualifier must be accessible; `super.x` does not
work for statics or through interfaces; an inner class needs `Outer.this`;
and repair edits code the user did not point at, which is a product decision.

One more, about this section itself: the binding row of the table above is
SHIPPED and reproduced. The other four rows are **argued**, from the prior
art and by analogy to the binding case — they have not been built here yet.
#16, #17 and #18 are the proofs of concept that decide whether they hold;
#19 to #22 attack the refusals. Until those land, do not cite this table as
though it were measured. Reproduce, don't reason.

---

## The recurring bug class

> A check enumerates AST node **kinds** to stand in for a Java rule that is
> actually about a **semantic property**.

Every review round has found another instance. The other half of it is that
**JavaParser reports what was written, not what the JLS implies.** These have
each caused a confirmed, reproduced bug here:

| API | Diverges how |
|---|---|
| `getAllAncestors()` | returns **empty** for a local class — use `NameBindingChecker.ancestorsOf` |
| `isStatic()` / `accessSpecifier()` | `false` / `NONE` for interface members, which are implicitly `public static final` (JLS 9.3, 9.5) |
| `instanceof ResolvedFieldDeclaration` | an **enum constant** is not one (`ResolvedEnumConstantDeclaration`) |
| `findAll(TypeDeclaration)` | misses **anonymous** and **enum-constant** class bodies |
| `findAncestor(X.class)` | walks **past** any scope that is not an `X` — a lambda is not a `CallableDeclaration`; an anonymous body is not a `TypeDeclaration` |
| `calculateResolvedType()` | reports the **target-typed** answer for poly expressions (lambdas, diamonds, generic calls), so comparing types cannot reveal a mismatch |

Fix at the level of the **property**, not the shape the reproduction showed.
Three separate fixes here were landed at the demonstrated shape and had to be
redone — anonymous classes fixed but not local ones, diamonds but not generic
methods, initializers but not returns.

---

## Working method — bulk edits

Three separate migrations here were reported as finished while incomplete
(test-assertion migration, Check-ID migration, rename-method repair). The
cause was the same each time: **treating a script's success message as
evidence the work was done.** Scripts printed "updated 8 test files" while
stale offsets put edits on the wrong tests, and "assigned 35 sites" while 15
remained.

The suite being green is not confirmation. Green proves nothing regressed;
it says nothing about *completeness*, which is exactly what those failures
were.

1. **Compile after every scripted edit, before the next one.** Chaining two
   bulk edits caused the worst instance: the first silently displaced an
   annotation, the second hit a non-matching pattern and died before
   writing, and the tree was left in a state neither intended and not
   compiling. An `assert` firing mid-script does NOT mean nothing changed —
   earlier scripts have already written.
2. **Report completeness as a measured count, not a script's echo.** Grep
   for what should be zero and show the number.
3. **Prefer an invariant that makes the partial state fail the build.**
   Removing `RefactorException(String)` worked because the compiler then
   counted the unconverted sites instead of me. Do that where possible; it
   turns "did I get them all?" from a judgement into a build error.
4. **Drive bulk edits off ground truth, not a regex over source.** javac's
   own error locations are reliable; a regex over method signatures silently
   matched nothing and assigned zero sites.

### Verification-by-resolution only works within one compilation unit

`JavaParserTypeSolver` reads types **from disk**. During a refactor nothing
has been written yet, so resolving anything in another file gets the
PRE-mutation source. A repair whose target lives in the same file
(`this.count` in rename-field) can be verified end to end by resolving the
rewritten node; a repair pointing at another file (`Util.report(x)` in
rename-method) cannot, and no amount of qualifier fiddling changes that.
Verify structurally against the in-memory AST in that case, and say so.

## Verification habits

- **Reproduce, don't reason.** Every finding worth acting on was confirmed by
  building a fixture and running it. Several confident chains of reasoning
  turned out wrong — including one that removed a check as "dead code" for a
  reason that was itself incorrect.
- **`tools/mutation-check.sh`** disables each `reject*`/`verify*` call and
  checks whether any test notices. Run it after touching a check. It only
  measures *named methods* — a refusal written as an inline `throw` is
  invisible to it, and such refusals turned out to be systematically
  untested. Baseline: **killed=47 subsumed=11 survived=0 unmeasurable=0**
  of 58 targets.
- **`tools/repair-mutation-check.sh`** does the same job from the other
  side, for the half the first script structurally cannot reach. A `verify*`
  method fires only when a repair produced something wrong, so with the
  repair working it is unreachable and disabling it proves nothing — it just
  sits on the allowlist as a BACKSTOP. So instead of removing the verifier,
  this **breaks the repair the verifier guards** and requires the verifier to
  notice: a failing test whose stack trace names it. Going red is not enough,
  since a broken repair can equally well emit code that will not compile.
  Corruptions live in `tools/repair-mutations.txt`, one per repair.
  **Every verifier written so far has failed its first run of this**, all
  six for the same reason: each validated the input it was HANDED rather
  than the result that was produced. If you are writing a `verify*`, assume
  you have made that mistake and go looking for it.

  It is worth knowing what its first run found, because "unverifiable"
  had been accepted for both: **two of the three verifiers were checking
  something other than what they claimed.** `verifyQualifiedCalls` compared
  the qualifier it was HANDED, never the one the repair emitted — verifying
  the plan, not the result. `verifyRepairedReferences` proved a repaired
  reference bound to the right declaration without checking the reference
  was *legal*, so a type-qualified reference to an instance field passed and
  the project stopped compiling. Both are the recurring bug class, inside
  the verification pass itself.
- **Every check has an identity.** `Check` (in `obelisk-guard`) is an enum,
  so javac guarantees the IDs are distinct. `@Guard(Check.X)` on each
  `reject*`/`verify*` method binds one to it, and `GuardProcessor` fails the
  BUILD if two methods claim the same constant or if a check declares none.
  `RefactorException` carries it.
- **Assert on the ID and the message.** `expectRefused(Check.X, action)`
  pins which check refused; `hasMessageContaining` then pins that its
  message describes the right hazard. The ID is the contract, the message is
  presentation — reword messages freely.
  `MessageDistinctivenessTest` additionally forbids asserting a phrase that
  more than one check's message contains.
  Tests here passed for the wrong reason three times before this existed.
  **Every** `RefactorException` carries a `Check` — there is no message-only
  constructor, so skipping one is a compile error, not a judgement call. All
  91 refusal call sites assert an ID. An earlier version exempted "lookup and
  validation" failures by hand; that boundary was removed, because a
  hand-drawn exemption is exactly the shortcut that has burned this codebase
  before.
- **`TestProject.rangeOf(file, snippet)`** locates expressions by text for
  extract-variable tests. Hand-counted columns have been wrong three times.
- Fixtures deliberately write **no `pom.xml`** — that keeps them hermetic and
  fast (no `mvn` subprocess).

## Repo hygiene

- Never run `git checkout`/`reset`/`clean` reflexively. This has cost work
  TWICE here: once as `git checkout -- .` appended to a build command, once
  as `git checkout -- <file>` used to undo a temporary debug probe, which
  also discarded four unrelated uncommitted edits to that file. **To remove
  a probe, restore from a `cp` backup taken before adding it** — never from
  git, which cannot tell your probe from your work. Check `git status`
  first; stash if anything is there.
- `tools/mutation-check.sh` and `tools/repair-mutation-check.sh` edit
  sources in place. Both restore on `EXIT INT TERM`, but never run either
  against a tree with uncommitted work you cannot afford to re-do, and never
  two at once against the same checkout. An interrupted repair-mutation run
  is the worse of the two: it leaves a silently WRONG refactoring engine that
  still compiles, rather than a broken build.
