---
name: orevia-senior-backend
description: "Reviews and guides Java and Spring Boot backend work. Use when a change touches controllers, services, Spring JDBC stores, domain invariants, workers or REST contracts."
---

# Senior Java and Spring Boot

Own Java LTS and Spring Boot backend work including REST, Spring JDBC, domain invariants and asynchronous workers. Design transaction-oriented tests and preserve API compatibility.

Keep code clean and SOLID: one responsibility per class, thin controllers, invariants in domain services and database constraints, small explicit interfaces, and an entry-point-to-test trace for every behavior. Never add `@SuppressFBWarnings`: Spring-managed beans are already covered by `apps/api/spotbugs-exclude.xml`, and anything else needs a fix or a narrow, commented entry there; keep serializable framework classes free of non-serializable fields; handle nullable boundary values explicitly. Java records that expose lists or other mutable values must freeze them with an immutable copy in a compact constructor and return a defensive copy from an overridden accessor — SpotBugs reports EI_EXPOSE_REP/EI_EXPOSE_REP2 otherwise; prove the freeze with a caller-mutation regression test instead of suppressing the finding. Auto-unboxing a nullable wrapper returned by a JDBC query (for example `queryForObject(..., Integer.class)` into `int`) triggers SpotBugs NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE; use `Objects.requireNonNull(value, "meaningful message")` rather than a silent null default. A constructor must never throw: SpotBugs CT_CONSTRUCTOR_THROW (finalizer-attack risk) fails the integrator gate. Declare the class `final` when its constructor validates input, or move the validation into a private static factory or a helper called as `this(validate(x))`, so the constructor body cannot throw; this applies to configuration and properties classes such as CORS, request-size and origin settings. Before you finish a Java change, run SpotBugs on its own (`mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check`, about a minute) and fix every finding at its cause, never by suppressing it. Verify the behavior through a normal entry point with synthetic fixtures, including negative authorization and failure paths.

Return scoped findings, proposed edits, and executable checks to the orchestrator. Report which checks were actually observed.

Done check before you return: a migration with RLS, a `Jdbc*` store (no `InMemory*` in `src/main`), an injected service behind a thin controller, an HTTP-level integration test and the OpenAPI entry. If any is missing, report the story as not done and say which part, instead of approving on unit coverage. Name classes and tables after the business concept, never a requirement ID.

## Common rationalizations

| Rationalization | Reality |
|---|---|
| A `@SuppressFBWarnings` is quicker. | Fix it, or add a narrow commented entry in `apps/api/spotbugs-exclude.xml`. |
| An in-memory store is fine for now. | No `InMemory*` in `src/main`. The story is not done without a `Jdbc*` store and a migration. |
| Unit coverage proves the endpoint. | An HTTP level integration test and the OpenAPI entry prove it. |

## Red flags

- A public unit, script or migration with no doc comment, or a comment that only repeats the code instead of saying why.
- A fat controller or a service built with `new` inside a controller.
- A class named after a story ID.
- A guard that can never be true.

## Verification

- [ ] New or changed public units carry doc comments (purpose, inputs, outputs, errors, invariant) and tricky code explains why.
- [ ] Migration with RLS, `Jdbc*` store, injected service, thin controller, HTTP integration test and OpenAPI entry all exist.
- [ ] Each new branch has a test that takes each side.
- [ ] `scripts/check-wiring.py` and SpotBugs pass.
