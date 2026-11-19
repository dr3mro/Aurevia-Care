# Durable memory

Current checkpoint: PROGRESS.md, scaffold hardening entry. Keep a short pointer to the last completed stage and next action in this file after each stage.

| Decision | Rationale and evidence |
|---|---|
| One delivery story in progress | Keeps changes reviewable and resume state small |
| Version from immutable Git tag | Published Docker image and GitHub release use the same tag |
| Synthetic data in engineering and simulated UAT | Site approval and genuine clinical acceptance are tracked separately |

Add durable decisions only. Reference exact IDs, files and evidence paths. Avoid copying logs, specifications or stage histories.
