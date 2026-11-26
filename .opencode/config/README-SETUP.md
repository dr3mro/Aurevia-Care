# Project setup and delivery

This archive contains a resumable OpenCode 2.x team, the approved V1 specification snapshot, GitHub Actions, a monorepo skeleton and a Docker release rehearsal. It supports repeated delivery cycles as new stories and defects arrive. The first container is a disposable bootstrap test stack with an HTTP health endpoint and loopback-only PostgreSQL. Each start initializes a fresh database. Application modules are pending. Replace the bootstrap Dockerfile and smoke assertions with the real web/API entry points during G0 and keep the tag and digest contract. The patient app lives in the same repo and is built and tested through its own Expo tooling.

## Project name

The starter name is **Aurevia Care**, with the suggested repository slug `aurevia-care`. Change `displayName` and `repositorySlug` in `project.json` to rebrand before setup. The identity is validated in CI, used for repository creation and stamped into the Docker image title. New client apps should read the display name at build time from this file. After repository creation, GitHub identities and registry paths follow the actual remote. Agent IDs, mobile bundle IDs and historical specification names are stable technical identifiers.

## Development stack and specialist routing

The core stack follows the included product specification. Select and pin compatible versions during G0. Suggested build and test tools below are starting choices for the team.

| Area | Languages, frameworks and tools | Specialist routing |
|---|---|---|
| Backend and workers | Java LTS, Spring Boot, Spring JDBC, REST and OpenAPI | Senior Java and Spring Boot |
| Identity and permissions | Spring Security, managed OIDC, server-side authorization | Senior Spring Security and OIDC |
| Java build and verification | Maven Wrapper, JUnit, JaCoCo | Senior Java Build and Test Tooling |
| Staff web | React, TypeScript, Vite, generated OpenAPI client | Senior React and Vite |
| Patient mobile | React Native, Expo, TypeScript, iOS and Android | Senior React Native and Expo |
| Shared client tooling | Strict TypeScript, Node LTS, generated client types, lockfile; Vitest for web, Jest and React Native Testing Library for mobile | Senior TypeScript with the affected client specialists |
| Database | PostgreSQL, SQL, Flyway; integration tests on real PostgreSQL | Senior PostgreSQL, SQL and Flyway |
| Interoperability | FHIR R4, partner-specific HL7 v2 and DICOMweb when required | Senior Integrations |
| Project and container tooling | Bash, Python 3, Docker and Compose | Senior Bash and Container Tooling, Senior Python Tooling |
| Hosting and infrastructure | AWS ECS Fargate, RDS, private S3 and SQS where approved, infrastructure as code | Senior Cloud Infrastructure as Code with System Designer and DevSecOps |
| Delivery and validation | GitHub Actions, GHCR, native AMD64 and ARM64 smoke checks, Git-tagged releases, coverage gates | GitHub Workflow Expert, Tester and QA |

## Create the monorepo

1. Extract the archive into an empty project directory, including the hidden .opencode and .github directories. In an existing repo, merge configuration and preserve tracker history.
2. Install OpenCode 2.x, Git, Python 3, Bash, Docker with Compose and GitHub CLI. Add the Java/Node toolchains when the application modules are introduced.
3. Java builds run OWASP Dependency-Check and SpotBugs inside `mvn verify`. An optional repository secret `NVD_API_KEY` gives fresh NVD data in CI; without it the scan uses the cached database and still fails on CVSS >= 7. The key is read from the environment and must never be written to the repository.
4. Run `git init -b main`, then `scripts/check.sh` and `scripts/verify.sh`. The first script lints the shell scripts, validates the agent pack and generated `opencode.json`, checks Markdown links and requirement IDs against the specification, scans for credential and PHI-shaped strings, runs the tooling unit tests and runs any wired module checks. The second also builds and smoke-tests the local image. Use `docker compose up -d` to start the all-in-one bootstrap test image after the local build. Test with `curl -fsS http://127.0.0.1:8080/health.txt`. Stop it with `docker compose down`.
5. Make the initial verified commit, create the GitHub repository, add `origin`, and push `main`. You can also create and push to a private project with `gh repo create "$(python3 scripts/project-name.py repositorySlug)" --private --source . --remote origin --push`. After the first green run, require both **Verify and smoke image (amd64)** and **Verify and smoke image (arm64)** checks in a branch ruleset, protect release tags, and restrict the GHCR package as appropriate. Use a delivery branch and pull request once protections are active.
6. Start OpenCode at the project root. Run `opencode debug agents` to confirm agent discovery. Enable the configured model and authorized execution environment. WORKSPACE.md stores entry points and the active branch.

| Location | Responsibility |
|---|---|
| apps/api | Java and Spring application and worker |
| apps/staff-web | React and TypeScript staff UI |
| apps/patient-mobile | React Native and Expo |
| db/migrations | Flyway migrations and database integration checks |
| contracts | OpenAPI and synthetic interoperability fixtures |
| infra | Deployment and test infrastructure |
| .opencode | Agents and reusable skills |
| .github/workflows | Branch, pull request, merge queue and tag verification |

## Run the delivery loop

> Resume Aurevia V1 in this repository. Start with WORKSPACE.md and the `tasks/` directory. Execute each eligible story, WIP one, with test-first implementation and specialist reviews. Save each stage. Commit small verified changes, push to the current approved delivery branch, and verify GitHub checks for the exact SHA. Stop only when the eligible queue is empty or the next step needs unavailable evidence, access or an owner decision. Release tags require separate release authority.

Product Manager turns feedback into outcomes. Product Owner owns priority and story acceptance. Architect and Scrum Master order the work. Senior Software Engineer routes the slice to React, mobile, backend, database, integrations or another relevant expert. Tester defines executable tests, the primary agent runs them, QA reviews evidence independently, and Debugger diagnoses failures. UI/UX and synthetic clinician or unit reviewers exercise the relevant product flows and feed defects and ideas back into the next cycle. The System Designer owns deployment planning, load balancing and scaling. DevSecOps reviews defensive controls. The GitHub Workflow Expert owns delivery pipeline guidance.

Use synthetic review for routine engineering. Record real clinical and unit feedback only from authorized people. Keep each stage's evidence in its `tasks/` file (`[ ]` open, `[-]` taken, `[x]` done), update WORKSPACE.md when the environment changes, and record defects in BUGS.md. Resume by reading these files and only the specification IDs needed. Track improvement ideas and acceptance in `tasks/`.

## Checks, coverage and release

`opencode.json` is generated: edit `scripts/gen-opencode.py` or add an agent file, run `python3 scripts/gen-opencode.py`, and commit the result. The pack check fails when the committed file is stale. Under the owner's standing authorisation, the orchestrator runs scoped shell commands without prompts; the generated profile is the authoritative permission set and keeps hard denies for force-push, remote branch deletion, hard reset, clean, `--no-verify`, root and home recursive deletion, download-to-shell pipelines and `.env` reads. Production delivery, tags and deployment stay separately gated by `.opencode/setup/AGENTS.md`.

Every scoped commit runs `scripts/verify.sh` locally. In GitHub, `delivery.yml` is split into small dependent jobs: policy/tooling, Java and database verification, developer reference, then per-architecture image smoke, then release. Java and OWASP NVD data are cached; verification steps live in reusable composite actions under `.github/actions`. Pull request heads, tags, dispatches and merge groups keep a verdict for their exact SHA; superseded branch pushes are cancelled so a burst of commits does not wait behind older runs. Expensive full verification stays serialized within each PR and tag. Keep the module `check.sh` files as the canonical test entry points. They must fail the build below the 100% thresholds in CODING_STYLE.md. The pack check rejects production module changes with no check entry point. It does not measure application coverage before implementation.

Release from an exact green default-branch commit. Create and push an authorized annotated tag such as `v0.1.0-rc.1`. The version comes from the tag. CI builds and smoke-tests native Linux AMD64 and ARM64 images, then publishes a multi-architecture manifest to `ghcr.io/<owner>/<repo>:<tag>`, and creates a GitHub release with the image digest. Stable releases use tags such as `v1.0.0`. Treat tags as immutable. Pull by digest for repeatable testing. For localhost smoke use `AUREVIA_IMAGE=ghcr.io/<owner>/<repo>:<tag> docker compose up -d` after logging in if required.

The release workflow publishes an image and release record. The System Designer and DevSecOps review real deployment, ingress, load balancing, scaling, recovery and operational gates as separate work.

- [OpenCode agents](https://opencode.ai/docs/agents/)
- [GitHub container publishing](https://docs.github.com/en/actions/tutorials/publish-packages/publish-docker-images)

For one-container testing use `docker run --rm -p 127.0.0.1:8080:8080 ghcr.io/<owner>/<repo>:<tag>`. The image uses ephemeral synthetic storage. GitHub Actions must have an Arm64 hosted runner available for the release matrix.


## Agent team in GitHub Actions

See [docs/TEAM-ACTIONS.md](../../docs/TEAM-ACTIONS.md) for the prepared agent images, what runs on which schedule, and the safeguards. [STARTER_PROMPT.md](STARTER_PROMPT.md) says how to start, steer and stop the swarm.

## Agent swarm

See [docs/SWARM.md](../../docs/SWARM.md) for the 10-worker swarm driven by `tasks/`: its integrator, the issue hand-off between workers, the issue monitor, the daily notes and the daily audit.

## Browse and maintain the code

Install Doxygen, then run `scripts/docs.sh`. Open `build/developer-reference/html/index.html` in your browser. It links to the maintainer guide, Java packages, class and file references, source pages and staff and mobile TypeScript references. GitHub Actions builds the site and makes it available as the `developer-reference` artifact. Download and extract the artifact before opening the index.

Java uses Javadoc comments rendered by Doxygen. Each TypeScript app adds a version-pinned TypeDoc dependency and an `apps/<module>/docs.sh` hook as part of its first implementation. The hook receives an absolute output directory as its first argument, uses the locked local TypeDoc tool and compiler configuration, fails on warnings, and writes `index.html`. See `CODING_STYLE.md` for required comments and `docs/WORKFLOW.md` for review routing. The starter site contains maintainer guidance and pending-module pages until application code is added.

If your browser restricts local-file search, run `python3 -m http.server 8000 --bind 127.0.0.1 --directory build/developer-reference/html` and browse to `http://127.0.0.1:8000/`. Keep the server bound to localhost when reading internal source.
