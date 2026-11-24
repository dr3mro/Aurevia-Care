# Aurevia Cloud V1
## Product, clinical safety and engineering specification

**Version 1.0, revision 1 · November 18, 2026 · Implementation baseline**

“Must” marks a V1 requirement. Clinical and policy owners approve site configuration before use with real patients. Requirement IDs are stable backlog and test references. Start development with synthetic data. Build and test all V1 workflows. Launch clinic, laboratory and patient access first, then enable each specialty per organization when its acceptance and operating prerequisites pass.

## 1. Product and boundaries

Aurevia Cloud is a hosted ambulatory electronic medical record, reporting and care coordination platform. Staff use an authenticated web portal. Patients use an iOS or Android app. Clinics deliver and document care, independent laboratories and radiology practices fulfill orders, physical therapy practices fulfill referrals, and pharmacies coordinate supported prescription events. Organizations may combine service lines across multiple facilities. The platform records which organization is responsible for each clinical action and retains the provenance of shared data.

| ID | V1 outcome | Boundary |
|---|---|---|
| PRD-01 | One longitudinal chart per patient per organization, covering registration, booking, check-in, clinical documentation and follow-up | Ambulatory care only |
| PRD-02 | Closed-loop orders, diagnostic results, specialty referrals and ordered prescription communication | Partner-specific interfaces require approval. Manual workflows must clearly identify delivery and verification evidence. |
| PRD-03 | Verified adult patients can see appointments and released reports, obtain copies, manage notification preferences, and securely exchange messages, files and instructions with connected care units | Self-access only. Staff handle proxy, dependent and other record requests through an approved manual process. |
| PRD-04 | Track self-pay invoices, payments, refunds and receipts | No claims adjudication, payer integrations, card data storage or accounting ledger |
| PRD-05 | Support onboarding, export, monitoring, recoverability, and English and Arabic operation | One market per production deployment, with a market-specific policy pack |
| PRD-06 | Clinical users decide and attest to diagnosis, treatment and release | No autonomous care decisions, diagnostic image viewer, in-house PACS, controlled electronic prescribing, pharmacy inventory, full dispensing system, inpatient workflows or offline clinical editing |

Pilot success metrics. Measure over the first 30 live days with per-site dashboards and named owners. All critical alerts must have an owner, deadline and escalation trail. Target at least 95% of final diagnostic reports reconciled by the configured due time and at least 95% of eligible approved releases visible to the patient within five minutes. Count from authoritative status events, including overdue and failed cases. Review safety incidents and delays weekly. A launch-blocking safety failure overrides adoption metrics.

## 2. Identity, permissions and organizational administration

| ID | Requirement |
|---|---|
| IAM-01 | Maintain Organization, Facility, UserIdentity, Membership and RoleAssignment. Tenant is the organization. Deny access without active membership, the required action permission and domain authorization for the resource. Facility scope may further narrow access. Users explicitly switch active organization. Every write checks current scope. |
| IAM-02 | Invite and deactivate staff, assign bounded roles, configure locations and retain immutable membership history. Record who granted each role and its effective dates. Permission changes take effect on the next protected request. API calls using expired or removed membership fail closed. |
| IAM-03 | Record PatientRecord and issuer-scoped MRN per organization. Normalize search while preserving original spelling. Registration collects names, date of birth or a coded unknown reason, contacts, language, identifiers with issuer and verification state, and applicable consent records. Warn on likely duplicates. Confirm two identifiers at high-risk transitions when available, including collection and chart consolidation. Document the approved alternate process when identifiers are unavailable. Shared phone or names and dates alone cannot link records or patient accounts. |
| IAM-04 | Provide a same-tenant duplicate review worklist. Show both identities, conflicting demographics, dependencies, and a dry run. A second authorized reviewer approves consolidation. Move chart visibility through a versioned association with the surviving record. Preserve original identifiers, signed artifacts, signatures and source references. Lock both records during commit. Block ambiguous orders, patient-account links or grants until resolved. Use an audited support workflow to correct erroneous consolidation. |
| IAM-05 | Patient identity proofing uses site-verified enrollment plus expiring single-use invitations and re-verification for lost access or disputed identity. A verified patient account may link to several independently verified charts. Unlinking immediately ends future account access for the affected chart. A staff contact change does not change account ownership. |
| IAM-06 | Record business purpose, role, facility and user for sensitive actions. Emergency access, where approved, is limited to qualified clinicians, scoped inside their own organization, time-limited, reasoned, alerted and reviewed. It cannot unlock other organizations’ data. Platform support access to clinical data is disabled by default, with a separate approved and audited incident procedure. |

| Role | Allowed workflow | Scope constraint |
|---|---|---|
| Reception | Register, schedule, check in and manage patient contact data | Assigned facilities and demographic/scheduling fields |
| Clinical assistant | Intake, vitals, reconciliation preparation and queue handoff | Assigned encounters or care team |
| Clinician | Document and sign, order, prescribe where authorized, reconcile and release | Care relationship, privileges and facility |
| Lab collector / verifier | Accession and collect / verify, release and correct results | Assigned requisitions. Site privileges determine separation of duties. |
| Radiology staff / interpreter | Schedule and associate study / sign interpretation and addenda | Assigned referrals and verified imaging privileges |
| PT clinician | Accept referrals, author plans and notes, sign progress and discharge | Assigned episode |
| Pharmacy staff | Review routed items, communicate, record status evidence | Explicitly routed prescriptions |
| Finance | Invoice, record payment and request or approve adjustments | Financial fields and service references |
| Tenant admin / privacy auditor | Organization configuration / audit and disclosure review | Clinical access follows separate clinical permissions |
| Patient | Self-access, download eligible documents and manage preferences | Verified linked chart, active release or authorized conversation, according to the action |

## 3. Clinic and clinical records

| ID | Requirement |
|---|---|
| CLN-01 | Model facility/time zone, clinician and room/resource availability, visit duration, recurrence templates, holds and configurable booking rules. Prevent overlap for protected resources transactionally. Reschedule atomically and retain history. States are booked → checked in → in progress → completed, with cancelled or no-show branches. Record reason and actor for overrides, cancellation and no-show. |
| CLN-02 | Reception verifies identity and checks in. Staff see an organization/facility worklist ordered by scheduled time and configurable priority, with assignment and timestamps. Transfer handoff requires an active assignee. Only eligible roles can record vitals or open the encounter. Link unscheduled encounters to intake. |
| CLN-03 | Capture chief concern, history, exam, assessment/diagnoses, plan, vitals with units and measurement time, and longitudinal problems, allergies and medications. Track verification, source and effective dates, including explicit unknown and none states. Record clinical author separately from data entry actor. Support local procedure codes and a versioned market diagnosis catalog. Retain original code and text after mapping. |
| CLN-04 | Provide versioned SOAP, operative, OB/GYN, perinatal and pediatric development templates. Operative notes include procedure, date, team, findings, complications and follow-up. OB/GYN captures LMP and structured obstetric history from the approved site dictionary. Perinatal captures birth and neonatal history. Development captures milestone domain, observation and date. Clinical governance approves field dictionaries and requiredness. Allow free text with language, and preserve historical field meanings with each saved version. |
| CLN-05 | Draft → signed, with separate amendment drafts and signed addenda. Require sign permission, signer attestation, correct patient context, draft version and required fields. Save signer, effective time, signed time, content checksum and template version. Signed revisions cannot be edited. Entered-in-error preserves the prior revision and records actor, reason and affected recipients. Signing differs from patient release and PDF rendering. |
| CLN-06 | Suggest a follow-up date using configured visit type intervals and applicable prior follow-up. Explain the rule and allow authorized override with provenance. Only explicit confirmation creates a booking. Care-team action tasks track owner, due date and outcome. |
| CLN-07 | Link original PDFs and images to chart, order or result as authorized. Retain original bytes and checksum. Image rotation is presentation metadata. Voiding an association captures a reason and preserves history. Generate immutable versioned PDFs in English or Arabic with embedded fonts, direction-aware layout, page numbers, provenance and print profile. |

## 4. Closed-loop order exchange and results

| ID | Requirement |
|---|---|
| XCH-01 | An order includes local patient and encounter, order code, priority, clinical question, destination, source actor and responsible clinician/work queue. The sending organization authorizes the specific payload, recipient, purpose and duration. Snapshot the released content, stamp identifiers and versions, and record disclosure. The receiver sees an exchange-specific work item, with a locally verified patient association where needed. Sharing an order grants no general chart access. |
| XCH-02 | Use separate internal states for workflow (requested → accepted → processing → completed, with rejected/cancelled branches), transmission (pending → sent → delivered or failed), and clinical review (unreviewed → acknowledged → action pending → resolved). Acknowledgment is made by an authorized human with timestamp and revision. A transmission acknowledgment cannot close a clinical task. |
| XCH-03 | The recipient accepts, rejects with reason, or requests clarification. Changes after acceptance create a linked version or cancellation request, and require recipient response. Capture retained recipient copies and permitted purposes. Revocation blocks future sender-hosted retrieval and records downstream notification. Apply law and policy to recipient retention. |
| XCH-04 | Route every final, corrected and critical result to a named owner and coverage queue. Match patient, source, accession, destination and order identifiers before import. Unmatched or conflicting reports enter a reconciliation queue. Corrections preserve prior versions, notify prior recipients where appropriate, and reopen review even after acknowledgment. |
| XCH-05 | For critical findings, require verification, responsible owner, due time and escalation policy. Track attempts, recipient, read-back where required, acknowledgment and follow-up as separate events. Use site-approved backup contact channels if an alert is overdue or the system is down. Delivery and patient release do not satisfy clinician acknowledgment. Report overdue items, roster gaps and failed notification attempts. |
| XCH-06 | Patient release is a separate authorization referencing the immutable revision, release policy version and effective time. Support approved immediate release, timed release, or permitted holds with reason, expiry/review and audit. Handle corrected or withdrawn releases by updating current access, retaining history and notifying affected recipients per policy. |
| XCH-07 | Trend numeric observations only when test identity, units and clinically relevant comparability are established. Display source unit, reference range, time and interpretation. Keep qualifiers such as less-than and greater-than, qualitative results and incompatible units visible without treating them as directly comparable measurements. |

## 5. Specialty workflows

| ID | V1 workflow and integrity rules |
|---|---|
| LAB-01 | Accession a requisition with an issuer-scoped unique ID. Record specimen type, site, collector, collection/receipt time and quality. Match patient and accession at collection and receipt. Rejected specimens retain reason and notification. Recollection creates a linked specimen ID. |
| LAB-02 | Capture numeric, qualified numeric, text and coded observations, with units, range, method when supplied, abnormal flags, instrument/source and verifier. Support preliminary, final, corrected and entered-in-error reports. Verification checks specimen, patient and observations. Approved site reference ranges and critical policies are versioned. Evaluate only eligible comparable results. Trigger XCH-04/05 on relevant issued revisions. |
| RAD-01 | Intake an imaging order, capture urgency and clinical question, schedule, record accession and modality, link the external Study Instance UID and verify patient/accession before association. Use the partner diagnostic workstation. Produce a signed interpretation with technique, comparison, findings, impression and follow-up. Link addenda and critical communications. Images remain under partner access control. |
| PT-01 | Accept a referral into a therapy episode, track evaluation, problems, baseline measurements, goals, planned frequency/duration, interventions, outcomes, progress and discharge. Version outcome scales and store units. Sign session, progress and discharge notes, send approved summaries to the referrer and document return acknowledgment. |
| RX-01 | Create a prescription draft with patient, medication, strength, formulation, dose, route, frequency, quantity, refills where supported, instructions, prescriber and destination. Search a market-approved versioned drug catalog by generic/trade name and formulation. Show tagged catalog alternatives for prescriber review. Check against verified allergy ingredients and surface unknowns and mismatches. Keep override reason, author and matched data. |
| RX-02 | Require prescriber attestation and market entitlement. Route through an approved prescribing channel or produce a locally approved printable prescription. Record the actual channel. Track submitted, delivery confirmed, pharmacy accepted/rejected, clarification, renewal and cancellation events separately. Distinguish dispensing evidence from acceptance. Use stable submission IDs. On ambiguous transmission, verify partner status before retrying. |
| FIN-01 | Generate itemized self-pay invoices in one currency per invoice using fixed precision. Support draft, issued, paid, partially paid and void. Record payment, refund and credit as linked immutable entries, requiring reason and approval according to policy. Apply balance changes transactionally, deduplicate payment references and issue numbered receipts. Reconcile daily totals without exposing clinical narrative to finance roles. |

## 6. Patient access, administration and accessibility

- **PAT-01** List appointments with facility time zone and status, allow confirmation where enabled, and record the timestamp. Prefer push notifications for status changes with generic text. The app retrieves details after authentication. Log invalid device tokens and support device revocation.
- **PAT-02** Show released reports with revision, issuing organization, effective date and correction context. Authorize every download against the linked patient and current release. Protect local credentials with OS-backed storage. Avoid persistent clinical caches. Confirm user intent for saving a local copy.
- **PAT-03** Support privacy-safe enrollment, recovery and account lockout. Require renewed authentication for sensitive account changes. Provide a support channel for identity disputes, accessibility and record access requests.
- **ADM-01** Tenant admins configure facilities, referral partner directory, scheduling policies, templates, catalogs, coverage rosters, critical-result escalation rules and patient release policies. Clinical/market policies require named approval, effective dates, audit and version history. Pilot site onboarding tests each configured channel.
- **ADM-02** Provide operational queues for unsigned encounters, unreconciled reports, unacknowledged critical findings, rejected exchanges, document failures, missing coverage and revoked accounts. Authorized operators assign, resolve, retry or escalate with reason. Exports use explicit scope, expiry and audit.
- **UX-01** Support English and Arabic, RTL direction, local name display and mixed-script clinical text. Store UTC instants with IANA time-zone context and retain civil dates where required. Test daylight-saving transitions, accessibility against WCAG 2.2 AA on staff web, and native screen-reader flows on both mobile platforms. Preserve language and units in exports. Keep presentation styling flexible.

## 6.1 Secure patient-to-unit communication

A care unit is a named, staffed inbox for a clinic, laboratory, radiology practice, physical therapy practice or pharmacy. A connection lets the patient and unit communicate within a defined context. A unit receives its own conversations and files.

| ID | V1 requirement |
|---|---|
| MSG-01 | Show verified patients their eligible care units in the app with organization, facility, unit name and supported topics. Allow a patient to connect through a verified care relationship, an order, referral, prescription or a unit-issued invitation tied to one of these relationships. Validate patient, destination and context on the server at connection time and every protected action. Block arbitrary directory contact, self-linking based on demographics, and sharing a conversation across units. A new recipient requires a separate authorized conversation and explicit disclosure. |
| MSG-02 | Let the patient or an authorized unit staff member open a conversation. Support replies and an inbox with unread counts on mobile, and a role-scoped work queue on the staff portal. Every new inbound patient message creates or reopens a work item. Staff can assign, transfer within the unit or resolve it, with reason and audit. Track awaiting staff, awaiting patient, resolved and closed states. A reply to a resolved thread reopens the work item if the relationship still permits messaging. Closed threads are read-only for authorized viewers. |
| MSG-03 | Allow both sides to attach PDFs and JPEG/PNG images. Before sending, show the recipient and pending files and require explicit send. Bind uploads to the uploader, selected conversation and unit. Validate integrity, size and type and scan in quarantine. A file is accessible only after a clean verdict and authorized message publication. Show scan failures to the sender with removal or replacement options. Preserve originals, checksums and provenance. A file sent to a unit enters its conversation inbox as patient-supplied material. An authorized reviewer explicitly files it in the chart if appropriate. |
| MSG-04 | Let authorized staff send typed patient instructions with title, text, language, author, unit, issue time, optional action due date, and optional secure attachments. Tie instructions to the relevant appointment, order, referral, prescription or encounter when available. Examples include collection, imaging preparation, therapy home instructions, visit follow-up and pharmacy pickup. Use unit-approved templates or approved free text within the sender’s professional scope. Clinical instructions require the configured clinician authorization. Changes after sending create a linked replacement or withdrawal, mark the previous version as superseded, and notify the patient. |
| MSG-05 | Record message publication, notification dispatch, first in-app view and, when requested, explicit patient acknowledgment as separate facts. Bind acknowledgment to the exact instruction revision. Track overdue requested acknowledgments and route them to the owning unit for follow-up. The acknowledgment confirms receipt. Clinical consent, task completion and critical-result acknowledgment use their respective workflows. Use generic push notifications and retrieve full content only after authentication. |
| MSG-06 | Each enabled unit must publish service hours, response targets, appropriate topics and urgent contact instructions. Show these before sending. Require a named queue owner, backup coverage and overdue escalation. Outside hours, show when the unit next handles messages. Surface backlog, unassigned work, failed notifications and overdue responses to unit supervisors. Use the site’s approved urgent and downtime channels for time-sensitive care. |
| MSG-07 | Audit opening, viewing, sending, assigning, downloading, acknowledging, filing, correcting and restricting conversations or files. Apply configured retention, export and legal holds. Preserve provenance when authorized staff link clinically relevant messages or instructions to the chart. Revoking a connection stops new messaging and future attachment access through that connection. Record whether prior conversations remain readable under the verified patient’s record-access policy. Preserve sender and recipient retention duties. Requests for copies follow the site's record access procedure. |

## 7. Architecture and data contracts

| Layer | V1 decision |
|---|---|
| Web and mobile | React, TypeScript and Vite for staff. React Native with Expo for patient iOS/Android. Use generated OpenAPI client types. Server authorizes and validates all commands. |
| Backend | Java LTS, Spring Boot, Spring Security, Spring JDBC and Flyway. Modular monolith plus independently scalable workers from the same codebase. Modules own schema and commands for tenancy, scheduling, chart, reports, exchange, patient communication, specialties, finance and audit. Use explicit interfaces for cross-module changes. |
| Storage | Managed PostgreSQL for authoritative records and transactional outbox. Private object storage for quarantined uploads, approved originals, rendered reports and expiring exports. SQS for asynchronous jobs if deployed on AWS. Initial cloud pattern uses ECS Fargate, RDS and private S3 where approved. Define infrastructure as code. |
| Identity | Managed OIDC. Staff MFA and web HTTP-only secure session cookies with CSRF protection. Mobile authorization code with PKCE. Issue scoped credentials for partners and workers. Rotate and store secrets centrally. |
| Interoperability | FHIR R4 at agreed partner boundaries, mapping ServiceRequest, Observation, DiagnosticReport, Specimen, ImagingStudy, DocumentReference and MedicationRequest as applicable. Use partner-specific profiles and conformance fixtures. Use HL7 v2 order/result messages, DICOMweb and e-prescribing routing only where required by the partner contract. The V1 API is REST with OpenAPI. |

**ARC-01 · Domain keys.** Tenant-owned records have organization ID and opaque ID. Internal foreign keys include organization ID. MRN uniqueness is issuer scoped. Partner identifiers and accession numbers are unique within their defined issuer and type. Key models include Appointment, QueueEntry, EncounterRevision, TemplateVersion, Order, Specimen, Observation, ReportRevision, Signature, PatientRelease, ReviewTask, ExchangeGrant, DeliveryEvent, Attachment, TherapyEpisode, Prescription, Invoice, PaymentEntry, CareUnit, PatientUnitConnection, Conversation, Message, MessageAttachment, InstructionRevision, InstructionAcknowledgment and AuditEvent. Record clinical effective time, ingestion time, author, source system, source ID and version where applicable. Preserve patient attribution independently of merge aliases.

**ARC-02 · Isolation.** Derive tenant context from authentication and current membership. Enforce action and object policy in application services. Use PostgreSQL row-level security with forced enforcement on tenant tables, a non-owner runtime role without bypass, and transaction-local tenant context. Separate migration and runtime roles. Jobs, exports, search, attachments and integrations apply the same policy. Cross-tenant exchange uses explicit, verified sender/recipient relationships and bounded projections. Validate pooled connection reuse and fail closed if context is missing.

**ARC-03 · API.** Use versioned routes under `/api/v1`, cursor pagination with stable tenant-scoped ordering, request/correlation IDs and machine-readable errors. Resource families include patients, appointments, encounters, orders, reports, referrals, prescriptions, releases, files, invoices, patient-unit connections, conversations, messages, instructions and audit events. Use explicit POST commands for sign, correct, acknowledge, release and exchange. Require version preconditions for draft writes and irreversible transitions. Use idempotency keys scoped to tenant, caller and command with a request digest. Same key and payload return the original outcome subject to current authorization. Different payloads using the same key yield conflict. Expired retry keys must still respect durable business uniqueness for external clinical actions.

**ARC-04 · Sample contract.** `POST /api/v1/orders/{id}/accept` requires authenticated destination context, an `Idempotency-Key` and an `If-Match` version. Input contains the validated local requisition association and optional receiving assignee. Commit status, acceptance event and audit together. Respond with order ID, accepted state, new version and timestamp. Use 401 for unauthenticated requests, 404 for inaccessible resources where existence must be concealed, 409 for invalid transitions or reused keys with different payloads, 412 for stale versions, and 422 for invalid business inputs. Apply identical semantics to analogous transitions.

**ARC-05 · Delivery.** Commit state, audit and outbox record atomically where applicable. Workers use immutable event IDs and per-aggregate version. Consumers deduplicate external events inside sender/type namespace, reject or hold out-of-order changes, and record processing outcome. Retry transient errors with bounded backoff. Send failures to a restricted dead-letter queue and review workflow. Replays preserve event identity and need authorization and a reason. Confirm order and patient identifiers before linking external data. Use synthetic contract fixtures, including duplicates, corrections and partial partner outages.

**ARC-06 · Documents.** Validate size, supported type, checksum and scan state; keep quarantine private. Use unique object keys and verified object version. Authorize upload completion and each download, preferring mediated downloads for clinical artifacts. Generate PDFs asynchronously, bind them to immutable revisions, and prevent re-release with incomplete artifacts where a PDF is required. Keep protected clinical payloads out of ordinary operational logs, URLs, analytics and notification content.

**ARC-07 · Patient communication.** A conversation belongs to one recipient unit and one verified patient connection. Store each message with sender identity, sender role, immutable content, server timestamp and conversation sequence. Draft uploads are private and cannot be reused across conversations. Authorize patient access through the connection and staff access through membership, unit assignment and role. Commit publication, audit, queue update and notification outbox together. Use durable client message IDs to deduplicate retries. Keep messages in the authenticated app and portal, including when notifications fail. Expose commands to connect, send, assign, resolve, acknowledge and withdraw or replace instructions.

## 8. Privacy, security, quality and operations

| ID | Requirement and verification |
|---|---|
| OPS-01 | Define hosting region, applicable law, retention and legal hold rules, patient release rules, incident notification, supplier obligations and prescribing constraints in the approved policy pack. For a U.S. launch where HIPAA applies, verify the security risk analysis, required agreements and vendor scope before handling ePHI. Record jurisdiction-specific requirements and owners before live pilot. |
| OPS-02 | Encrypt transport, data, queues and backups. Restrict cloud identity, network access and key use. Enforce least privilege and regular access review. Audit clinical reads, queries, exports, downloads, disclosures, auth, emergency access, mutations and administration. Include actor, purpose, scope, timestamp, action, resource ID, correlation ID and outcome. Keep audit append-only with restricted access and tamper detection. Successful sensitive downloads and disclosures require a durable audit record. |
| OPS-03 | Target 99.9% monthly core availability, p95 ≤ 500 ms for routine API reads and ≤ 900 ms for routine commands, measured server-side excluding document transfer/rendering and external calls. Pilot test profile is 20 organizations, 100,000 total patient charts, 200 concurrently active staff and 50 mixed API requests per second for 30 minutes. Run before pilot and revise assumptions after observation. |
| OPS-04 | Recovery target RPO ≤ 15 minutes, RTO ≤ 4 hours for core chart, order data, documents and their associations. Restore database, objects and IdP dependencies into a clean environment. Reconcile hashes, source versions, in-flight deliveries and audit coverage before reopening writes. Verify before pilot and quarterly thereafter. |
| OPS-05 | Monitor error rates, saturation, queue age, partner delivery, unmatched reports, critical acknowledgment time, missing coverage, unassigned patient messages, response breaches, failed instruction notifications, overdue instruction acknowledgments, rendering failures and backup status. Define operational on-call and site clinical coverage owners. Have downtime and recovery runbooks for paper/manual capture, patient identification, critical contacts and supervised back-entry. Prevent duplicates when recovering. |
| OPS-06 | Use separate development, test, staging and production accounts/resources. Use synthetic test fixtures, CI policy checks, dependency and secret scanning, signed builds, migration checks and monitored progressive rollouts. Support reversible deployments and expand/contract schema changes. Production access requires audited approval. |
| OPS-07 | Support tenant export with manifest, schema version, checksums, authorized documents, provenance and audit history as allowed by policy. Retention jobs respect legal holds and track deletion of active data and approved backup expiration. Test representative record requests, exports, tenant offboarding and disputed access. |
| OPS-08 | Import approved onboarding datasets through a versioned manifest and deterministic mapping. Dry-run counts, unsupported fields, duplicate risk, missing attachments and errors. Preserve import provenance and historical signature evidence without creating a new attestation. Commit in controlled batches, reconcile counts and hashes, and require authorized sign-off before changing the source of truth. |

## 8.1 Production platform and web delivery

These requirements apply to every production deployment and are in addition to OPS-01 to OPS-08.

| ID | Requirement and verification |
|---|---|
| WEB-01 | Edge and transport. Serve every endpoint over TLS 1.2 or newer (TLS 1.3 preferred) with HSTS (max-age at least one year, includeSubDomains) and an HTTP-to-HTTPS redirect. Issue and renew certificates automatically and alert 21 days before expiry. Put a managed WAF with the OWASP core rule set and provider DDoS protection in front of the API and the staff web. Verify with an external TLS scan and a WAF rule test before pilot. |
| WEB-02 | Browser security. Send a Content-Security-Policy without `unsafe-inline` or `unsafe-eval` (nonces or hashes), `frame-ancestors 'none'`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer` and a restrictive `Permissions-Policy` on every response. Cookies are `HttpOnly`, `Secure` and `SameSite=Lax` or stricter. Authenticated responses carry `Cache-Control: no-store`. No patient data in URLs, local storage, session storage, analytics or logs. Verify with a header test in CI and an external header scan. |
| WEB-03 | Sessions. Staff sign in through managed OIDC with MFA (section 7, Identity). The web session expires after 15 minutes idle (policy-configurable, never above 30) and 12 hours absolute, sensitive actions require renewed authentication, logout ends the session on the server, a user can list and revoke their sessions, and state-changing requests carry a CSRF token. Lock out and rate-limit authentication endpoints. Mobile uses authorization code with PKCE and short-lived access tokens. Verify expiry, revocation, CSRF rejection and lockout with automated tests. |
| WEB-04 | API protection. Rate-limit per subject and per organization and answer `429` with `Retry-After`. Cap request header and body size, page size and request time. Allow cross-origin calls only from an exact origin allowlist (empty by default). Error bodies are machine-readable and never reveal stack traces, SQL, other tenants or internal class names. Verify with tests for limit, size, CORS and error-shape rules. |
| WEB-05 | Health and lifecycle. Expose `GET /health/live` (process up) and `GET /health/ready` (database reachable and migrations current) outside `/api/v1`; these are the only routes outside the versioned prefix, need no authentication and return no patient or tenant data. Shut down gracefully by draining in-flight requests for at least 30 seconds. Fail startup closed when required configuration is missing. Run migrations as a separate deployment step. Verify with tests and a rolling-deploy rehearsal. |
| WEB-06 | Observability. Emit structured JSON logs with correlation ID, route, status and latency, and never request bodies, tokens or patient fields; prove redaction with a test. Publish request rate, error rate and duration per route plus saturation and queue age. Alert on the OPS-03 and OPS-05 targets, on error-budget burn and on failed external uptime probes from outside the network. Keep logs and metrics for the period the approved policy pack sets. |
| WEB-07 | Supported clients. Staff web supports the latest two major versions of Chrome, Edge, Firefox and Safari on desktop at 1280 x 720 and above, with a compact layout down to 768 px width. The patient app supports the latest two major iOS and Android versions. Unsupported browsers see a plain message and no data. Verify with a browser matrix in the end-to-end suite. |
| WEB-08 | Front-end performance. Staff web meets, at the 75th percentile on a mid-range laptop over broadband, largest contentful paint of 2.5 s or less, interaction to next paint of 200 ms or less and layout shift of 0.1 or less. Each route chunk is at most 250 KB gzip, enforced in CI. Split code per feature, self-host fonts with Arabic coverage, serve hashed assets as immutable, and load no third-party scripts or trackers. |
| WEB-09 | Resilient screens. Every screen defines loading, empty, error, timeout or offline, forbidden, stale-version conflict and session-expired states. A conflict never loses typed work; the user can compare, reload or copy. Leaving with unsaved changes asks first. A global error boundary shows the correlation ID and logs the error without patient data. Verify each state with a component test. |
| WEB-10 | End-to-end verification. Automate the critical journey of each supported role in a real browser against staging with synthetic data, in English and in Arabic right-to-left, with an accessibility scan that reports zero serious or critical violations and light and dark visual checks. Run it on every release candidate and keep the report as evidence. |
| WEB-11 | Release and rollback. Build once and promote the same signed artifact through environments with an SBOM. Roll out progressively (5%, 25%, 100%) and roll back automatically when error rate or latency breaches its threshold. Use expand and contract migrations and feature flags that default off. Rehearse a rollback before pilot and keep a release checklist with named approvers. |
| WEB-12 | Vulnerability management. Scan dependencies, images and secrets on every change and block merges on critical findings. Pin base images by digest and CI actions by commit SHA. Remediate critical findings within 7 days and high findings within 30. Commission an independent penetration test before pilot and after each major change, and track every finding to closure. |
| WEB-13 | Public presence. V1 has no public marketing site. Host separately, without patient data, a status and maintenance page and a support page with the privacy notice, accessibility statement, terms and support contact. Mark every authenticated web application `noindex` and disallow crawling in `robots.txt`. |

Before G5, each production deployment holds evidence for WEB-01 to WEB-13. Evidence for WEB-01, WEB-04, WEB-11 and WEB-12 is produced with the infrastructure owner; the rest is produced by CI and the end-to-end suite.

## 9. Delivery gates and acceptance

| Gate | Work | Required evidence |
|---|---|---|
| G0 — platform | IAM, bounded tenant model, catalog/configuration, audit and infrastructure | Two tenants with identical local IDs remain isolated across API, search, files, workers and exports. Revocation applies on next access, including prior invitations and protected releases. Concurrent connection and transaction tests pass. |
| G1 — clinic | Scheduling, staff workflows, clinical chart, templates, sign/addenda and documents | Simultaneous competing bookings enforce a single protected slot. Stale draft cannot overwrite a signed record. Corrections preserve the signed original and authorship. English/Arabic PDFs validated. Consolidation all-or-nothing and blocked on unresolved conflicts. |
| G2 — labs and exchange | Scoped requisitions, specimens, verified reports, escalation, reconciliation and release | Rejected/recollected specimen traced to the right order. Duplicate and reordered events do not duplicate or overwrite final reports. Unmatched results wait for review. Critical result escalates on deadline, a human acknowledgment is recorded, and a correction reopens review. |
| G3 — patient and operations | iOS/Android access, support, exports, recovery, metrics and privacy | Confirmed patient sees only their authorized revisions. Generic notifications reveal no clinical details. Withdrawn releases stop future access. Tests cover MSG-01–07, including patient and unit initiation, wrong-unit denial, patient document upload and safe unit retrieval, instructions and attachments on both mobile platforms, revision-specific acknowledgment, failed uploads, notification retry and revoked connections. Restore, downtime, accessibility, load and audit checks pass. |
| G4 — specialty and finance | Radiology, PT, pharmacy and self-pay | Wrong-patient study association refused. Radiology addendum reaches the referrer. PT episode produces signed discharge and referral closure. Ambiguous prescription transmission reconciles without sending a duplicate. Payment/refund entries reproduce balances on replay. |
| G5 — launch | Authorized clinical and operational configuration per enabled site | Each site approves its workflow, release policy, market constraints, integrations, identity procedures, staffing and downtime drills. Before enabling messaging, each participating unit verifies its patient eligibility rules, inbox ownership, after-hours guidance and backup coverage. No unresolved patient safety, tenant isolation, record-integrity or critical escalation defects in enabled scope. |

Use requirement IDs in acceptance tests and backlog items. Require unit tests for state/legal transitions and permissions, integration tests against a real database and queue, contract fixtures for partners, end-to-end tests per supported role, and failure injection for concurrent edits, duplicate messages, unavailable partners and restoration. Assign an owner, reviewer and evidence link to each gate.

## 10. Decisions required before production enablement

| Decision | Accountable role | Evidence |
|---|---|---|
| Launch jurisdiction, hosting region and scope of pilot | Product owner and privacy lead | Signed market policy pack and data handling map |
| Clinical templates, code sets, interpreting privileges, results escalation and coverage | Clinical safety lead and site medical director | Approved dictionaries and timed workflow drills |
| Result release, sensitive information handling and patient identity recovery | Privacy lead and clinical safety lead | Approved rules, enrollment rehearsal and access review |
| Lab, PACS and pharmacy interface contracts | Integration lead and partner owner | Conformance results, approved credentials, reconciliation and outage drill |
| Service capacity and recovery targets | Engineering and operations | Load report, restore evidence and on-call readiness |
| Implementation priorities and release approval | Product, clinical, engineering and privacy owners | Signed acceptance matrix and roll-forward/rollback criteria |

Develop synthetic adapters and mock partners while decisions are pending. Enable provider or partner traffic only after the corresponding evidence exists.

## 11. Implementation handoff

| Cross-cutting scenario | Testable outcome |
|---|---|
| Missed coverage | A critical result lands in a staffed backup queue when the owner is unavailable. Alerts record each attempt, the original deadline and confirmed receipt. No result is silently reassigned to an unstaffed queue. |
| Retracted document | The portal stops serving the withdrawn revision. Already issued disclosures remain in the audit history. Outbound correction tasks use the original recipient list and expose failed deliveries. |
| Patient contacts two units | The patient starts separate authorized threads, shares a PDF with one unit, and receives verified instructions from the other. Neither unit can search, read or download material in the other thread. The patient can open both in their mobile inbox. |
| Failed exchange after commit | The order and outbox entry survive process loss. Replay from the review queue produces a single downstream effect, with one clinical task for the intended revision. |
| Patient linkage dispute | A privacy officer suspends the affected link and reviews other authorized associations. In-flight reads and new downloads recheck link status. The clinical chart and disclosure history remain available to authorized staff. |
| Service fails during rollout | Operators stop traffic to the affected feature, preserve committed records and recover deterministically. Existing appointments and clinical results remain visible where the core service is healthy. Outage and backlog status are available to site operators. |

First implementation slice. Provision two synthetic clinics and a partner lab. Implement staff membership, patient registration, booking, check-in and one signed laboratory order. Exercise collection, verification, correction, clinician acknowledgment and approved release through both patient mobile platforms. Use this slice to validate the permission model, event IDs, correction semantics and restore path before adding clinical templates and specialty depth.

Each implementation pull request records affected requirements, review owner, data changes, authorization boundaries, audit events, migration and rollout considerations, plus test evidence. Changes that affect signed data, cross-organization sharing, patient association, prescribing or critical results require explicit clinical and architecture review before rollout.

## Appendix A. Role skill definitions

Each fenced block is a complete Markdown skill definition with YAML frontmatter. The roles can be used independently or in the following order on each proposed capability: product defines intended workflow and scope, physician reviews clinical safety, architect specifies contracts and integrity, and engineer implements and verifies. Reopen upstream decisions when implementation exposes a mismatch.

### Product Manager

```markdown
---
name: healthcare-product-manager
description: Define or review healthcare software scope, care workflows, pilot priorities, stakeholder outcomes, release criteria and requirement traceability.
---

# Healthcare Product Manager

1. Read the current specification and constraints. Identify the population, organization types, actors and release boundary. Record assumptions and separate build blockers from deployment decisions.
2. Map normal, exception and downtime journeys from trigger through disposition. For each handoff record sender, owner, deadline, acknowledgment and escalation. Check independent facilities and external recipients.
3. Assign stable requirement IDs. Write one measurable behavior per requirement with roles, preconditions, expected state, exception and outcome. Preserve traceability to product objectives.
4. Prioritize by patient harm, workflow dependency and operational benefit. Propose rollout gates and named decision owners. Define pilot metrics with denominators, time windows and data sources.
5. Produce a scope table, prioritized backlog, acceptance scenarios and decision register. Flag clinical and regulatory decisions for their accountable reviewers. Avoid representing assumptions as confirmed requirements.
```

### Physician and Clinical Safety Reviewer

```markdown
---
name: physician-clinical-safety-reviewer
description: Apply a physician perspective to reviewing EHR workflows, patient identity, documentation, prescribing, result reconciliation and clinical safety requirements.
---

# Physician and Clinical Safety Reviewer

1. Follow a synthetic patient through registration, handoff, care, ordering, results, follow-up and correction. Verify who owns each decision and how coverage changes.
2. Review wrong-patient, wrong-specimen, wrong-medication, missing context, late result, mistaken release and failed escalation hazards. Check ambiguity, units, qualifiers and provenance. Distinguish absent, unknown and unverified observations.
3. Specify required verification steps, authorized signers, amendment rules, escalation evidence and downtime actions. Require an owner and deadline for critical results. Treat delivery, acknowledgment and clinical follow-up as distinct events.
4. Use approved clinical references and local policy for clinical rules. Route thresholds, alert overrides, signing and release policies to the designated clinician or privacy owner. Keep site-specific values explicitly configurable.
5. Produce a hazard log with severity, cause, control, verification case, accountable owner and residual risk. Report unresolved launch-blocking risks. Clinical approval belongs to qualified site personnel.
```

### Software Architect

```markdown
---
name: healthcare-software-architect
description: Specify or review healthcare platform architecture, tenant and trust boundaries, contracts, state transitions, failure recovery and operational tradeoffs.
---

# Healthcare Software Architect

1. Trace each major requirement to a bounded context and system of record. Define identifiers, ownership, invariants and permitted transitions. Record cross-organization disclosures and lifecycle of recipient copies.
2. Specify API, event and adapter contracts. Model concurrency, idempotency, replay, partial failure, correction and compatibility. Define access paths for humans, service principals, workers, search, exports and files.
3. Review tenancy, authorizations, audit durability, data residency, retention and recovery. Describe object lifecycle and relational constraints. Validate architecture assumptions with measurable capacity and recovery targets.
4. Choose the simplest design that satisfies known constraints. Document consequential decisions, alternatives, migration impact and rollout evidence. Surface unresolved dependencies and assign their approval owners.
5. Produce domain boundaries, interface and state contracts, decision records, and verification requirements linked to specification IDs.
```

### Software Engineer

```markdown
---
name: healthcare-software-engineer
description: Implement and verify healthcare features from an approved specification, including database migrations, APIs, portal and mobile behavior, integration jobs and release evidence.
---

# Healthcare Software Engineer

1. Read repository instructions and identify the relevant requirement IDs, existing entry points and test fixtures. Implement the smallest complete vertical slice through schema, domain, API, clients and worker as applicable.
2. Enforce invariants and authorization server-side. Add database constraints and transaction boundaries where needed. Preserve signed artifacts, provenance and retry behavior across upgrades and concurrent requests.
3. Use synthetic records. Verify happy path, access denial, concurrency, stale versions, failures, replay, correction and forward/backward migration where applicable. Check staff, patient, partner and worker behavior when affected.
4. Keep changes reviewable. Record schema and contract changes, operational metrics, rollback considerations and remaining blockers. Escalate unresolved product, clinical or architecture decisions before real-patient rollout.
5. Return a requirement-to-change map and concrete evidence from executed checks. Identify unverified behavior and deployment dependencies explicitly.
```
