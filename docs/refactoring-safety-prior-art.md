# Refactoring safety: what the prior art says

Research notes written after `inline-method` went through eight rounds of
independent review, each of which found genuinely new categories of
silently-wrong-output bugs. The question this set out to answer: is that
difficulty intrinsic, is it self-inflicted, and does anyone already know how
to do better?

Short answer: **intrinsic, measured, and yes** — there is a named
architectural alternative to what obelisk currently does.

## 1. The difficulty is a measured phenomenon, not our inexperience

The most directly relevant source is [Towards Understanding Refactoring
Engine Bugs][tosem] (TOSEM 2025), the first systematic study of refactoring
engine bugs, covering **518 manually-labelled bugs** across Eclipse JDT,
IntelliJ IDEA, and NetBeans. Its findings line up with this project's
experience point for point:

| Their finding | Our experience |
|---|---|
| **"Inline" is the #2 most bug-prone refactoring type** (88 bugs, 16.99%), behind only "Extract" | `inline-method` needed 8 review rounds; the four prior refactors needed far fewer |
| **Lambda expressions are the #1 most bug-prone language feature** (39 bugs, 14.5%), then generics, then **Enum (#3)** | Round 8 finding #3 was lambdas; round 7 #4 and round 8 #2 were both enums |
| **"Overly weak preconditions" (54) outnumber "overly strong" (8) ~7:1** | Our deliberate bias toward over-refusing is empirically the right side to err on |
| **"Behavior Change" (66) is the hardest symptom to detect** — no syntax error, tests frequently miss it | Precisely the class our review rounds kept surfacing |
| "Incorrect Preconditions Checking" (62) is the #3 root cause; *"preconditions need to be updated considering the introduction of new language features"* | Rounds 5–8 were largely newer-language-feature semantics |

The paper's own framing of why this is hard:

> Since specifying preconditions is a nontrivial task, developers may be
> unaware of the preconditions needed to guarantee behavioral preservation
> or syntax correctness.

## 2. Three of our checks correspond to real shipped bugs in mainstream tools

Not hypotheticals — filed, confirmed bugs in tools with far more engineering
behind them than this one:

- **[Eclipse JDT #1360][eclipse1360]** — inline method **dropped the
  `synchronized` modifier**, silently changing behaviour. This is obelisk's
  `rejectSynchronized`, verbatim. The paper's diagnosis: *"engine developers
  ignored the case where methods own a `synchronized` modifier when
  rewriting AST."*
- **[IDEA-127135][idea127135]** — inlining a getter used as a bare statement
  generated `someString;`, an invalid statement. This is obelisk's
  `rejectStatementPosition`.
- **[IDEA-314882][idea314882]** — inline method **adds unnecessary
  parentheses** when inlining string concatenation. This is obelisk's
  conservative always-parenthesize rule. Worth noting mainstream tools get
  bugs *filed against them* for this tradeoff; ours is a deliberate, known
  cost, and we're in good company.

## 3. The structural diagnosis: preconditions are the wrong tool

This is the finding that matters most.

Max Schäfer and Oege de Moor argue directly that the precondition-based
architecture — the one obelisk currently uses, and the one every mainstream
engine uses — is the wrong shape for this problem:

> While preconditions are valuable for specifying shallow conditions that
> must hold in order for a refactoring to make sense, they are not the right
> tool to ensure behavior preservation in the face of issues related to name
> capture or control and data flow preservation. Such problems are much
> easier to tackle if they are expressed as dependency preservation problems.

Their alternative, developed across [Sound and Extensible Renaming for
Java][renaming] (OOPSLA 2008), [Stepping Stones over the Refactoring
Rubicon][stepping] (ECOOP 2009), [Specifying and Implementing
Refactorings][specifying] (OOPSLA 2010), and Schäfer's [thesis][thesis], is
**dependency preservation**:

1. Treat the binding from a name to the declaration it refers to as a
   **dependency the refactoring must preserve**. The same framework
   generalises to control flow, data flow, and synchronisation.
2. **Perform the transformation first.** Do not attempt to enumerate, ahead
   of time, everything that could go wrong.
3. **Afterwards, check that every tracked dependency still holds.** Repair
   (by synthesising qualified references) or abort if not.
4. Decompose complex refactorings into **microrefactorings** that can be
   specified, implemented, and verified in isolation.

The name-binding core of this was mechanically verified in Coq.

### Why this matters for obelisk specifically

A post-transformation re-resolve-and-compare would have caught, *without
anyone anticipating them individually*:

- the poly-expression/lambda bug (round 8 #3) — the lambda's target type
  changes, so its resolved type changes
- the boxing and overload-selection bugs (rounds 2, 5) — the resolved
  invocation target changes
- the inaccessible-field bug (rounds 5, 6) — resolution outright fails at
  the new location
- the name-capture and free-reference bugs (rounds 1–3) — names bind to
  different declarations

That is the difference between ~45 hand-derived checks, each discovered by a
review round finding a fresh way to be wrong, and **one invariant that holds
by construction**.

### What it would *not* catch

Dependency preservation checks that *bindings* are unchanged. It cannot see
hazards where the bindings are identical but the compiler's treatment of
them differs:

- **`<clinit>` dropping** (rounds 7–8) — deleting the last call site removes
  the class-initialization trigger; no binding changed
- **constant-expression promotion** (rounds 5–8) — the substituted result
  becomes a JLS 15.29 constant expression; no binding changed
- **`synchronized` / `throws`** — declaration-level effects, not bindings

So the semantic checks stay. Dependency preservation replaces the *binding*
half of the safety argument, not the whole of it.

## 4. Two of our hazards appear under-documented in the tooling literature

Searching specifically for the class-initialization and constant-expression
hazards **in a refactoring context** turned up only language-level material
— [JLS Chapter 13 (Binary Compatibility)][jls13] and [SEI CERT
DCL59-J][dcl59] — and nothing about refactoring tools treating either as a
precondition.

The constant-folding ABI hazard is well known *as a binary-compatibility
concern*: JLS 13 permits a compiler to inline a constant variable's value
into every reading class, so changing it later does not affect already-
compiled code. CERT has a rule against the pattern. But the inverse — that
*inlining a method call can promote a runtime read into a compile-time
constant*, silently creating that hazard where none existed — does not
appear to be documented as a refactoring precondition anywhere found.

**Caveat:** absence from a search is not proof of novelty. IntelliJ and
Eclipse may well check these without it being written up, and the search was
not exhaustive.

## 5. An instructive contrast

[Refactoring = Substitution + Rewriting][substitution] (Thompson &
Horpácsi) formalises inline-style refactorings cleanly as substitution
followed by beta-reduction — but in **Erlang**. Substitution is safe there
precisely because the language is pure: no evaluation-order hazards, no
implicit conversions, no class initialization, no side effects.

Every category of bug this project hit lives in exactly the gap between that
clean model and Java's impurity. The mess is Java's, not ours — but it does
mean the elegant formulation is not available to us, and something like
obelisk's restriction set (or dependency tracking) has to stand in for it.

Related: [Steimann & Thies, *From Public to Private to Absent*][steimann]
(ECOOP 2009) treats accessibility as a constraint system, which is the
principled version of obelisk's ad-hoc JLS 6.6.1 checks in
`rejectInaccessibleField`.

## 6. What we're doing about it

### 6.1 First attempt, and what it got wrong

The initial response was to add **one post-transformation verification
pass**: after building the substituted AST but before writing anything,
re-resolve the transformed region and assert every name, type, and
invocation target still binds as it did.

That was a **misreading of the research**, and it is worth recording why.

Dependency preservation is not "add a checker underneath your existing
preconditions." It is a three-step *replacement* architecture:

1. Perform the naive transformation.
2. Check the tracked dependencies still hold.
3. **Repair the ones that don't** — Schäfer and de Moor track bindings by
   *"'locking' references to declarations and then **synthesising concrete
   references**"* — and fail only when repair is impossible.

Step 3 is where the value is. Synthesis is what turns "refuse when risky"
into "transform, then fix it up," which *enables* refactorings rather than
merely re-checking them.

The first implementation did steps 1 and 2 and made step 3 `throw`, then
stacked the result beneath maximally conservative preconditions that had
already refused everything it could speak to. Under those conditions it is
structurally guaranteed to be redundant — the checking half of an
architecture whose payoff lives in the repair half.

### 6.2 Measured, not assumed

A stress test lifted the two lambda bans to find out what the verification
pass could actually recover. Results:

- **Lambda as argument** — the pass fails *closed* on every lambda, safe
  context or not, because JavaParser cannot resolve a lambda's type in any
  position. It refused the unsafe case and both safe cases for the same
  degenerate reason. Zero valid cases recovered.
- **Call as lambda body** — the pass fails *open*. A void-compatible lambda
  body (`IntConsumer c = v -> Util.twice(v)`) has identical bindings and
  identical types before and after; only **statement-position legality**
  changes. The pass allowed it and the build broke.

An earlier version of this document, and of the code's Javadoc, claimed the
pass "independently caught" the poly-expression bug when that precondition
was disabled. That was an over-claim — it was failing closed
indiscriminately, not recognising a hazard. Corrected here for the record.

### 6.3 Doing it properly: repair

The concrete payoff is `rejectFreeReferences`, which is probably the single
largest source of over-refusal — it bans a body referencing *any* field,
constant, unqualified sibling call, or type name, which rules out most
ordinary utility methods.

Unqualified `static` field reads are the textbook repair case:

```java
static final int SCALE = 2;
static int scaled(int x) { return SCALE * x; }   // was: refused outright
                                                  // now: inlines as Util.SCALE * x
```

The reference is re-qualified with its declaring type on the way in, and the
verification pass then proves the synthesised reference binds to the *exact
same field declaration* — not to something that merely shares its name at
the call site. That is the loop working as designed: the check is no longer
redundant, because the precondition it used to shadow is gone.

Cases that remain refusals because they are genuinely unrepairable: an
inaccessible field (JLS 6.6.1), an *instance* field (no receiver available),
an unqualified sibling *call* (evaluation order), `this`/`super` (receiver
rebinding).

Verified against a shadowing call site — one declaring its own `SCALE =
1000` — which still produces the original answer after inlining, where naive
substitution would silently have used the caller's value. Also verified for
constants inherited from a superclass (qualified with the ancestor, not the
subclass), interface constants, constants on nested types, and call sites in
a different package.

Two implementation traps worth recording, both instances of the same
recurring theme — **JavaParser reports what was written, not what the JLS
implies**:

- An interface field is implicitly `public static final` (JLS 9.3), but
  JavaParser reports `isStatic() == false` **and** `accessSpecifier() ==
  NONE`. A first version of the check tested only the access specifier and
  still refused the case; a direct probe was needed to find that *both*
  implicit modifiers were missing.
- A fully-qualified reference (`com.example.Util.SCALE`) is a chain of
  `FieldAccessExpr` nodes whose prefixes are package/type qualifiers that
  legitimately do not resolve as *values*. The verification pass initially
  rejected its own repairs for this reason.

### 6.4 A known over-refusal found while testing

`args.length` resolves to no inspectable declaration, so the constant-
expression check's conservative "unknown means possibly-constant" default
classifies it as constant — meaning `Util.scaled(args.length + 3)` is
refused as a potential constant-promotion even though `args.length` is
plainly a runtime value. Safe direction, but a real false positive. Assigning
to a local first (`int n = args.length + 3;`) works around it.

The semantic checks that verification structurally cannot see (`<clinit>`,
constant promotion, statement-position legality, `synchronized`, `throws`)
stay exactly as they are.

---

[tosem]: https://dl.acm.org/doi/10.1145/3747289
[eclipse1360]: https://github.com/eclipse-jdt/eclipse.jdt.ui/issues/1360
[idea127135]: https://youtrack.jetbrains.com/issue/IDEA-127135
[idea314882]: https://youtrack.jetbrains.com/issue/IDEA-314882
[thesis]: https://ora.ox.ac.uk/objects/uuid:1a027679-1e2b-4fb5-a6ff-3270f15154a1
[specifying]: https://dl.acm.org/doi/pdf/10.1145/1932682.1869485
[stepping]: https://dl.acm.org/doi/10.1007/978-3-642-03013-0_17
[renaming]: https://dl.acm.org/doi/pdf/10.1145/1449955.1449787
[steimann]: https://dblp.org/rec/conf/ecoop/SteimannT09.html
[substitution]: https://arxiv.org/pdf/2211.11550
[jls13]: https://docs.oracle.com/javase/specs/jls/se8/html/jls-13.html
[dcl59]: https://wiki.sei.cmu.edu/confluence/spaces/java/pages/88487460/DCL59-J.+Do+not+apply+public+final+to+constants+whose+value+might+change+in+later+releases

## 7. Backfilling the four older refactors

The four refactors predating `inline-method` had never had a review round.
One was run across all of them. **None was solid** — they were
under-examined, not simpler. Twelve findings, all reproduced and run; most
compiled cleanly and silently changed program output.

Seven of the twelve turned out to be one bug wearing different hats:

> Every refactor checked the collision surface of the **old** name. None
> checked what the **new** name already binds to at each affected site.

That is a dependency-preservation problem in the exact sense of §3 — and
unlike the first attempt at applying that idea, the fix here is not a
redundant checker. It replaces a genuinely wrong check with a correct one.

`NameBindingChecker` asks the symbol solver directly, at each site: *what
does this name already mean here?* Three entry points — value bindings
(locals, parameters, fields including inherited, enum constants, static
imports), type bindings (imports, type parameters, `java.lang`,
same-package), and visible methods (declared or inherited).

Closed by it:

- **rename-method** — renaming onto an applicable overload silently
  redirecting a call to a different method body; accidental overrides in
  both directions; an unqualified call shadowed by an *inherited* method.
- **rename-field** — capture of an inherited field, and of a static import.
- **rename-class** — a single-type import outranking a same-package type, so
  the rewritten reference binds to a different class entirely; and renaming
  onto an enclosing type parameter.
- **extract-variable** — the introduced name shadowing a field for the rest
  of the block.

Two notes on polarity and precision, both deliberate:

- **"Not solved" means "nothing binds", i.e. proceed.** This is the opposite
  default from the constant-expression check in §6.2, and correct here: for
  essentially every rename the new name binds to nothing anywhere, so
  treating unknown as "might bind" would refuse every rename.
- **Method checks match on bare NAME, not applicability.** Modelling JLS
  15.12.2 overload resolution properly would mean reimplementing it.
  Over-refusal is the direction this codebase always chooses.

### 7.1 The remaining five

The other five findings were not name capture and each needed its own fix.
Four are extract-variable, which is unusually exposed because it does two
risky things at once: it moves an expression's evaluation EARLIER, and it
gives the expression a standalone `var` type in place of whatever the
context was supplying.

- **Hoisting out of a class body.** `findAncestor(Statement.class)` walks
  straight past a local or anonymous class, because a field initializer
  inside one has no enclosing statement of its own. A per-instance
  initializer silently became evaluate-once.
- **Hoisting out of a try-with-resources header.** The anchor is the
  `TryStmt` itself, so the new declaration lands *before* the `try`, outside
  its own catch/finally. The repro went from catching cleanly to crashing.
- **`var` losing a context-supplied type.** `rejectUnsuitableInitializer`
  enumerated node kinds (`null`, lambda, method reference) as a stand-in for
  "this expression's standalone type equals its type in context" (JLS 15.2,
  5.2) — the recurring syntactic-proxy mistake again. Now: a diamond is
  refused outright (the resolver reports the *target-typed* answer, so
  comparing types cannot reveal the mismatch), and where the expected type
  *is* determinable, it must be assignable from the expression's own type.
- **Compound assignment reordering.** `x += f()` reads `x` before evaluating
  the right-hand side, so hoisting anything out of the RHS moves it before
  that read. The class doc had framed this hazard as needing "multiple
  arguments both hav[ing] side effects, which is already fairly unusual
  code"; `x += f()` is not unusual.

The fifth: rename-field's duplicate check only looked at `FieldDeclaration`,
missing `EnumConstantDeclaration` — the same enum-constant blind spot found
twice before in inline-method. Enum constants share a name space with
fields.

All twelve findings are now closed, with a test each.

## 8. Reviewing the fixes, and making mutation testing systematic

The fixes in §7 were themselves reviewed. The core held up — every new check
was pinned by the test claiming it, and the deliberately-broad checks did not
make any refactor unusable on ordinary code. But four fixes were
**incomplete in the same way**, and it is worth recording why, because it is
the same mistake this document is about:

> Each hazard was fixed at the SHAPE the reviewer demonstrated, rather than
> at the level the hazard actually lives.

- A named subclass was shown; named subclasses were handled. An **anonymous**
  subclass is the same hazard — and `findOverrides`, twenty lines away in the
  same file, already scans `MethodDeclaration` directly *precisely because*
  anonymous and enum-constant bodies are not `TypeDeclaration`s, with a
  Javadoc saying so.
- A diamond was shown; diamonds were handled. A **generic method**
  (`Collections.emptyList()`) is the same hazard, and the fix's own Javadoc
  articulated the reasoning that applies to it.
- A variable initializer was shown; initializers and assignments were
  handled. A **`return`** is the same hazard.
- The rename-class fix listed three kinds of reference site but not
  **imports**, which the same refactor rewrites.

All four are now fixed at the property level.

### 8.1 One recommendation reversed by testing

The review also suggested covering ARGUMENT positions in `expectedTypeOf`.
Building it showed it cannot work, for two independent reasons: JavaParser
throws `UnsolvedSymbolException` on `takesByte(5)` because it does not model
JLS 5.2 constant narrowing during applicability; and where a call *does*
resolve, the resolver already found it applicable, so `isAssignableBy` is
true and the branch could never fire. It would have been dead code at best
and an over-refusal on boxing at worst. Removed, and the gap documented
instead of papered over.

### 8.2 `tools/mutation-check.sh`

The review found a test that passed **with its check disabled** — the
enum-constant test, satisfied by a different check whose error message
happened to share the asserted words. No amount of reading catches that; only
mutation does.

So it is now a script rather than an ad-hoc habit. It disables each
`reject*` call in turn, runs the suite, and reports whether anything noticed.
A check reported as SURVIVED is either untested, or tested only by a test
that some other check also satisfies.

First full run: **28 killed, 15 survived.** All the checks added in §7 were
killed. The survivors are pre-existing gaps, and split into two kinds worth
distinguishing:

- **Genuinely untested** — most of extract-variable's original position
  checks (`rejectUnhoistablePosition`, `rejectRecurringControlPosition`,
  `rejectAssertPosition`, `rejectForwardReference`, `rejectLvaluePosition`,
  `rejectUnsuitableInitializer`, `rejectNonValueExpression`), and several
  inline-method body checks (`rejectSelfRecursion`, `rejectParameterWrites`,
  `rejectDeferredEvaluationConstructs`, `rejectMutatingOrCallExpressions`,
  `rejectUnsafeReceiver`).
- **Shadowed by a newer check** — `rejectDuplicateTypeName` and
  rename-field's `rejectShadowingCollision` now have their cases caught first
  by the §7 additions, so no test can tell the two apart. Redundant coverage
  is not a defect, but it does mean deleting the older check would go
  unnoticed.

The script exits non-zero while any check survives, so this is a standing
signal rather than a one-off measurement.
