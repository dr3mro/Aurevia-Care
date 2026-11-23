---
name: smartclinic-code-simplify
description: Simplify code for clarity without changing behavior. Use when a change is green but the implementation is heavier than it needs to be, when reviewing code with nested logic or duplication, or when consolidating rules scattered across layers. Do not use on code you do not understand yet, on throwaway work, or on performance-critical paths where the simpler form is measurably slower.
---

# Simplify code without changing behavior

## Responsibility

Reduce complexity while preserving **exact** behavior. The goal is not fewer lines;
it is code a new team member can understand faster than the original. Every change
must leave inputs, outputs, side effects, error behavior and edge cases identical.

## The rules

1. **Preserve behavior exactly.** If you cannot prove a simplification preserves
   behavior, do not make it. Lean on the existing tests; add a characterization
   test first when coverage is thin.
2. **Prefer clarity over cleverness.** Guard clauses, domain vocabulary and shallow
   nesting beat compact expressions and clever chaining.
3. **Name stable concepts.** Introduce an abstraction only when it names a real
   concept or removes consequential duplication. Do not create wrappers solely to
   hide an ordinary framework call.
4. **Remove, do not comment out.** Delete dead code, unused branches and stale
   comments in the same change. Keep comments that explain why a rule exists.
5. **Stay inside the task scope.** This skill never expands a change into unrelated
   cleanup. If a simplification needs a file outside the task scope, note it for a
   follow-up story instead.

## Workflow

1. Confirm the code is green and you understand what it does.
2. Write or identify tests that pin the current behavior.
3. Apply one small simplification at a time; run the focused tests after each.
4. Run the module's canonical check and the repo policy checks.
5. Record in your notes what you simplified and which tests prove behavior is unchanged.

## Common rationalizations

| Excuse | Why it is wrong |
|---|---|
| "It is shorter, so it is better." | Shorter is not always clearer; a dense expression can hide an edge case. |
| "The tests pass, so behavior is unchanged." | The tests may not cover the edge case your rewrite changed. Prove it with a characterization test. |
| "I will clean up the surrounding files while I am here." | Unrelated cleanup hides the behavior-preserving diff and expands review scope. |
| "This helper will be useful later." | A helper used once is not an abstraction; add it when a second real caller appears. |
| "The reviewer will understand it." | Optimize for the next person who has not seen this change. |

## Red flags

- A "simplification" that changes an error code, status, message or side effect.
- A new helper used once, or a wrapper that only hides a standard library call.
- Moving code across a task boundary without a follow-up task.
- Removing a test to make the code shorter.

## Verification

- [ ] The change is behavior-preserving: each modified branch is still exercised by a test.
- [ ] Focused tests and the module's canonical check pass after each simplification.
- [ ] `scripts/check-constraints.py` and `scripts/check-wiring.py` pass.
- [ ] Notes state what was simplified and which tests prove behavior is unchanged.

## Related skills

`smartclinic-code-reviewer` (five-axis review), `smartclinic-test-engineer`
(characterization tests), `smartclinic-delivery` (canonical checks and notes).
