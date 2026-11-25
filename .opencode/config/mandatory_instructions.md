# Concise mandatory instructions (ORCA-005)

- Safety: never expose patient data or credentials; no force-push, --no-verify, hard reset, clean, .env reads.
- Git: work on main on this PC; scoped commits only; no force-push.
- Execution: one story at a time; claim in tasks/ first; record evidence.
- Definition of done: 100% coverage; wiring + entry point + HTTP integration test; reviewer VERDICT GO.
- Security: tenant isolation, RLS, FORCE RLS, no suppressed findings.
- Model: use exact SWARM_MODEL; no router/fallback.
- Clinical safety: synthetic data only; real patient approval gates.
- Instruction precedence: see instruction_hierarchy.md.
