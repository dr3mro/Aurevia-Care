# Administrator task guide

- Requirements: PRD-01, UX-01
- Role in the staff web: `tenant_admin` (the audit page is also available to
  `privacy_auditor`)
- Screens you use: Facilities, Staff administration, Configuration and catalogs,
  Audit event search

These are the things you do most when you administer an organization. Switch to
the correct active organization first. Each screen reads and writes only that
organization's data, and the screens say so when access is denied. All names
and values in the product are synthetic.

## What you do most

1. **Check what you can access.** Screen: **Administration and audit review**.
   The "Access review" section shows "Access permitted for this active
   organization and role." If membership or the reviewing role is missing you
   see "Permission denied: active membership or a reviewing role is missing.",
   and a concealed resource shows "Resource not found."

2. **Create a facility.** Screen: **Facilities**. Fill "Facility code", "Facility
   name" and "Time zone", then press "Create facility". You should see "Created
   facility {name}" and the new row in the list. Invalid fields are listed under
   "Fix the following fields". Press "Load more" for more facilities.

3. **Invite a staff member.** Screen: **Staff administration**. Fill "Staff
   subject", "Display name" and "Bounded role", then press "Invite staff". You
   should see "Invited {name}" and the person in the staff list. The bounded
   role choices are `tenant_admin`, `reception` and `privacy_auditor`.

4. **Deactivate a staff member.** Screen: **Staff administration**. In the
   "Actions" for the person, press "Deactivate". The "Deactivate {name}?" dialog
   explains that access is denied on the next request. Enter a "Reason for
   deactivation" and press "Deactivate" to confirm, or "Cancel" to stop. You
   should see "Deactivated {name}. Reason entered: {reason} (not stored by the
   API)". Press "History" to read the append-only events and "Close history" to
   return.

5. **Define and approve a configuration version.** Screen: **Configuration and
   catalogs**. In "Look up configuration", enter a "Configuration key" and look
   it up. "Configuration versions" shows "Version", "Effective from", "Approval
   state" and "Approver"; the current one is marked "Effective now". Press
   "Propose version" to add a pending version, then "Approve version". The
   "Approve version {version} of {key}?" dialog asks for an "Approval note
   (optional)" and confirms with "Approve". You should see the version listed as
   "Approved".

6. **Maintain a catalog.** Screen: **Configuration and catalogs**. Enter a
   "Catalog family" and use "List catalog family" to see "Effective catalog
   entries". Press "Define catalog entry" and fill "Entry code", "Entry value"
   and "Effective from (optional)". You should see the new entry in the list.

7. **Search the audit trail.** Screen: **Audit event search**. Under "Audit
   filters" set "Action", "Outcome", "Resource type", "From (UTC instant)" and
   "To (UTC instant)", then press "Apply filters"; use "Clear filters" to reset.
   The "Audit events and chain integrity" list shows purpose, scope, correlation
   id, outcome and action. You should see "Chain verified"; a broken chain shows
   "Chain not verified" and "Chain integrity check failed. Escalate to the
   privacy auditor." Times are shown in the facility zone with the UTC instant
   on hover or focus. Press "Load more" for older events.

8. **Planned administration screens.** The duplicate review worklist is
   `PLANNED: BUILD-48` (the screen component exists but has no registered
   route), templates, field dictionaries and release policies are
   `PLANNED: BUILD-50`, and tenant export and onboarding import are
   `PLANNED: BUILD-51`. None of these can be reached from the portal today.
