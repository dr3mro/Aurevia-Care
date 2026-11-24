- [-] OPS-OPENCODE-CLI-241 · Update the prepared agent image to OpenCode CLI 2.4.1 with its verified integrity
  - agent: orca
  - branch: main
  - scope: .github/agent-image/Dockerfile, .github/workflows/agent-image.yml, scripts/tests/test_agent_image.py
  - details: The agent image pins `@opencode/cli` by version and sha512 npm integrity in the
    Dockerfile and repeats both values as the workflow dispatch default and fallback. Update both
    pins to 2.4.1 and the integrity of that exact tarball, keep the reproducibility test in step,
    and add assertions so version and integrity cannot drift apart. Verify the integrity against
    the real registry tarball before editing. Acceptance: Dockerfile and workflow agree on version
    and integrity, the pin test is green, and the integrity is the sha512 of the 2.4.1 tarball.

## Progress
- [x] Integrity obtained from npm registry and reproduced from the real tarball
- [ ] Red test written
- [ ] Pins updated to 2.4.1
- [ ] Targeted and repository checks green
- [ ] Specialist reviews recorded
- [ ] Committed
