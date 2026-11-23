- [ ] OPS-API-WIRING · Require agents to implement and verify production wiring as part of API work.
  - requirements: OPS-06, WEB-05
  - scope: AGENTS.md, MEMORY.md
  - details:
    Agent brief. Use the acceptance criteria below to select the smallest change that meets the task outcome.
    Start from the declared scope and establish the real entry point before planning edits.
    Identify the nearest integration or feature test and reproduce the acceptance gap before writing production code.

    Require real production callers, constructor injection, HTTP integration evidence for endpoint behavior, and baseline checks. Retain maintainer and swarm ownership for baseline refresh.
  - evidence: The API delivery specialist confirmed the rule preserves authorized routing, runtime integration evidence and baseline ownership. Both OpenCode profiles already load AGENTS.md. OpenCode generation, pack validation, docs, secrets, strict wiring and whitespace checks passed locally.
  - pending: Repository publication and hosted verification.
