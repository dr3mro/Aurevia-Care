-- Requirement IDs: OPS-02, ARC-04
-- Change: expand
-- Purpose: durable append-only ledger of command authorization outcomes. The
--   gate records every allow and deny, including denials returned before a
--   command body is trusted. Only stable codes and identifiers are stored:
--   command, catalog action, subject, reason code, correlation id and instant.
--   No request body, patient field or free-text reason is ever written.
-- Tenant invariants: organization_id is required and forced row-level security
--   confines both reads and writes to app_org(). The primary key is id alone as
--   specified by the ledger contract; tenant isolation is enforced by the
--   policy rather than by a composite key, and no row is ever updated or
--   deleted. Only SELECT and INSERT are granted to the runtime role, and
--   reject_immutable_mutation() also protects rows from the migration owner.
-- Lock/volume impact: one empty table, one policy and two triggers; no table
--   is rewritten, no existing row is backfilled and no long lock is held.
-- Recovery/forward-fix: additive and idempotent for a fresh environment; a
--   later expand migration can add columns without changing these invariants.
-- Tamper detection: the ledger relies on append-only grants and rejection
--   triggers rather than V3's hash chain; audit_event remains the chained
--   general audit trail. A hash-chained decision ledger needs its own task.

CREATE TABLE public.authorization_decision (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    subject         text NOT NULL CHECK (length(subject) <= 256),
    action          text NOT NULL CHECK (length(action) BETWEEN 1 AND 128),
    resource_type   text NOT NULL CHECK (length(resource_type) BETWEEN 1 AND 128),
    decision        text NOT NULL CHECK (decision IN ('allow', 'deny')),
    reason_code     text NOT NULL CHECK (length(reason_code) BETWEEN 1 AND 64),
    correlation_id  text NOT NULL CHECK (length(correlation_id) <= 128),
    decided_at      timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.authorization_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.authorization_decision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.authorization_decision
    USING (organization_id = public.app_org())
    WITH CHECK (organization_id = public.app_org());

-- A decision cannot be rewritten after it is recorded. The trigger covers the
-- owner as well; the grants below cover every other role.
CREATE TRIGGER authorization_decision_no_mutation
    BEFORE UPDATE OR DELETE ON public.authorization_decision
    FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation();
CREATE TRIGGER authorization_decision_no_truncate
    BEFORE TRUNCATE ON public.authorization_decision
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation();

GRANT SELECT, INSERT ON public.authorization_decision TO aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.authorization_decision FROM aurevia_app;
