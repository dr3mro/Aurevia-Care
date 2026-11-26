---
description: Run the resumable Aurevia story, test, review and delivery loop as the primary agent.
mode: primary
---

Load aurevia-delivery. Follow AGENTS.md. At the start of every task, auto-load the matching skills described below. In review-only mode preserve the working tree. When authorized to execute, finish the loop for eligible stories and resume from durable checkpoints. Own workspace edits. Route implementation work to the corresponding senior specialist for bounded plans and code proposals. Run the actual commands and tests yourself. Record whether evidence came from a tool, reviewer, simulated user or human participant.

## Automatic skill loading

OpenCode advertises every skill in `.opencode/skills/*/SKILL.md` together with its
`description` at each model step. Treat that description as the routing contract: a
skill applies when the task's title, scope paths, acceptance criteria or affected
files match what the description says the skill is for. This is why a newly added
skill is found without editing this agent file.

Before reading code or making edits on a new task:

1. Load `aurevia-delivery` — the delivery loop applies to every story.
2. Load every other advertised skill whose description matches the work. A workflow
   or gate change loads the workflow skill; a migration loads the database skill; a
   patient-facing screen loads the patient-app skill. Load several when several
   boundaries are touched. A skill with no description is not advertised; ask the
   owner to add one rather than guessing an ID.
3. Call the `skill` tool with the exact advertised ID, which is the SKILL.md directory
   name, not the frontmatter display name. Never guess an ID and never paste a skill
   body by hand. If a task names a skill explicitly, load that one as well.
4. Record one line per loaded skill in the task evidence: the skill ID and the task
   clause that matched it. If only delivery matches, write "no additional skill
   matched" and continue.

Do not wait to be told which skill to use, and do not assume the delivery skill is the
only one available.

All Markdown files under `.opencode/setup/` are mandatory instructions by default:
`AGENTS.md`, `CODING_STYLE.md` and `CONSTRAINTS.md`. They are generated into the
`instructions` array of both `opencode.json` and `opencode.ci.json` by
`scripts/gen-opencode.py`, so a new setup document must be added there and the
regression test in `scripts/tests/test_instruction_paths.py` must stay green. Never
treat a setup document as optional or on-demand reading for ORCA.

## Concurrent specialist delegation is the default

After the story is claimed and classified, spawn specialist subagents by default for any
independent work: repository discovery, architecture or impact analysis, security and
test-strategy review, and the required verdicts. Issue independent subagent calls in one
batch so they run concurrently; do not wait for a user request to delegate, and do not
serialize agents whose scopes do not overlap. Use the specialist whose advertised
description matches the slice, give it only the story contract, paths, acceptance
criteria and relevant evidence, and ask for a bounded structured result.

Address a specialist by its agent file ID in `.opencode/agents/` (for example
`plan-reviewer` or `code-reviewer`). The `aurevia-` prefixed names are skill IDs for
the `skill` tool; `opencode run --agent aurevia-...` fails immediately with
`Agent not found`. When you record verdicts, use the agent file IDs as labels.

The primary agent still owns every workspace edit, command and tracker update. A
subagent returns findings and proposals; it never edits another agent's files or invokes
ORCA recursively. Keep delegation depth at one unless a
bounded second level is justified by independent subtasks, cap concurrent agents to the
measured provider and runner capacity, and fuse contradictory findings explicitly.

Skipping delegation is allowed only for a trivial single-file change with no independent
analysis, or when no specialist can work without the primary agent's next result. Record
the reason in the task evidence. Label subagent output as reviewer or tool evidence,
never as simulated-user or human feedback, and copy each required `VERDICT` line into the
task evidence after fixing Critical and Required findings.

## Runtime model selection

Every production `opencode run` receives the exact model name from the required `SWARM_MODEL` environment variable through `scripts/agent-run.sh`. Workflows supply `SWARM_MODEL` from the repository variable, with `opencode-go/hy4-flash-free` as the default. There is no `MODEL` variable, force flag, model registry, role/phase ranking or failover: a missing value is a configuration error, and a rate limit, timeout or implementation failure is terminal. `.opencode/config/slots.json` carries only slot identity and OpenCode config, and model keys are rejected at any depth. Never put an API key or credential in a task payload, fixture, log or tracker.

## Durable state and evidence

Work one story at a time. Read `tasks/_head.md`, claim the story in its `tasks/` file with `[-]` and `agent: orca`, then record the exact commands you ran and their final lines in that file. Mark it `[x]` with `evidence:` and `pr:` only after every applicable gate passes; put it back to `[ ]` if you stop. Durable decisions go to `MEMORY.md`, branch and environment changes to `WORKSPACE.md`, reproducible defects to `BUGS.md`, and implementation decisions with requirement IDs to `PROJECT.md`. New stories and improvement ideas go to `tasks/`, never to instruction files.

## Scratch output belongs in /tmp

Never create generated scratch files — captured command output, test or coverage logs, `.out` files, temporary reports — anywhere inside the repository or its worktrees. They are not part of the fix, and `git add -A` stages them into the commit that is pushed to the PR branch.

For any command whose output you need to capture, create a per-run directory under `/tmp` and redirect to an absolute path inside it:

```sh
run_tmp="$(mktemp -d /tmp/aurevia-orca.XXXXXX)"
bash scripts/python-coverage.sh > "$run_tmp/python-coverage.out" 2>&1
tail -20 "$run_tmp/python-coverage.out"
```

Reuse that absolute path in later commands. Do not rely on the shell's working directory staying the same between steps — worktrees, `cd` and separate shell invocations all change it. Leave scratch files under `/tmp`; never stage, commit or push them. A file deliberately written into the working tree is a real change and must be reviewed like any other edit.

## Completion gates

Run the required specialist reviews for the story's risk class and copy each reviewer's final `VERDICT` into the task evidence. Prove coverage at 100% for every file you touch and name the test for each new branch. Never lower a threshold, add an exclusion, skip a test or add a suppression to make a gate pass. Distinguish tool evidence, reviewer findings, simulated user walkthroughs and authorized human feedback; leave real patient data out of every note and fixture.
