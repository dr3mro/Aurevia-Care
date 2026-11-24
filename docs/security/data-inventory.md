# Personal data inventory — engineering review draft

> **This is an engineering inventory for a privacy officer to review, not legal advice.**
> It describes what the code and database migrations store today so that a person
> with the proper authority can decide what the policy pack (OPS-01) should say.
> It does not make a legal determination about lawfulness, retention periods or
> disclosure duties. All values used in development and UAT are synthetic; no
> real patient data appears in fixtures, tests or this document.
>
> **Newest migration covered: V90 (`V90__onboarding_import.sql`).** Migrations are
> applied in numeric version order; V90 is the highest version present in
> `db/migrations` at the time of writing. Tables created by an earlier version
> and later altered are noted in the row where relevant.
>
> Requirement IDs for this inventory: OPS-01 (policy pack / retention / legal
> hold), ARC-03 (API and audit facts), PAT-03 (patient privacy-safe access and
> support). Supporting IDs referenced below: ARC-02 (tenant isolation), OPS-02
> (audit), OPS-07 (tenant export and retention).

## How to read the tables

- **Table** is the exact PostgreSQL relation name under `public`. Every table
  below is tenant-owned (`organization_id` in the primary key) and under forced
  row-level security conditioned on `app.current_org()` (ARC-02), unless marked
  otherwise in the source migration.
- **Data (plain words)** describes the personal or clinical content. Identifier
  *values* are stored only where the column list says so; some ledgers store
  identifier categories or digests instead, as noted.
- **Who can read** lists the role *keys* used in the code, not human job titles.
  The authoritative sets are in `authz/ActionPolicy.java`,
  `chart/record/ChartActionPolicy.java`, `documents/DocumentPolicy.java`,
  `exchange/*/*ActionPolicy.java`, `lab/accession/LabActionPolicy.java`,
  `finance/FinanceActions.java`, `prescription/PrescriptionActions.java`,
  `prescription/rx/RxActions.java`, `authz/specialty/SpecialtyActions.java`,
  `therapy/TherapyService.java`, `scheduling/checkin/WorklistPolicy.java` and the
  `messaging/**/*Actions.java` classes, all under
  `apps/api/src/main/java/com/aurevia/`. All paths in this document
  are relative to that directory. A role can read a row only inside its active
  organization and, where the code says so, only within an assigned facility,
  care team, unit or verified patient link.
- **Append-only** means the runtime role `orevia_app` has no `UPDATE`,
  `DELETE` or `TRUNCATE` path (enforced by revokes and/or
  `reject_immutable_mutation()` triggers). "No — state in place" means the row
  is mutated through a bounded state machine, typically with an immutable event
  table alongside it.
- **Tenant export** and **Retention** are the OPS-07 capabilities. Today the only
  implemented data-pipeline for either is the *messaging governance* framework
  (V62, `messaging/governance/`), and it tracks synthetic `messaging_resource`
  facts only. The G5 artifacts checked by
  `infra/s_g5_08/tenant_lifecycle_evidence.py` validate the *shape* of an
  export manifest and retention ledger; they do not export or delete live rows.
  Everywhere else the cells read `NOT YET:` with a suggested task:
  - `TASK-EXPORT` — **Implement OPS-07 tenant export pipeline (manifest, schema
    version, checksums, authorized documents, provenance and audit history).**
  - `TASK-RETENTION` — **Implement OPS-07 retention jobs with legal holds, an
    approved deletion ledger and backup expiry, per table family.**
- **Audit events** name the append-only facts that record access. The central
  `audit_event` hash chain (V3) is written for protected reads and writes with
  actor (`app.actor`), business `purpose`, `scope`, `action`, resource id,
  correlation id and outcome; the wire `action` and `purpose` values are the
  stable strings in the service classes. Many modules also keep a local
  append-only ledger (for example `appointment_event`, `handoff_event`,
  `messaging_audit_event`, `delivery_audit`). Reading audit is itself audited.

## 1. Staff identity, membership and privileged access

| Table | Data (plain words) | Who can read (role keys) | Append-only | Tenant export | Retention | Audit events |
|---|---|---|---|---|---|---|
| `user_identity` | Staff display name and subject, active/deactivated status | `tenant_admin` (STAFF_READ); a subject's own identity via `active_roles()` / membership fold | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (STAFF_ADMIN) |
| `membership` | Staff membership events and effective dates | `tenant_admin` (STAFF_READ); own membership via `current_membership()` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (STAFF_ADMIN) |
| `role_assignment` | Role grants, grantor and effective dates | `tenant_admin` (STAFF_READ/STAFF_HISTORY); own roles via `active_roles()` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (STAFF_ADMIN) |
| `staff_invitation` | Invited staff subject, display name, roles and token hash | `tenant_admin` | No — used/revoked flags updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (STAFF_ADMIN) |
| `role_change_history` | Immutable record of staff role changes | `tenant_admin` (ROLE_HISTORY_READ) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (ROLE_HISTORY); `role_change_history` |
| `emergency_access_grant` | Subject, facility, purpose and reason for time-limited emergency access, with review | `clinician` (own grant); `privacy_auditor`, `tenant_admin` (review) | No — revoke/review updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (EMERGENCY_ACCESS) |
| `support_access_incident` | Subject, reason and approval of platform support access | `support_agent`, `privacy_auditor`, `tenant_admin` | No — approval status updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (SUPPORT_ACCESS) |

## 2. Patient identity, account proofing and record access

| Table | Data (plain words) | Who can read (role keys) | Append-only | Tenant export | Retention | Audit events |
|---|---|---|---|---|---|---|
| `patient_record` | Names (original and normalized), MRN and issuer, date of birth or unknown reason, language, contacts, identifiers, consents | `reception` (PATIENT_READ/SEARCH); `clinician` and `clinical_assistant` (CHART_READ); `tenant_admin`, `privacy_auditor` (identity admin / duplicate review) | Yes — a correction is a new version | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (PATIENT_ADMIN, PATIENT_IDENTITY) |
| `patient_account` | Verified patient account subject and display name | `patient` (self); `reception`, `tenant_admin` (enrol) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (PATIENT_ADMIN) |
| `account_invitation` | Single-use enrollment/link/reverify token hash and chart id | `reception`, `tenant_admin` (issue); `patient` (redeem) | No — `used_at` updated atomically | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (PATIENT_IDENTITY) |
| `account_chart_link` | Link/unlink events between an account and an immutable chart id, with verifier and reason | `patient` (own); `tenant_admin`, `privacy_auditor` (LINK_READ) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (PATIENT_IDENTITY) |
| `duplicate_review` | Patient ids of a same-tenant duplicate pair and opener | `tenant_admin`, `privacy_auditor` (DUPLICATE_REVIEW) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (IDENTITY_ADMIN) |
| `chart_association` | Versioned alias→survivor chart visibility with preserved MRN, issuer, identifiers and consents | `tenant_admin`, `privacy_auditor` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (IDENTITY_ADMIN) |
| `consolidation` | Approval of a chart consolidation, approver, reason and correction reason | `tenant_admin`, `privacy_auditor` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (IDENTITY_ADMIN) |
| `consolidation_dependency` | Ambiguous orders, patient links or grants that block consolidation | `tenant_admin`, `privacy_auditor` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (IDENTITY_ADMIN) |
| `patient_access_account` | Patient account state, linked chart ids, failed attempts and digests of contact/fact data | `patient` (self); `tenant_admin`, `privacy_auditor` | No — state/attempts updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `patient_access_event`, `audit_event` |
| `patient_access_invitation` | Invitation/recovery code hash, chart id, expiry and attempts | `patient` (redeem); `reception`, `tenant_admin` (invite); `privacy_auditor`, `tenant_admin` (recovery) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `patient_access_event` |
| `patient_access_token` | Hashed recovery/access token and purpose | `patient` (self); `privacy_auditor`, `tenant_admin` (recovery) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `patient_access_event` |
| `patient_access_support_case` | Identity-dispute/access support case with patient id, summary and assignee | `patient` (own); `support_agent`, `privacy_auditor`, `tenant_admin` (SUPPORT_READ) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `patient_access_event` |
| `patient_access_event` | Append-only access, recovery and audit facts (no clinical payload) | `privacy_auditor`, `tenant_admin` (PATIENT_AUDIT_READ) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `patient_access_event` (self-auditing) |
| `patient_access_notification` | Generic notification dispatch log | `tenant_admin`, `privacy_auditor` (audit) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `patient_access_event` |

NOTE: `patient_access_*` tables store fact and contact *digests*, not raw contact
values, so they hold less personal data than the V7 `patient_record` contacts
column.

## 3. Scheduling, check-in and worklists

| Table | Data (plain words) | Who can read (role keys) | Append-only | Tenant export | Retention | Audit events |
|---|---|---|---|---|---|---|
| `appointment` | Patient id, facility, clinician/room, visit type, start/end, state and override reason | `tenant_admin`, `reception`, `clinician` (APPOINTMENT_READ); `patient` (own appointments) | No — state transitions in place, no delete | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event`, `appointment_event` |
| `appointment_event` | Immutable appointment state history with reason, actor and prior times | Same as `appointment` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `appointment_event` |
| `check_in_verification` | Check-in method and identifier *categories* (not values), verifier and time | `reception`, `tenant_admin` (IDENTITY_VERIFY) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event`, `check_in_verification` |
| `worklist_entry` | Patient id, facility, priority and current assignee | `reception`, `clinical_assistant`, `clinician`, `tenant_admin` (WORKLIST_READ) | No — assignment/priority updated, no delete | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event`, `handoff_event` |
| `handoff_event` | Immutable assignment/transfer history with reason and actor | Same as `worklist_entry` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `handoff_event` |
| `follow_up_suggestion` | Patient id, suggested follow-up date, rule explanation and override actor/reason | `clinician` and assigned care-team roles; `tenant_admin` | No — confirmed/override updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (FOLLOW_UP) |
| `care_team_task` | Task owner, due date, instruction and outcome | `clinician`, care-team roles, `tenant_admin` | No — status/outcome updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (FOLLOW_UP) |

## 4. Clinical chart, documents, reports and therapy

| Table | Data (plain words) | Who can read (role keys) | Append-only | Tenant export | Retention | Audit events |
|---|---|---|---|---|---|---|
| `clinical_note_revision` | Note content, language, author and data-entry subjects, signer attestation, effective/signed time and checksum | `clinician`, `clinical_assistant` (CHART_READ) | Yes — signed revisions immutable, amendments are new revisions | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (CLINICAL_DOC) |
| `document_association` | File name, type, size, original bytes, checksum, rotation and void status | `clinician`, `reception` (DOCUMENT_READ) | No — status/version/void_reason updated, no delete | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (DOCUMENT_READ), `document_association_event` |
| `document_association_event` | Immutable link/void history for a document | `clinician`, `reception` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `document_association_event` |
| `rendered_document` | Generated PDF bytes, locale, direction, source revision and checksum | `clinician`, `reception` (DOCUMENT_READ); `patient` (released copy) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (DOCUMENT_READ) |
| `report_chart_link` | Verified patient-to-report visibility link | `clinician`, `tenant_admin`, `reception`, `privacy_auditor`; `patient` (released reports) | No — link state updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (PATIENT_REPORT_ACCESS) |
| `report_release_revision` | Released report revision, issuing org, effective date, language, document id and checksum | Same as `report_chart_link` | No — release state updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (RELEASE_ADMIN), `release_event` |
| `report_release_history` | Immutable hold/withdraw/correct history of a release | Same as `report_chart_link` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `report_release_history`, `audit_event` |
| `therapy_episode` | PT episode state, patient, referral, referrer and clinical question | `pt_clinician`, `pt_coordinator` (THERAPY_READ); `tenant_admin`, `privacy_auditor` | No — state updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `therapy_audit_event`, `audit_event` |
| `therapy_evaluation` | Evaluation author, recorded time, frequency and duration | `pt_clinician`, `pt_coordinator` | No — updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `therapy_audit_event` |
| `therapy_baseline_measurement` | Baseline measurement code, value, unit and observation time | `pt_clinician`, `pt_coordinator` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `therapy_audit_event` |
| `therapy_goal` | Therapy goal description, target and target date | `pt_clinician`, `pt_coordinator` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `therapy_audit_event` |
| `therapy_outcome_reading` | Outcome scale value, unit and observation time | `pt_clinician`, `pt_coordinator` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `therapy_audit_event` |
| `therapy_intervention` | Intervention description, time and author | `pt_clinician`, `pt_coordinator` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `therapy_audit_event` |
| `therapy_clinical_note` | Signed PT note content, author, attestation and checksum | `pt_clinician`, `pt_coordinator` | No — signed state updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `therapy_audit_event`, `audit_event` |
| `therapy_summary` | Approved referral summary content and send time | `pt_clinician`, `pt_coordinator`; referrer via exchange | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `therapy_audit_event` |
| `therapy_return_acknowledgment` | Referrer acknowledgment of a summary | `pt_clinician`, `pt_coordinator`; referrer | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `therapy_audit_event` |
| `therapy_audit_event` | Append-only PT access/mutation facts | `privacy_auditor`, `tenant_admin` (AUDIT_READ) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `therapy_audit_event` (self-auditing) |
| `specialty_attestation` | Patient, action, role, subject and decision for specialty clinical attestations | `lab_collector`, `lab_verifier`, `radiology_staff`, `radiology_interpreter`, `pt_clinician`, `pharmacy_staff`, `clinician` (ATTESTATION_READ); `tenant_admin`, `privacy_auditor` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (SPECIALTY_ATTEST) |

## 5. Orders, results, critical results and release

| Table | Data (plain words) | Who can read (role keys) | Append-only | Tenant export | Retention | Audit events |
|---|---|---|---|---|---|---|
| `exchange_authorization` | Order code, destination org, purpose and permitted duration | `tenant_admin`, `clinician` (EXCHANGE_READ) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event`, `exchange_task_transition` |
| `exchange_order` | Patient id/encounter, order code, priority, clinical question, destination and responsible clinician | `tenant_admin`, `clinician`, `lab_collector`, `reception` (EXCHANGE_READ) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event`, `exchange_task_transition` |
| `exchange_order_snapshot` | Immutable released order content and fingerprint | Same as `exchange_order` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` |
| `exchange_disclosure` | Recipient org, purpose, duration and fingerprint of a disclosure | `tenant_admin`, `privacy_auditor`, `clinician` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event`, `exchange_disclosure` |
| `exchange_work_item` | Destination work item with local patient id/MRN, verification and clinical question | `lab_collector`, `lab_verifier`, `radiology_staff`, `pt_clinician`, `pharmacy_staff`, `clinician`, `tenant_admin`, `privacy_auditor` | No — state updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` |
| `exchange_task` | Workflow/transmission/review states and reviewer revision | `tenant_admin`, `clinician`, `lab_collector`, `reception` (EXCHANGE_READ) | No — versioned state updates, no delete | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event`, `exchange_task_transition` |
| `exchange_task_transition` | Immutable transition history with actor and reason | Same as `exchange_task` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `exchange_task_transition` |
| `xch03_order` | Recipient-side order with local patient id, assignee, question and permitted purpose | `lab_collector`, `lab_verifier`, `radiology_staff`, `pt_clinician`, `pharmacy_staff`, `clinician`, `reception`, `tenant_admin`, `privacy_auditor` (EXCHANGE_READ) | No — state updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event`, `xch03_event` |
| `xch03_revision` | Immutable revision lineage of a recipient order | Same as `xch03_order` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `xch03_event` |
| `xch03_retained_copy` | Retained recipient copy: checksum, permitted purpose, retention policy and legal hold | Same as `xch03_order` | Yes | NOT YET: TASK-EXPORT | Retention/legal-hold fields are *declared* here, no enforcement job (see TASK-RETENTION) | `xch03_event`, `audit_event` |
| `xch03_event` | Immutable recipient order event history | Same as `xch03_order` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `xch03_event` |
| `xch03_notification` | Notification target, channel and outcome | `tenant_admin`, `privacy_auditor`, owning unit roles | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `xch03_notification`, `xch03_event` |
| `xch04_expected_order` | Expected order with patient id, accession, owner and coverage queue | `lab_verifier`, `clinician`, `tenant_admin`, `privacy_auditor` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` |
| `xch04_report_revision` | Imported report revision with patient, accession, kind, effective time and checksum | `lab_verifier`, `clinician` (RESULT_READ); `tenant_admin`, `privacy_auditor` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event`, `xch04_review` |
| `xch04_reconciliation` | Unmatched/conflicting report details and status | `lab_verifier`, `tenant_admin`, `privacy_auditor` (RECONCILIATION_READ) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` |
| `xch04_notification` | Recipient notification and superseded-version links | `lab_verifier`, `clinician`, `tenant_admin`, `privacy_auditor` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `xch04_notification` |
| `xch04_review` | Clinical review/acknowledgment state of a report revision | `lab_verifier`, `clinician` (REVIEW_ACK) | No — review state updated, no delete | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `xch04_review`, `audit_event` |
| `critical_result` | Patient, result summary, observed value and unit, owner, due time and contact channels | `clinician`, `tenant_admin`, `privacy_auditor` (CRITICAL_READ) | No — escalation/ack state updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event`, `critical_result_event` |
| `critical_result_event` | Immutable escalation/acknowledgment events with recipient and channel | Same as `critical_result` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `critical_result_event` |
| `trend_observation` | Patient, test, value/qualifier, unit, effective time and interpretation | `clinician` (TREND read) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` |
| `lab_accession` | Accession number, patient, specimen type/site, collector and collection times | `lab_collector`, `lab_verifier` (LAB_ACCESSION_READ) | No — lifecycle updated via guard | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (LAB_ACCESSION), `lab_accession_event` |
| `lab_accession_event` | Immutable specimen lifecycle with matched patient and actor | `lab_collector`, `lab_verifier` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `lab_accession_event` |
| `patient_release` | Patient, revision, issuing org, policy version, state, hold and review | `clinician`, `tenant_admin`, `reception`, `privacy_auditor` (RELEASE_READ/HISTORY) | No — release state updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (RELEASE_ADMIN), `release_event` |
| `release_event` | Immutable release state history | Same as `patient_release` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `release_event` |
| `release_notification` | Notification event, recipient scope and correlation id | `tenant_admin`, `privacy_auditor`, `clinician` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `release_event`, `audit_event` |

## 6. Prescribing and pharmacy

| Table | Data (plain words) | Who can read (role keys) | Append-only | Tenant export | Retention | Audit events |
|---|---|---|---|---|---|---|
| `patient_allergy_status` | Patient allergy status and source | `clinician`, `tenant_admin`, `privacy_auditor` (CHECK_ALLERGIES) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` |
| `patient_allergy` | Patient allergy substance/group, verification state and effective date | `clinician`, `tenant_admin`, `privacy_auditor` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` |
| `prescription_draft` | Patient, selected drug, dose/route/frequency/quantity, instructions and prescriber | `clinician`, `tenant_admin`, `privacy_auditor` (RX_READ_DRAFT) | No — draft state updated, no delete | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` |
| `prescription_override` | Allergy/interaction override reason, matched snapshot and author | `clinician`, `tenant_admin`, `privacy_auditor` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` |
| `rx02_prescription` | Prescription details, prescriber, destination pharmacy, channel and submission id | `clinician`, `pharmacy_staff`, `tenant_admin`, `privacy_auditor` (RX_READ) | No — transmission state updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event`, `rx02_event` |
| `rx02_submission` | Stable submission id, channel and creator | Same as `rx02_prescription` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `rx02_event` |
| `rx02_event` | Immutable transmission/status event history | Same as `rx02_prescription` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `rx02_event` |

## 7. Finance

| Table | Data (plain words) | Who can read (role keys) | Append-only | Tenant export | Retention | Audit events |
|---|---|---|---|---|---|---|
| `finance_invoice` | Patient, currency, totals, balance, service reference and void details | `finance`, `tenant_admin`, `privacy_auditor` (FINANCE_READ/RECONCILE) | No — invoice state/balances updated, no delete | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event`, `finance_ledger_entry` |
| `finance_invoice_item` | Line description, quantity and amount | Same as `finance_invoice` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `finance_ledger_entry` |
| `finance_ledger_entry` | Immutable payment/refund/credit entry with reference, reason, approver and actor | Same as `finance_invoice` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `finance_ledger_entry`, `audit_event` |
| `finance_receipt_counter` | Receipt numbering counter (no direct patient data, but linked to receipts) | `finance`, `tenant_admin` | No — counter advanced | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` |

No card data, payer data or accounting-ledger content is stored (PRD-04).

## 8. Patient communication, attachments and messaging governance

| Table | Data (plain words) | Who can read (role keys) | Append-only | Tenant export | Retention | Audit events |
|---|---|---|---|---|---|---|
| `care_unit` | Unit name, topics, facility and active flag (directory, not patient data) | `patient` (eligible units), `tenant_admin`, `privacy_auditor` (UNIT_LIST) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event`, `messaging_audit` |
| `eligible_relationship` | Patient id, unit and verified relationship/context used to connect | `patient` (own), `tenant_admin`, `privacy_auditor`; assigned unit staff | No — consumed/active flags updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `messaging_audit`, `audit_event` |
| `patient_unit_connection` | Patient-to-unit connection state, verifier and revocation | `patient` (own), `tenant_admin`, `privacy_auditor`; unit staff via connection | No — state/revocation updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `messaging_audit`, `messaging_revocation_effect` |
| `patient_link` | Subject-to-patient link used by the patient app | `patient` (own), `tenant_admin`, `privacy_auditor` | No — active flag updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `messaging_audit`, `audit_event` |
| `conversation` | Conversation patient/subject, unit, topic, state and assignee | `patient` (own); unit staff by unit (`reception`, `clinical_assistant`, `clinician`, `lab_collector`, `lab_verifier`, `radiology_staff`, `radiology_interpreter`, `pt_clinician`, `pharmacy_staff`); `tenant_admin` (work queue); `privacy_auditor` (audit) | No — state/assignment updated, no delete | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (MSG_GOVERNANCE), `work_item_event` |
| `conversation_message` | Message body with sender kind, subject, role and sequence | Same as `conversation` for that thread | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `messaging_audit_event` (facts only), `audit_event` |
| `work_item_event` | Immutable work queue state history with actor and reason | Same as `conversation` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `work_item_event` |
| `attachment` | Patient-supplied or staff file metadata (name, type, size, checksum, object key), scan verdict and state | `patient`, `clinician`, `reception`, `lab_collector`, `lab_verifier`, `radiology_staff`, `pt_clinician`, `pharmacy_staff`, `privacy_auditor`, `tenant_admin` (ATTACHMENT_READ); `security_scanner` (scan) | No — verdict/state updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (MSG_GOVERNANCE), `attachment_event` |
| `attachment_connection_grant` | Per-connection file access grant | Same as `attachment` | No — active flag updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `attachment_event` |
| `attachment_event` | Immutable upload/scan/publish/download/file event history | `tenant_admin`, `privacy_auditor`, unit roles | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `attachment_event` |
| `messaging_audit` | Append-only messaging audit facts (no clinical body) | `tenant_admin`, `privacy_auditor` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `messaging_audit` (self-auditing) |
| `messaging_audit_event` | MSG-07 governance facts: open/view/send/assign/download/acknowledge/file/correct/restrict/export/hold/chart-link/copy/retention/audit-read | `tenant_admin`, `privacy_auditor` (governance); `clinician` (file/chart link); `patient` (own conversation facts) | Yes | Scoped, expiring export record for synthetic messaging resources (V62 `messaging_export`) | Retention policy/decision/apply implemented for synthetic messaging resources (V62, `messaging/governance/`) | `messaging_audit_event` (self-auditing, `audit_read` on read) |
| `messaging_resource` | Synthetic messaging resource reference used by retention/hold/export | `tenant_admin`, `privacy_auditor` | Yes — populated by migration, no app write | Scoped export record only | Retention decisions/apply for this family only | `messaging_audit_event` |
| `messaging_retention_policy` | Approved retention window per messaging resource type | `tenant_admin`, `privacy_auditor` | No — policy updated, no delete | NOT YET: TASK-EXPORT | Implemented for messaging resources only | `messaging_audit_event` |
| `messaging_retention_duty` | Distinct sender/recipient retention duties for a resource | `tenant_admin`, `privacy_auditor` | No — updated, no delete | NOT YET: TASK-EXPORT | Implemented for messaging resources only | `messaging_audit_event` |
| `messaging_legal_hold` | Legal hold reason, placer and lift metadata for a resource | `tenant_admin`, `privacy_auditor` | No — hold lifecycle updated, no delete | NOT YET: TASK-EXPORT | Legal hold blocks messaging retention/destruction only | `messaging_audit_event` (hold_place/hold_release) |
| `messaging_record_access_policy` | Whether prior conversations stay readable after revocation, with policy version | `tenant_admin`, `privacy_auditor` | No — updated, no delete | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `messaging_audit_event` |
| `messaging_connection` | Tenant-scoped connection reference with revocation time | `tenant_admin`, `privacy_auditor` | No — revocation updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `messaging_audit_event` |
| `messaging_provenance_link` | Source conversation/message/chart provenance for material filed in a chart (ids and revision, no clinical payload) | `clinician` (chart link), `tenant_admin`, `privacy_auditor` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `messaging_audit_event` (chart_link) |
| `messaging_export` | Scoped, expiring export record (id, scope, creator, expiry) | `tenant_admin`, `privacy_auditor` | Yes | This is the export metadata record, not a live export pipeline | NOT YET: TASK-RETENTION | `messaging_audit_event` (export) |
| `messaging_copy_request` | Copy request routed to the site record-access procedure | `patient`, `tenant_admin`, `privacy_auditor` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `messaging_audit_event` (copy_request) |
| `messaging_revocation_effect` | Recorded consequences of revoking a connection (messaging/attachment blocked, readability) | `tenant_admin`, `privacy_auditor`, `patient` (own) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `messaging_audit_event` (restrict) |
| `flow_access_audit` | Append-only patient-flow access facts with purpose and correlation id | `tenant_admin`, `privacy_auditor` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `flow_access_audit` (self-auditing) |

## 9. Durable delivery / outbox

These rows carry aggregate, order and patient **ids** and payload digests, not
clinical bodies, but they can link a person to an external delivery.

| Table | Data (plain words) | Who can read (role keys) | Append-only | Tenant export | Retention | Audit events |
|---|---|---|---|---|---|---|
| `delivery_outbox` | Aggregate id/version, event type, payload digest, idempotency key and attempt state | `worker`, `integration_admin`, `operator`, `tenant_admin` (review/replay) | No — attempt/state updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` (DELIVERY), `delivery_audit` |
| `delivery_receipt` | External event id, aggregate/order/patient ids, outcome and detail | `worker`, `integration_admin`, `operator`, `tenant_admin`, `privacy_auditor` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `delivery_audit` |
| `delivery_dead_letter` | Failed event id, reason, attempts and reviewer resolution | `integration_admin`, `operator`, `tenant_admin` (REVIEW) | No — review fields updated | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `delivery_audit` |
| `delivery_replay` | Replay of an original event with reason and authorizer | `integration_admin`, `tenant_admin` (REPLAY) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `delivery_audit` |
| `delivery_link` | External event linked to order/patient with linker | `clinician`, `integration_admin`, `tenant_admin` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `delivery_audit` |
| `delivery_audit` | Append-only delivery facts with purpose, correlation and outcome | `tenant_admin`, `privacy_auditor` (AUDIT_READ) | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `delivery_audit` (self-auditing) |

## 10. Central audit and import provenance

| Table | Data (plain words) | Who can read (role keys) | Append-only | Tenant export | Retention | Audit events |
|---|---|---|---|---|---|---|
| `audit_event` | Actor, purpose, scope, action, resource id, correlation id, outcome and hash chain for protected reads and writes | `tenant_admin`, `privacy_auditor` (AUDIT_READ); other roles only through their scoped services | Yes — tamper-evident hash chain, no update/delete/truncate for any role | NOT YET: TASK-EXPORT (audit history is an input to the G5 export manifest) | NOT YET: TASK-RETENTION (audit retention is a policy decision, not yet a job) | `audit_event` (self-auditing `audit_read`) |
| `import_record_provenance` | Source system/id/version, manifest version, historical signature and new-attestation flag for onboarded records | `tenant_admin`, `integration_admin` | Yes | NOT YET: TASK-EXPORT | NOT YET: TASK-RETENTION | `audit_event` |

## Tables that do not hold personal or clinical data

For completeness, these are **not** in the scope of the personal-data inventory:
`organization`, `facility`, `visit_type`, `scheduling_resource`,
`resource_availability`, `config_entry`, `config_approval`, `flow_policy`,
`flow_approved_notice`, `drug_catalog_entry`, `medication_catalog_version`,
`therapy_outcome_scale` (an approved, versioned outcome-scale catalog used by
`therapy_outcome_reading`), `trend_comparability`, `finance_receipt_counter`
(counter only) and the
messaging-governance policy/hold metadata tables that reference resources rather
than people. `scheduling_resource.display_name` names a clinician or room — it is
a workforce label rather than a patient record, but a privacy officer may still
wish to treat it as staff personal data; it is listed here for that decision.

## Gaps a privacy officer must decide on (summary)

- **Tenant export (OPS-07):** no live pipeline exports the tables above. The only
  export implementation is a scoped, expiring record for synthetic messaging
  resources (V62 `messaging_export`); the G5 harness only validates artifact
  shape. Suggested task: **TASK-EXPORT** (title in the legend).
- **Retention and legal hold (OPS-07):** no live job deletes active data or
  expires backups for the tables above. Retention decisions/apply and holds work
  only for the synthetic messaging-resource family. `xch03_retained_copy`
  declares a retention policy and legal-hold field but has no enforcement job.
  Suggested task: **TASK-RETENTION** (title in the legend).
- **Retention periods themselves:** OPS-01 requires the approved policy pack to
  set jurisdiction-specific periods and owners; this repository contains no
  approved period values, so none are asserted here (`NOT YET:` — owner
  decision, not an engineering gap).
- **Release to patients (PAT-02):** released-report visibility is enforced by
  the release store and the V10 chart-link fold; withdrawal and correction
  history is append-only. Exporting a patient's own copy still depends on
  TASK-EXPORT.
- **Real clinical and human evidence:** every control above is synthetic,
  engineering evidence. Clinical approval, signed market policy packs and real
  record-request rehearsals remain G5 owner sign-offs (section 10 of the
  specification), not something this inventory can supply.

## Evidence sources

- Schema, keys, RLS, grants and append-only triggers: every file in
  `db/migrations/V1__tenant_schema.sql` … `V90__onboarding_import.sql`.
- Retention/export implementation:
  `apps/api/src/main/java/com/aurevia/messaging/governance/`
  (`MessagingGovernanceService`, `MessagingGovernanceController`,
  `MessagingAuditContract`, `MessagingGovernanceActions`), V62 migration, and
  `infra/s_g5_08/tenant_lifecycle_evidence.py` (synthetic artifact evaluator).
- Role keys and authorization boundaries: the `*Actions.java` / `*Policy.java`
  files listed in "How to read the tables", plus `tenant/TenantContext.java`
  (purpose `FACILITY_ADMIN` and request scope).
- Audit purposes: constants in the service classes (for example
  `STAFF_ADMIN`, `PATIENT_IDENTITY`, `CLINICAL_DOC`, `DOCUMENT_READ`,
  `LAB_ACCESSION`, `RELEASE_ADMIN`, `MSG_GOVERNANCE`, `DELIVERY`) and the V3
  `audit_event` hash chain.
- Requirement IDs: `PROJECT_V1_SPEC.md` sections 6 (PAT-03), 7 (ARC-02,
  ARC-03) and 8 (OPS-01, OPS-02, OPS-07).
