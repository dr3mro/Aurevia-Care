- [x] PIN-DOCKER-BASE-IMAGES · Make Docker base images match their documented versions
  - agent: orca
  - requirements: OPS-06
  - scope: Dockerfile, Dockerfile.app, MEMORY.md, tasks/01268300-PIN-DOCKER-BASE-IMAGES.md, tasks/01268000-CLEAN-NAMING-BASELINE.md
  - details:
    Owner directive 2026-11-24 overrides the earlier same-day decision to leave
    Dockerfile version text untouched: use what the version comments say. Change
    `FROM` tags back to postgres:17-alpine, node:22-alpine and
    maven:3.9-eclipse-temurin-21 and re-pin each to a current multi-arch index
    digest. Leave the comments unchanged; they now describe the actual images.
    Java 21 stays consistent with the agent image and the openjdk21 JRE package.
    Registry digests were resolved from Docker Hub with an authenticated pull
    token; each index was checked to contain linux/amd64 and linux/arm64.
    Evidence to record here: check-names, check-docs and the applicable gate.
  - evidence:
    Tool, 2026-11-24: resolved the three tags through auth.docker.io and
    registry-1.docker.io; each returned multi-arch index contains linux/amd64
    and linux/arm64. One-off consistency check over all four FROM lines:
    comment tag == FROM tag and pinned digest == live tag digest, 4/4 OK, exit 0.
    Tool: `python3 -I scripts/check-names.py` exit 0; `python3 -I scripts/check-docs.py`
    exit 0; `python3 -I scripts/check-pack.py` exit 0 (Delivery tooling validated:
    39 agents, 39 skills); `python3 -I scripts/check-secrets.py` exit 0;
    `python3 -I scripts/check-wiring.py` exit 0; `python3 -I scripts/check-constraints.py`
    exit 0; `python3 -I scripts/check-migrations.py --require-baseline` exit 0.
    Not run: docker build / scripts/verify.sh (no docker CLI in this environment);
    CI image smoke is the remaining verification. Evidence source: executed tool
    commands plus the owner's directive; no simulated user or clinical review.
  - pr: local scoped commit on main; GitHub delivery not authorized in this request.
