- [-] ISS-1589 · Repair slot images: sparse checkout omits slots.json
  - attempts: 0
  - agent: orca
  - scope: .github/workflows/swarm-images.yml, .github/workflows/swarm.yml, scripts/tests/test_swarm_checkout.py
  - details:
    GitHub issue #1589 (workflow run 45320142763) and Agent swarm run 45321906556
    both fail with `FileNotFoundError: '.opencode/config/slots.json'`. Commit 3a309e1b
    moved slots.json from `.github/swarm/` to `.opencode/config/` and updated the
    workflow comments and path triggers, but left the sparse-checkout lists in
    `swarm-images.yml` and `swarm.yml` pointing at the removed `.github/swarm`.
    Write a failing test that binds each sparse checkout to the path swarm.py
    actually reads, fix both workflows, then record commands and final lines here.
