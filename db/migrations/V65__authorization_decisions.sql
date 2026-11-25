-- Requirement IDs: OPS-02, ARC-04
-- Change: expand
-- Durable, append-only ledger of command authorization decisions (OPS-02).
-- Each row is the evidence for one allow or deny produced by CommandAuthorization:
-- actor, bounded command and action codes, outcome, reason, correlation and the
-- server decision instant. No request bodies, patient fields or free-text notes
-- have a column here. A decision with no server identity has a NULL organization;
-- that tenant-less evidence is visible under the isolation policy because an
-- unauthenticated request has no organization to isolate it into. Every tenant
-- row is forced through the same RLS policy as V2's tenant tables, so a session
-- can only write or read a row for its active organization. The table is
-- append-only for every role, including the migration owner.

CREATE TABLE public.authorization_decision (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid REFERENCES public.organization(organization_id),
    command_id      text NOT NULL CHECK (length(command_id) <= 128),
    subject         text NOT NULL,
    action          text NOT NULL,
    resource_type   text NOT NULL CHECK (resource_type = 'command'),
    decision        text NOT NULL CHECK (decision IN ('allow', 'deny')),
    reason_code     text NOT NULL,
    correlation_id  text NOT NULL,
    decided_at      timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.authorization_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.authorization_decision FORCE ROW LEVEL SECURITY;

-- The NULL branch is only for tenant-less evidence from an unauthenticated
-- decision; it never permits a session to write a row for a different tenant.
CREATE POLICY tenant_isolation ON public.authorization_decision
    USING (organization_id = public.app_org() OR organization_id IS NULL)
    WITH CHECK (organization_id = public.app_org() OR organization_id IS NULL);

CREATE TRIGGER authorization_decision_no_update_delete
    BEFORE UPDATE OR DELETE ON public.authorization_decision
    FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation();
CREATE TRIGGER authorization_decision_no_truncate
    BEFORE TRUNCATE ON public.authorization_decision
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation();

-- Only append and read are granted; UPDATE and DELETE are never granted, and the
-- triggers above close the gap for roles that own the table.
GRANT SELECT, INSERT ON public.authorization_decision TO aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.authorization_decision FROM aurevia_app;
