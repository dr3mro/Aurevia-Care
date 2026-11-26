- [-] MARKDOWN-ACCURACY · Audit all Markdown files for verifiable inaccuracies and fix them
  - agent: orca
  - scope: evidence-led; authoritative docs, current trackers and active task files only. Historical `.agents/` notes are read-only records and are not rewritten.
  - details:
    Owner requested a pass over all 3,064 tracked Markdown files (about 270k lines) and fixing every inaccuracy that can be verified. Method: mechanically extract backticked repository paths, stale root tracker paths (`TODO.md`, root `MEMORY.md`) and named scripts/commands from every Markdown file, check them against the current tree and gates, then repair authoritative docs and active tracker/task entries where evidence is unambiguous. Preserve historical notes and dated reports as records. Record findings, fixes and every left-unverified item in this file.
