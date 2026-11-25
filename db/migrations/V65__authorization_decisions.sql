-- Requirement IDs: OPS-02, ARC-04
-- Change: expand
-- Durable, append-only ledger for OPS-02 authorization outcomes. Every command
-- decision is recorded with the active tenant, the authenticated subject, the
-- owning action, the bounded command identifier, a stable reason code and the
-- correlation identifier. The table carries no request body, role list or
-- patient field: only the codes the OPS-02 contract names are stored. Local
-- identifiers are unique inside a tenant, so organization_id is part of the
-- tenant policy and every bound is checked by the database as well as by the
-- Java boundary. ENABLE and FORCE ROW LEVEL SECURITY confine reads and writes
-- to public.app_org(); the migration owner gets no exemption. The table is
-- additive and does not lock or rewrite an existing row.

CREATE TABLE public.authorization_decision (
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    subject         text NOT NULL CHECK (length(subject) <= 256),
    action          text NOT NULL CHECK (length(action) <= 128),
    resource_type   text NOT NULL CHECK (length(resource_type) <= 128),
    decision        text NOT NULL CHECK (decision IN ('allow', 'deny')),
    reason_code     text NOT NULL CHECK (length(reason_code) <= 64),
    correlation_id  text NOT NULL CHECK (length(correlation_id) <= 128),
    decided_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

ALTER TABLE public.authorization_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.authorization_decision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.authorization_decision
    USING (organization_id = public.app_org())
    WITH CHECK (organization_id = public.app_org());

-- Reject mutation for every role, including the migration owner: a GRANT alone
-- would not restrain the owner and would leave the audit trail editable.
CREATE TRIGGER authorization_decision_no_update_delete
    BEFORE UPDATE OR DELETE ON public.authorization_decision
    FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation();
CREATE TRIGGER authorization_decision_no_truncate
    BEFORE TRUNCATE ON public.authorization_decision
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation();

-- Append-only runtime access: the application may write and read decisions but
-- can never rewrite or remove them.
GRANT SELECT, INSERT ON public.authorization_decision TO aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.authorization_decision FROM aurevia_app;
