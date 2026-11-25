-- Requirement IDs: OPS-02, ARC-04
-- Change: expand
-- Durable authorization decision ledger. Every command authorization produces one
-- append-only row carrying a stable outcome, reason code, action and correlation
-- identifier. The tenant is taken from app_org(), bound by the guarded transaction,
-- so forced RLS confines both reads and writes to the active organization. This
-- migration is additive: no existing table or policy is altered.
--
-- The ledger holds codes and opaque identifiers only. No request body, patient
-- field or free-text explanation has a column, and the CHECKs keep every value
-- short and enumerable. TRUNCATE and mutation are rejected for every role,
-- including the migration owner, because a GRANT alone does not bind the owner.
-- Adding a future reason code is a contract change that needs an explicit
-- forward-fix migration replacing the CHECK before the new code can be written.

CREATE TABLE public.authorization_decision (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    subject         text CHECK (subject IS NULL OR length(subject) BETWEEN 1 AND 255),
    -- Malformed and unauthenticated commands carry no catalog action or command
    -- token. They are still denials worth recording, so these columns are
    -- nullable; a present value still has to be a bounded token.
    action          text CHECK (action IS NULL OR length(action) BETWEEN 1 AND 128),
    resource_type   text CHECK (resource_type IS NULL OR length(resource_type) BETWEEN 1 AND 128),
    decision        text NOT NULL CHECK (decision IN ('allow', 'deny')),
    reason_code     text NOT NULL CHECK (reason_code IN (
        'ALLOWED', 'UNAUTHENTICATED', 'INVALID_COMMAND', 'UNKNOWN_COMMAND',
        'MEMBERSHIP_INACTIVE', 'MEMBERSHIP_UNAVAILABLE', 'POLICY_UNAVAILABLE',
        'COMMAND_FORBIDDEN', 'AUDIT_UNAVAILABLE')),
    correlation_id  text CHECK (correlation_id IS NULL OR length(correlation_id) BETWEEN 1 AND 255),
    decided_at      timestamptz NOT NULL DEFAULT now()
);

-- The ledger only grows; tenant and correlation lookups must not scan all rows.
CREATE INDEX authorization_decision_tenant_correlation_idx
    ON public.authorization_decision (organization_id, correlation_id, decided_at DESC);

ALTER TABLE public.authorization_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.authorization_decision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.authorization_decision
    USING (organization_id = public.app_org())
    WITH CHECK (organization_id = public.app_org());

CREATE TRIGGER authorization_decision_no_update_delete
    BEFORE UPDATE OR DELETE ON public.authorization_decision
    FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation();
CREATE TRIGGER authorization_decision_no_truncate
    BEFORE TRUNCATE ON public.authorization_decision
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation();

-- Declaration-only ledger: the application can append and read, never rewrite.
GRANT SELECT, INSERT ON public.authorization_decision TO aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.authorization_decision FROM aurevia_app;
