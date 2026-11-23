## Clause

Require a named queue owner, backup coverage and overdue escalation.

## Status

Partly proven

## Evidence

All links are relative to this file; the repository root is four levels up. Only repository files are cited. No workflow-run output, pilot observation, partner confirmation or signed decision is stored in the repository, so none is claimed here. All fixtures in the cited tests are synthetic.

**Focused clause proof (this change).** [RequireNamedQueueOwnerBackupCoverageOverdueTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/RequireNamedQueueOwnerBackupCoverageOverdueTest.java) drives the real controllers, services and domain records through the Spring MVC tenant pipeline with the test-only in-memory stores:

- `enabledUnitRequiresNamedOwnerAndBackup` — an approved, effective profile naming an owner and a backup enables the unit and is served.
- `blankOrMissingQueueOwnerDisablesUnit`, `emptyOrBlankBackupCoverageDisablesUnit` — a blank or missing owner, or an empty or blank-only backup list, disables the unit at the domain guard and yields 404 through the service.
- `draftOrNotYetEffectiveProfileDisablesUnit` — neither a draft nor a future-dated profile can enable the unit or displace the effective named profile.
- `httpPublishRefusesBlankOwnerAndMissingBackup` — the publish route refuses a blank/absent owner and an empty/blank backup list with 422, and accepts a valid one with 201.
- `httpServesUnitOnlyWhenOwnerAndBackupAreNamed` — the profile route serves the named profile, then returns 404 once a persisted effective version loses its owner and backup.
- `anotherTenantCannotReuseTheOwnedUnit`, `aRoleWithoutProfilePermissionCannotUseTheOwnerBackupRule` — the same local unit id in another tenant is concealed (404) and a role without permission is refused (403).
- `overdueFollowUpsRouteToTheOwningUnitOnly`, `supersededInstructionFollowUpIsNotOpenWork` — overdue requested work is routed once to the owning unit, and a superseded revision is never open work.
- `unassignedUnitCannotReadOverdueFollowUps` — the overdue-follow-up route refuses a wrong role, a wrong tenant and an unassigned staff actor with 403, and serves the assigned unit actor.
- `overdueFollowUpsCannotNameTheProfileOwnerBackupOrEscalationChannel` — a passing assertion that records the structural gap (see below) without a skipped test. It must be deleted in the same change that closes the gap, because a `FollowUp` that gains owner/backup/channel fields will correctly make it fail.

**Production classes exercised.** The proof reaches [ServiceProfileContract.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/service/ServiceProfileContract.java) (`ServiceProfile.enablesUnitAt`, which requires an approved, effective profile plus a non-blank `queueOwner` and at least one non-blank `backupCoverage` entry); [ServiceProfileInputs.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/service/ServiceProfileInputs.java) (`safeText` and `list` publish validation); [ServiceProfileService.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/service/ServiceProfileService.java) (`viewProfile`, `availability`, `publish`); [ServiceProfileController.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/service/ServiceProfileController.java) (`GET /api/v1/care-units/{unitId}/service-profile` and `POST /api/v1/care-units/{unitId}/service-profiles`); [InstructionFactService.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/acknowledgment/InstructionFactService.java) (`overdueFollowUps`); and [InstructionFactController.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/acknowledgment/InstructionFactController.java) (`GET /api/v1/instructions/overdue-follow-ups`). Durability and forced-RLS evidence for the owner and backup columns is in [V92__service_profiles.sql](../../../../db/migrations/V92__service_profiles.sql) (`queue_owner` has `CHECK (btrim(queue_owner) <> '')`; `backup_coverage` is `text[] NOT NULL`; every table has RLS and FORCE RLS).

**Supporting evidence already on the branch, not duplicated here.** [ServiceProfileDomainTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/messaging/service/ServiceProfileDomainTest.java), [ServiceProfileServiceTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/messaging/service/ServiceProfileServiceTest.java) and [ServiceProfileHttpIntegrationTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/it/messaging/ServiceProfileHttpIntegrationTest.java) already assert the domain guard, denial paths and the durable HTTP publish/approve/serve chain. [InstructionFactServiceTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/messaging/acknowledgment/InstructionFactServiceTest.java) and [InstructionFactControllerHttpTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/messaging/acknowledgment/InstructionFactControllerHttpTest.java) already cover overdue routing, idempotency and supersession. The `overdueResponses` counter in [SupervisorSnapshotCalculator.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/service/SupervisorSnapshotCalculator.java) surfaces overdue work to supervisors; that belongs to MSG-06 clause 5, not this clause, and is not used as proof here.

## Gap

Owner and backup coverage are enforced at the publish boundary, in the domain enablement guard and partly in the database. The escalation half of the clause is only partly built, so the status is **Partly proven**:

1. **No enablement rule requires a non-blank escalation channel.** `ServiceProfile.enablesUnitAt` inspects only status, effective date, owner and backup; it never reads `escalationChannel`. `ServiceProfileInputs.safeText` rejects a blank channel at publish time, but [V92__service_profiles.sql](../../../../db/migrations/V92__service_profiles.sql) declares `escalation_channel text NOT NULL` with no `btrim(...) <> ''` check, so a persisted or migrated row with a blank channel still enables the unit. Closing this needs a domain guard branch, a migration check on the existing table, their tests and an approval-transition review.
2. **Overdue work is not routed to the named owner or backup, nor escalated through the profile's channel.** `InstructionFacts.FollowUp` carries only organization, instruction id, revision, unit id, route time, state and resolution fields — no owner, backup or channel. `InstructionFactService.overdueFollowUps` routes by `candidate.unitId()` and gates on staff-unit assignment; it never reads a service profile. The built behaviour routes overdue work to the *owning unit* under role and tenant scoping (proved above), but not to the *named* queue owner/backup or through the escalation channel. Closing this needs the follow-up contract, service, store, migration and controller to consult the effective service profile, plus tests.
3. **Public contract gap (reported, not edited).** `/api/v1/care-units/**` and `/api/v1/instructions/overdue-follow-ups` are absent from `contracts/openapi.yaml` and the generated client. That is already tracked as the `FIX-OPENAPI-01..04` route gap; it is out of scope for this test-only task and was not changed here.

The complete proof for the missing escalation rule is written below for a later task, with the required skip reason. It is documentation only here — an added `@Disabled` line in Java is rejected by `scripts/check-constraints.py` (AGENTS.md forbids skipped tests), so the current suite asserts the structural gap with a passing test instead of carrying a skipped one:

```java
@Test
@Disabled("GAP: escalation_channel is not required by enablesUnitAt and overdue follow-ups "
        + "are routed by unit id only: no rule names the queue owner, backup or escalation channel")
void overdueWorkIsEscalatedToTheNamedOwnerBackupAndChannel() throws Exception {
    // A blank escalation channel must never enable the unit, even with an owner and backup.
    ServiceProfile staffed = namedProfile(1, "nurse-lead", List.of("nurse-backup"));
    assertFalse(withEscalationChannel(staffed, "   ").enablesUnitAt(NOW));
    assertTrue(staffed.enablesUnitAt(NOW));

    // An overdue instruction for the unit routes to the named staffing and channel.
    publishOverdue(1);
    TenantContext.set(new TenantContext(ORG_A, STAFF, "PATIENT_MESSAGE", "c3-escalation"));
    List<FollowUp> followUps = factService.overdueFollowUps(UNIT);
    assertEquals(1, followUps.size());
    FollowUp routed = followUps.get(0);
    assertEquals("nurse-lead", routed.queueOwner());
    assertEquals(List.of("nurse-backup"), routed.backupCoverage());
    assertEquals("escalation-queue", routed.escalationChannel());

    // The escalation stays tenant- and role-scoped and does not duplicate on a rescan.
    mvc.perform(headers(get("/api/v1/instructions/overdue-follow-ups")
                    .param("unitId", UNIT), STAFF, ORG_A))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].escalationChannel").value("escalation-queue"));
    mvc.perform(headers(get("/api/v1/instructions/overdue-follow-ups")
                    .param("unitId", UNIT), STAFF, ORG_B))
            .andExpect(status().isForbidden());
    assertEquals(routed, factService.overdueFollowUps(UNIT).get(0));
}
```

This compiles only after the gap is closed: `FollowUp` needs `queueOwner()`, `backupCoverage()` and `escalationChannel()` accessors, `ServiceProfile.enablesUnitAt` must inspect the channel, and a `withEscalationChannel` fixture helper must be added alongside the `withStaffing` helper. The assertions above are the acceptance criteria for that work.

This needs at least **three separate tasks**: (a) a domain/database task for the escalation-channel enablement rule and its check; (b) a service/store/contract task to route overdue follow-ups to the named owner, backup and channel; and (c) a contract task to publish the care-unit and overdue-follow-up routes in OpenAPI and regenerate the client. Per-site human verification that the named owner, backup coverage and escalation channel are configured and drilled belongs to the G5 launch gate and cannot be created by a code change.
