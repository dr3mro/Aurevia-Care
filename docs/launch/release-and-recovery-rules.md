# Result release, sensitive information and identity recovery rules — decision pack

Status: Not approved

This pack prepares a decision for the accountable roles named in section 10 of the [Orevia Cloud V1 specification](../../.opencode/config/PROJECT_V1_SPEC.md). It sets out the choices, what the repository already enforces, what is still missing and the evidence a site must hold. It decides nothing: no option is selected, no jurisdiction, region, partner, name or date is supplied, and every cell of the approval record is empty. The human decision it prepares is `S-G5-03` in tasks/; acceptance is a person's decision and is not implied by anything in this file.

## Decision needed

The owner must approve, for each enabled site, rules in three areas and the two rehearsal artifacts that prove them:

1. **Result release policy** — which of immediate, timed and hold release may be used; who may authorize a release and lift a hold; what reason, expiry and review a hold requires; and how a corrected or withdrawn release updates current access while retaining history and notifying affected recipients.
2. **Sensitive-information handling** — what may appear in notifications, logs, URLs, analytics and patient-facing text; which sensitive actions require renewed authentication; who may approve emergency or support access, with what business purpose, scope and expiry; and what retention and legal-hold discipline applies to released material.
3. **Patient identity recovery and re-verification** — how enrollment is site-verified, how expiring single-use invitations are issued and consumed, what re-verification is required after lost access or a disputed identity, who clears a lockout, how a disputed link is suspended and restored, and the rules that a staff contact change does not change account ownership and that unlinking ends future access to a chart.

The approval must be accompanied by an **enrollment rehearsal** (a site-run walkthrough of enrollment, failure, lockout and recovery with re-verification) and an **access review** (a named review of release authorizations and holds, emergency/support access and account links). This pack prepares the questions and evidence; it does not record the outcome.

## Why it matters

- **XCH-06** — patient release is a separate authorization referencing the immutable revision, release policy version and effective time; approved immediate, timed and held releases, holds with reason/expiry/review, and corrected or withdrawn releases that update access and keep history.
- **IAM-05** — identity proofing uses site-verified enrollment, expiring single-use invitations and re-verification for lost access or disputed identity; several independently verified charts may link to one account; unlinking ends future access and a staff contact change does not change ownership.
- **OPS-01** — the approved policy pack must define patient release rules and, where applicable, retention and legal-hold rules and incident notification before live pilot.
- **OPS-02** — sensitive actions record business purpose, role, facility and user; successful sensitive downloads and disclosures need a durable audit record; least privilege and regular access review are required.
- **PAT-03** — privacy-safe enrollment, recovery and account lockout, renewed authentication for sensitive account changes and a support channel for identity disputes and record requests.
- **PAT-02** — released reports are authorized against the linked patient and the current release; no persistent clinical caches and confirmed intent for a local copy.
- **ADM-01** — tenant admins configure patient release policies; clinical and market policies need named approval, effective dates, audit and version history.
- **PRD-06** — clinical users decide and attest to release; a hold does not open access by elapsed time alone.
- **G5 (section 9)** — each site approves its release policy and identity procedures; the gate requires no unresolved patient-safety, tenant-isolation, record-integrity or critical-escalation defect in enabled scope.
- **Section 10** — the decision "Result release, sensitive information handling and patient identity recovery" is accountable to the **privacy lead and clinical safety lead**, with "Approved rules, enrollment rehearsal and access review" as its evidence.

## Approval register

The three rule areas and the two rehearsal artifacts named in
[Decision needed](#decision-needed) are the single decision that section 10 of
[PROJECT_V1_SPEC.md](../../.opencode/config/PROJECT_V1_SPEC.md) assigns to the
**privacy lead and clinical safety lead**. Each row names the accountable owner role, where
the repository evidence lives, the human artifact that must be attached, and the
current status. The evidence locations are code and document facts, not
approvals; they do not change who is accountable. Status values are:

- `approved` — a named person has signed and effective dates are recorded;
- `open` — human approval is not yet supplied, and the repository can hold the
  data once it is;
- `blocked` — human approval is not yet supplied and a named engineering gap
  prevents a durable, versioned approval record.

All five rows are `open` in this checkout: the durable stores, services, HTTP
entry point and tests that can hold this data exist, so the only thing
outstanding is the named human decision. No row is `blocked` by a persistence or
reachability gap. A passing repository check, a synthetic screen or a simulated
review cannot move a row to `approved`.

| Item | Spec clause | Owner role | Evidence location (repository) | Human evidence to attach (open item) | Status |
|---|---|---|---|---|---|
| 1. Result release policy | OPS-01; XCH-06; ADM-01 | Privacy lead and clinical safety lead | `apps/api/src/main/java/com/aurevia/exchange/release/ReleaseKind.java`, `ReleaseService.java`, `PatientReleaseController.java`; `db/migrations/V24__patient_release.sql`, `db/migrations/V84__report_release_store.sql`; `apps/api/src/test/java/com/aurevia/it/exchangerelease/ReleasePolicyTest.java`, `PatientReleaseControllerHttpTest.java` | Approved release policy version with permitted kinds, named approver and effective date; hold reason, reviewer, review/expiry and lift notes; withdrawal and correction records showing current access updated, history retained and recipients notified | `open` |
| 2. Sensitive-information handling | OPS-01; OPS-02; PRD-06 | Privacy lead and clinical safety lead | `apps/api/src/test/java/com/aurevia/web/LogRedactionTest.java`; `apps/api/src/main/java/com/aurevia/authz/access/EmergencyAccessService.java`; `db/migrations/V11__emergency_and_support_access.sql`; `docs/operations/tenant-lifecycle/retention-and-offboarding.md`; `docs/operations/delivery-safety/rollout-and-production-access.md` | Approved field allowlist for notifications, logs, URLs, analytics and downloads; redaction test results and sanitized samples; emergency/support grant with business purpose, separate approver, scope and expiry; retention period and legal-hold scope for released material | `open` |
| 3. Patient identity recovery and re-verification | OPS-01; IAM-05; PAT-02; PAT-03 | Privacy lead and clinical safety lead | `apps/api/src/main/java/com/aurevia/messaging/enrollment/PatientAccessService.java`, `EnrollmentContract.java`, `JdbcPatientAccessStore.java`; `db/migrations/V83__patient_access_store.sql`; `apps/patient-mobile/src/features/enrollment/screens.tsx` | Site-verified enrollment rule; invitation issue and single-use consume records; recovery re-verification evidence; named lockout-clearing authority; disputed-link suspension and restoration review; staff contact-change and unlink records preserving ownership and ending future access | `open` |
| 4. Enrollment rehearsal | OPS-01; IAM-05; section 9 G5 row; section 10 | Privacy lead and clinical safety lead | `apps/api/src/main/java/com/aurevia/messaging/enrollment/PatientAccessService.java`, [enrollment screens](../../apps/patient-mobile/src/features/enrollment/screens.tsx); `docs/operations/production-readiness/README.md`, `docs/operations/production-readiness/launch-checklist.md` | Site-run walkthrough record covering enrollment, failed proofing, lockout and recovery with re-verification, with named roles, date and outcome | `open` |
| 5. Access review | OPS-01; OPS-02; section 10 | Privacy lead and clinical safety lead | `docs/operations/production-readiness/README.md`, `docs/operations/production-readiness/launch-checklist.md`, `docs/operations/tenant-lifecycle/retention-and-offboarding.md` | Named review record with scope, reviewer, date, findings and dispositions for release approvals and holds, emergency/support access and account links | `open` |

The owner role is the same for all five rows because they are one section 10
decision, "Result release, sensitive information handling and patient identity
recovery", whose required evidence is "approved rules, enrollment rehearsal and
access review". The G5 row of section 9 additionally requires each site to
approve its release policy and identity procedures and to have no unresolved
patient-safety, tenant-isolation, record-integrity or critical-escalation defect.
The two rehearsal artifacts (rows 4 and 5) are section 10 evidence, not
additional decisions. Cross-cutting market topics that this pack does not
choose — hosting region, applicable law, incident notification, supplier
obligations and prescribing constraints — are recorded in
[policy-pack.md](policy-pack.md); this register stays scoped to the three areas
and the two rehearsal artifacts.

## Synthetic example (illustrative shape only — not an approval)

The row below shows only the *shape* a completed approval takes. Every value is
a placeholder token, not a person, date, jurisdiction, region, partner or
credential. It records no decision and must not be copied into the
[Approval record](#approval-record).

| Item | Policy version (synthetic) | Approver (synthetic) | Effective from (synthetic) | Effective to (synthetic) | Status |
|---|---|---|---|---|---|
| 1. Result release policy | `SYNTHETIC-RELEASE-POLICY-VERSION` | `SYNTHETIC-APPROVER-PRIVACY-CLINICAL-SAFETY` | `SYNTHETIC-DATE-FROM` | `SYNTHETIC-DATE-TO` | `open` |

A real row replaces each token with the approved value and attaches the evidence
named in the [Approval register](#approval-register). Until then the item stays
`open`.

## Requirement mapping

| Requirement / clause | What it requires | Where this pack addresses it | Status |
|---|---|---|---|
| OPS-01 | Approved policy pack defines patient release rules, retention and legal-hold rules and incident notification before live pilot; jurisdiction-specific requirements and owners recorded | [Decision needed](#decision-needed), [Approval register](#approval-register) rows 1-5, [Evidence checklist](#evidence-checklist) | `open` — human approval and signed policy pack pending |
| XCH-06 | Patient release is a separate authorization on an immutable revision, release policy version and effective time; immediate, timed and held releases with reason, expiry and audit; correction or withdrawal updates current access, retains history and notifies recipients | [Decision needed](#decision-needed) item 1, [Options](#options), [Approval register](#approval-register) row 1, [Why it matters](#why-it-matters) | `open` — site-approved policy version and named approver pending |
| IAM-05 | Site-verified enrollment, expiring single-use invitations and re-verification for lost access or disputed identity; several independently verified charts may link to one account; unlinking ends future access and a staff contact change does not change ownership | [Decision needed](#decision-needed) item 3, [Approval register](#approval-register) rows 3-4 | `open` — site procedures and enrollment rehearsal pending |
| OPS-02 | Least privilege and regular access review; audited clinical reads, downloads and disclosures with actor, purpose, scope, role, facility, user and outcome | [Decision needed](#decision-needed) item 2, [Approval register](#approval-register) rows 2 and 5 | `open` — named field allowlist and access review pending |
| PAT-02 | Released reports authorized against the linked patient and the current release; no persistent clinical caches; confirmed intent for a local copy | [Decision needed](#decision-needed) item 1, [Approval register](#approval-register) row 2 | `open` — patient-facing policy approval pending |
| PAT-03 | Privacy-safe enrollment, recovery and account lockout; renewed authentication for sensitive account changes; support channel for identity disputes and record requests | [Decision needed](#decision-needed) item 3, [Approval register](#approval-register) row 3 | `open` — site procedure approval pending |
| ADM-01 | Tenant admins configure patient release policies; clinical and market policies require named approval, effective dates, audit and version history | [Approval register](#approval-register) row 1, [Evidence checklist](#evidence-checklist), [Questions for the owner](#questions-for-the-owner) | `open` — no named approval or effective dates recorded |
| PRD-06 | Clinical users decide and attest to release; no autonomous care decision or controlled electronic prescribing | [Decision needed](#decision-needed) item 1, [Why it matters](#why-it-matters) | `open` — clinical authority to release not yet approved |
| Section 9 G5 row | Each site approves its release policy and identity procedures; no unresolved patient-safety, tenant-isolation, record-integrity or critical-escalation defect in enabled scope | [Approval register](#approval-register) rows 4-5, [What is missing](#what-is-missing) | `open` |
| Section 10 decision row | Privacy lead and clinical safety lead accountable; approved rules, enrollment rehearsal and access review as evidence | [Approval register](#approval-register), [Approval record](#approval-record) | `open` — all Approval record Name, Date and Decision cells empty |

## Options

The rows are alternatives for the owner to consider; none is selected or recommended here.

| Option | What it commits the platform to | Risks | Evidence each needs |
|---|---|---|---|
| Immediate release | Every approved revision becomes visible as soon as its authorization is effective, under the linked-patient check. | No window to catch a mistaken release before a patient sees it; depends on the correctness of the authorization decision. | Site-approved policy version, effective date and named approver; release authorization audit and access test result. |
| Timed release | Visibility begins only at a configured future instant, enforced on every access, including after a correction. | Incorrect instant or time-zone handling can open access early or delay a legitimate release indefinitely. | Time source and UTC instant recorded on the release; tests for before/after the instant and for inherited instant on correction. |
| Held release | The revision stays invisible until an authorized reviewer lifts the hold; a reason and a future review/expiry are recorded and audited. | Reviewer unavailable at the due time; hold left past its review date; risk of a hold being treated as a timed release. | Named reviewer roster, hold reason and expiry, lift record with review note, and a test proving elapsed time alone never opens a hold. |
| Mixed, versioned per-site policy | One effective policy version per site chosen from the approved kinds; history is retained when the policy changes. | Configuration drift between sites; a policy change changing access to an already released revision. | Site policy version with named approver and effective date; version history and test that a change does not alter already committed releases. |
| Generic-only notifications and redacted logs | Notifications carry no clinical detail; request bodies, tokens and patient fields are absent from logs, URLs, analytics and client storage. | Over-redaction removes useful operational context; a new field can leak before a redaction test covers it. | Redaction test result, sample sanitized logs and notifications, and a field-level allowlist approved by the privacy lead. |
| Restricted clinical-detail disclosure | Named clinical roles may see clinical content only inside an authorized chart or mediated download, with business purpose and outcome audited. | Broader visibility raises wrong-patient and over-disclosure risk; exceptions can outlive their purpose. | Role/purpose matrix, per-download authorization test, audit sample and expiry/review record for any exception. |
| Named, time-bounded emergency or support access | Access is reasoned, inside the organization, approved by a separate person, audited, expiring and reviewed; platform support access is off by default. | Self-approval, permanent grants or cross-organization reach defeat the control. | Separation-of-duties record, grant expiry, audit extract and access-review finding; outage procedure approved by the owner. |
| Enrollment and recovery through expiring single-use credentials | Site-verified relationship plus single-use invitations; recovery issues a fresh single-use code and re-verifies facts before access is restored. | Reused or stolen codes, failed proofing, lockout abuse and social engineering of a support channel. | Invitation issue/consume records, lockout and unlock review, disputed-identity re-verification record, and a site-run enrollment rehearsal. |
| Disputed link suspended pending review | A privacy officer suspends the link, a named reviewer re-reviews and re-verifies before future access is restored; already issued disclosures remain in history. | Restoration without re-verification; in-flight reads using a stale link state. | Suspension and re-review record with named roles, re-verification evidence, and a test that in-flight and new reads recheck link status. |
| Periodic access review | Named reviewers inspect release approvals and holds, emergency/support grants and account links on an agreed cadence, recording findings and dispositions. | Review becomes a paper exercise; no named owner or no closure of findings. | Review record with scope, reviewer, date, findings and disposition; evidence that expired grants and stale links were removed. |

## What the repository already supports

These links point at files that exist in this checkout. They describe enforcement mechanisms, not an approval of the rules above.

- **Release decisions** — [ReleaseKind.java](../../apps/api/src/main/java/com/aurevia/exchange/release/ReleaseKind.java) defines `IMMEDIATE`, `TIMED` and `HOLD`, with a hold never opening by elapsed time; [ReleaseService.java](../../apps/api/src/main/java/com/aurevia/exchange/release/ReleaseService.java) authorizes a release against an immutable revision, policy version and effective time, requires a reason and future expiry for a hold, and implements lift, withdrawal, correction and current-visibility checks; [PatientReleaseController.java](../../apps/api/src/main/java/com/aurevia/exchange/release/PatientReleaseController.java) exposes the HTTP entry point.
- **Release retention and tests** — [V24__patient_release.sql](../../db/migrations/V24__patient_release.sql) and [V84__report_release_store.sql](../../db/migrations/V84__report_release_store.sql) provide durable release, event, notification and history tables; [ReleasePolicyTest.java](../../apps/api/src/test/java/com/aurevia/it/exchangerelease/ReleasePolicyTest.java) and [PatientReleaseControllerHttpTest.java](../../apps/api/src/test/java/com/aurevia/it/exchangerelease/PatientReleaseControllerHttpTest.java) exercise the policy and the controller over HTTP.
- **Sensitive-information handling** — [LogRedactionTest.java](../../apps/api/src/test/java/com/aurevia/web/LogRedactionTest.java) proves tokens and patient fields are absent from logs; [EmergencyAccessService.java](../../apps/api/src/main/java/com/aurevia/authz/access/EmergencyAccessService.java) and [V11__emergency_and_support_access.sql](../../db/migrations/V11__emergency_and_support_access.sql) provide scoped, audited emergency and support access; [retention-and-offboarding.md](../operations/tenant-lifecycle/retention-and-offboarding.md) defines binding legal holds and the disputed-access suspension/re-verification rule; [rollout-and-production-access.md](../operations/delivery-safety/rollout-and-production-access.md) defines approved, attributable, audited and expiring production access.
- **Enrollment and recovery** — [PatientAccessService.java](../../apps/api/src/main/java/com/aurevia/messaging/enrollment/PatientAccessService.java) implements site-verified single-use invitations, recovery with re-verification, lockout after repeated failures, renewed authentication for sensitive changes, contact changes that preserve ownership, unlinking and support cases; [EnrollmentContract.java](../../apps/api/src/main/java/com/aurevia/messaging/enrollment/EnrollmentContract.java) defines the contract facts; [JdbcPatientAccessStore.java](../../apps/api/src/main/java/com/aurevia/messaging/enrollment/JdbcPatientAccessStore.java) and [V83__patient_access_store.sql](../../db/migrations/V83__patient_access_store.sql) persist accounts, invitations, tokens and audit facts tenant-scoped; [screens.tsx](../../apps/patient-mobile/src/features/enrollment/screens.tsx) provides the patient enrollment, recovery, support and lockout screens.
- **Evidence registers and decisions** — [production readiness register](../operations/production-readiness/README.md) and [launch checklist](../operations/production-readiness/launch-checklist.md) list the human-owned G5 evidence; [decision 0003](../decisions/0003-persistence-of-in-memory-stores.md) records why report release and patient-access state must be durable. The `docs/spec/clauses` clause notes referenced by this task do not exist in this checkout; the relevant clause tasks are open (see below).

## What is missing

Everything here needs a person, a site or a separate task; this pack closes none of it.

| Missing item | Task or NO TASK | Note |
|---|---|---|
| Approval of these rules by the accountable roles | `S-G5-03` | Human decision; this task prepares the pack and never records an approval. |
| Per-site named approvers, effective dates and signed release policy | `S-G5-12`; otherwise NO TASK | Site acceptance is a person's decision; no value is invented here. |
| Site-run enrollment rehearsal record | NO TASK | The repository has synthetic tests and screens, not a site rehearsal with named roles. |
| Site access-review record for release approvals, holds, support/emergency access and account links | `SPEC-OPS-02-C03`; the periodic human review is NO TASK | The open task covers the automated least-privilege/access-review contract and clause note, not a signed review. |
| Clause-by-clause status evidence for the two requirements | `SPEC-XCH-06-C01`, `SPEC-XCH-06-C02`, `SPEC-XCH-06-C03`, `SPEC-IAM-05-C01` | All open; no `docs/spec/clauses` note exists for XCH-06 or IAM-05 in this checkout. |
| Named sensitive-information field allowlist and exception approvals | NO TASK | Policy decision for the privacy lead; the redaction test is only a mechanism. |
| Named reviewer roster, hold review cadence and lockout-clearing authority | NO TASK | Roles are accountable in section 10; individuals and cadence are site decisions. |
| Retention period and legal-hold scope for released material at each site | NO TASK | Depends on the site's jurisdiction and policy pack, which this pack does not choose. |

## Questions for the owner

1. Which release kinds may a site enable, and who may authorize a release and lift a hold?
2. What reason, review cadence and maximum duration apply to a hold, and what happens when a hold reaches its review date?
3. Which fields are allowed in notifications, logs, URLs, analytics and downloads, and who approves an exception?
4. Who may issue recovery codes, clear a lockout and restore a disputed link, and what re-verification is required in each case?
5. Which roles run the enrollment rehearsal and the access review, and how often is the review repeated?
6. What retention period and legal-hold scope apply to released material, and under whose authority?
7. Who signs the per-site policy version, effective date and final acceptance, and where is the signed record held?

## Evidence checklist

Each artifact below must be held by the site and reviewed by the accountable roles; none is supplied by this pack.

- [ ] Site-approved release policy stating the permitted kinds, named approver, version and effective date.
- [ ] Record of each release authorization referencing the immutable revision, release policy version and effective time.
- [ ] Hold register with reason, named reviewer, review/expiry date and a lift record carrying a review note.
- [ ] Withdrawal and correction records showing current access updated, history retained and affected recipients notified per policy.
- [ ] Sensitive-information policy naming the fields allowed in notifications, logs, URLs, analytics and downloads.
- [ ] Log-redaction and notification test results plus a sample of sanitized logs and notifications.
- [ ] Emergency and support access grants with business purpose, role, facility, separate approver, expiry and review.
- [ ] Retention and legal-hold ledger covering released material, with authorized holds and lifts.
- [ ] Enrollment rehearsal record covering site-verified relationship, invitation issue and single use, failed proofing, lockout and recovery with re-verification.
- [ ] Disputed-identity procedure record with suspension, named re-reviewer, re-verification and outcome.
- [ ] Access review record with scope, reviewers, date, findings and dispositions for release approvals, holds, support/emergency access and account links.
- [ ] Staff contact-change and unlink records showing ownership unchanged and future chart access ended.
- [ ] Sample generic notifications containing no clinical detail.
- [ ] Per-site G5 sign-off of these rules by the privacy lead and clinical safety lead.

## Approval record

No approval is recorded here. The accountable roles below are taken from section 10; the person who signs is recorded outside this repository.

| Decision (section 10) | Accountable role (section 10) | Name | Date | Decision |
|---|---|---|---|---|
| Result release, sensitive information handling and patient identity recovery | Privacy lead |  |  |  |
| Result release, sensitive information handling and patient identity recovery | Clinical safety lead |  |  |  |
