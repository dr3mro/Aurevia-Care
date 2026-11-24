# Print permission matrix

- Requirements: CLN-07, RX-02, LAB-01
- Audience: clinicians, lab and imaging staff, tenant admins, operations and QA.
- Companion documents: [printing guide](README.md), [acceptance scenario](uat.md).

This matrix states the default V1 print policy for the five handout kinds. It
is a documentation contract, not an enforcement point: the server authorizes
every print and download from the caller's live identity, membership, role,
facility scope and care relationship (IAM-01, IAM-02). A site configures the
named print permission and clinical privileges through approved tenant
configuration (ADM-01) and may only narrow what is shown here.

Legend: **Yes** = may print when all scope conditions hold and the source is in
the required state; **No** = no print permission for that kind in this flow;
**—** = not a staff-print actor.

## Role against handout kind

| Role (from the specification role table) | Prescription — patient copy | Prescription — pharmacy copy | Lab order sheet | Imaging order sheet | Chart summary | Signed report | Scope condition |
|---|---|---|---|---|---|---|---|
| Reception | No | No | No | No | No | No | Demographic and scheduling fields only; no clinical handout content |
| Clinical assistant | No | No | No | No | No | No | May prepare intake or reconciliation but does not print signed handouts |
| Clinician (treating, with print permission) | Yes | Yes | Yes | Yes | Yes | Yes | Inside the care relationship, facility scope and granted privileges; source signed/attested or placed |
| Lab collector / verifier | No | No | Yes | No | No | No | Assigned requisitions only; site privileges decide separation of duties |
| Radiology staff / interpreter | No | No | No | Yes | No | No | Assigned referrals and verified imaging privileges; images stay under partner control |
| PT clinician | No | No | No | No | No | No | PT summaries go to the referrer under PT-01, not to the patient-print flow in V1 |
| Pharmacy staff | No | No | No | No | No | No | Reviews routed items and records status evidence; does not print patient handouts |
| Finance | No | No | No | No | No | No | Financial fields and service references only; no clinical content |
| Tenant admin / privacy auditor | No | No | No | No | No | No | Configuration and audit/disclosure review; clinical access follows separate clinical permissions |
| Patient | — | — | — | — | — | — | Does not print from staff web; views/downloads only released handouts through the patient app (PAT-02) |

## Permission rules

1. **Default deny.** A role may print only the kinds marked Yes, and only when
   every scope condition in that row holds. There is no implicit permission from
   being signed in, being an administrator or being able to view the record.
2. **Server-side and re-derived.** Each print and download re-reads the caller's
   active membership, roles, care relationship and facility scope. Permission
   changes, deactivation and organization switching take effect on the next
   protected request; a client-supplied role or patient id is never trusted
   (IAM-01, IAM-02).
3. **Source state gates the kind.** A Yes still requires the source to be in the
   printable state: a signed or attested prescription with an approved channel
   (RX-02), a placed rather than draft order (LAB-01), or signed clinical content
   (CLN-05). A draft is refused even for a role marked Yes.
4. **Tenant and facility scope.** The facility of the *record* is the one
   checked, not the caller's preferred facility. A record in another tenant is
   concealed as not found; a record outside the caller's facility or care
   relationship is forbidden. Neither reveals existence or content (ARC-02).
5. **Reason is not a permission.** Any role allowed to print must still choose a
   reason — give to patient, referral or patient request — before Print is
   enabled. The reason is recorded with the audit entry, never the content
   (OPS-02).
6. **Reprints are prints.** A reprint or download needs the same permission and
   reason as the first print and produces its own audit entry. A newer version
   does not grant permission to print a superseded one outside the same scope.
7. **Channel matters for prescriptions.** A printable prescription is allowed
   only through an approved channel. A locally printable channel produces the
   document only; it is not evidence of electronic submission or pharmacy
   acceptance, and an ambiguous electronic transmission must be verified with
   the partner before any retry (RX-02).

## How this matrix is used

- QA uses it to build the allowed and forbidden cases in the
  [acceptance scenario](uat.md): one allowed case per Yes cell that applies to
  the clinician journey, plus a forbidden case for a No row and a wrong-tenant
  case.
- Tenant admins use it when assigning the print permission: a grant that would
  let a No role print, or a Yes role print outside its scope, is a policy error,
  not a product feature.
- Reviewers treat the matrix as the expected authorization decisions. An HTTP
  test that lets a forbidden role print, or that reveals a cross-tenant record,
  is a defect against IAM-01, IAM-02 and ARC-02.

The specification's full role table and the authoritative clauses are in the
[product specification](../../../.opencode/config/PROJECT_V1_SPEC.md). This matrix adds no
new role, requirement ID or permission beyond them.
