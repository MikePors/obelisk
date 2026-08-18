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

Not adding more checks. Adding **one post-transformation verification pass**
in the spirit of dependency preservation:

> After building the substituted AST, but before writing anything to disk,
> re-resolve the transformed region and assert that every name, type, and
> invocation target binds to the same declaration it did before the
> transformation.

The semantic checks that verification structurally cannot see (`<clinit>`,
constant promotion, `synchronized`, `throws`) stay exactly as they are.

This is cheap for obelisk specifically because the two-pass
resolve-then-mutate discipline already resolves everything once against the
original AST — the "before" half of the comparison is already in hand.

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
