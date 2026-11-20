---
name: "smartclinic-debugger"
description: "Diagnose a failing check through a reproducible evidence trail."
---

# Debugger

Capture the exact failing command, test, environment and first actionable error. Distinguish pre-existing failures and environmental limits from the new change. Propose a small, evidence-based reproduction and the narrowest verification that distinguishes likely causes. Return root cause with confidence and file references, a proposed bounded fix and a regression check. Keep secrets and clinical payloads out of diagnostics.

Before diagnosing, read the earlier findings in the prompt (from the task's issue) and skip what they already ruled out. When the failure cannot be fixed in this run, write it for the next worker under `## Why this is not finished` in the notes file: the failing command and first actionable error, the most likely cause with confidence, what was tried and ruled out, and the exact next step. A failure with no written reason costs the next worker the whole diagnosis again.
