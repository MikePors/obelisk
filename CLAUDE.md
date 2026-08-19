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

**This has drifted to refusal-only twice.** Score: **2 repair methods, 48
`reject*` methods.** When in doubt, that ratio is the smell.

### The decision rule

When you find a hazard, ask **what kind of hazard is it?**

| The hazard is… | Then… |
|---|---|
| A **name or reference would bind somewhere else** after the transformation | **REPAIR IT.** Synthesise a qualified reference (`Util.SCALE`, `super.count`, `Outer.this.x`), then verify it still binds to the original declaration. |
| Something the **compiler does differently** — `<clinit>` timing, constant-expression promotion, evaluation order, statement-position legality, `var` inference, an implicit conversion | **Refuse.** Repair synthesises references; it cannot restore a dropped class-initialization trigger or un-promote a constant. |

### The tell that you are drifting

You are about to add a `reject*` method whose name contains **Bound,
Shadow, Capture, Collision, Visible, Subtype**. Those are all *binding*
problems. Binding problems are the repair case. Stop and write the repair.

### Known backlog — refusals that should become repairs

**Done:** rename-field's local/parameter/pattern capture — the reference is
qualified (`this.count`, or `Config.count` for a static) instead of refused.

**Still refusals, repairable:** `rejectNewNameAlreadyBound`, rename-method's
`rejectShadowingCollision` (qualify the call), and
`rejectNewNameAlreadyBoundAtReference` (qualify the type reference), plus
extract-variable's `rejectNameCollision`.

**Genuinely NOT repairable, leave them:** `rejectNewNameDeclaredBySubtype`
and `rejectNewNameAlreadyVisible`'s override half — you cannot qualify away
an override relationship. rename-field's hierarchy-hiding clause is also out:
the correct qualifier depends on where the target sits relative to the hider,
so there is no single safe rewrite.

Doing this also fixes a real problem with the verification pass: it currently
survives mutation because, with one repair in the codebase, it has almost
nothing to guard. **Verification and repair are a pair.** Build the repair and
verification becomes load-bearing and testable.

Caveats worth respecting: a qualifier must be accessible; `super.x` does not
work for statics or through interfaces; an inner class needs `Outer.this`;
and repair edits code the user did not point at, which is a product decision.

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

## Verification habits

- **Reproduce, don't reason.** Every finding worth acting on was confirmed by
  building a fixture and running it. Several confident chains of reasoning
  turned out wrong — including one that removed a check as "dead code" for a
  reason that was itself incorrect.
- **`tools/mutation-check.sh`** disables each `reject*`/`verify*` call and
  checks whether any test notices. Run it after touching a check. It only
  measures *named methods* — a refusal written as an inline `throw` is
  invisible to it, and such refusals turned out to be systematically
  untested.
- **Tests here pass for the wrong reason often** — three times, each costing
  a review round or a mutation run to notice. `MessageDistinctivenessTest`
  now enforces the fix: every phrase a refusal test asserts must appear in
  exactly ONE check's message. Assert on a clause from the half of the
  message that explains WHY, not a two-word fragment of what.
  A caveat worth keeping: ~68 of 92 refusal assertions still only pin down
  the message's *subject* (a class or field name from the fixture) rather
  than which check fired. Not wrong, but not identifying either — strengthen
  them opportunistically when touching a test.
- **`TestProject.rangeOf(file, snippet)`** locates expressions by text for
  extract-variable tests. Hand-counted columns have been wrong three times.
- Fixtures deliberately write **no `pom.xml`** — that keeps them hermetic and
  fast (no `mvn` subprocess).

## Repo hygiene

- Never run `git checkout`/`reset`/`clean` reflexively. One careless
  `git checkout -- .` appended to a build command discarded uncommitted work
  in this repo. Check `git status` first; stash if anything is there.
- `tools/mutation-check.sh` edits sources in place. It restores on
  `EXIT INT TERM`, but never run it against a tree with uncommitted work you
  cannot afford to re-do, and never from a second checkout concurrently.
