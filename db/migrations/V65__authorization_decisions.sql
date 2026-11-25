-- Requirement IDs: OPS-02, ARC-04
-- Change: expand
-- Durable, append-only ledger of authorization decisions. One row is one allow or
-- deny outcome produced by the command gate. resource_type is the bounded catalog
-- command identifier, or the fixed INVALID_COMMAND / UNKNOWN_COMMAND code when no
-- catalog entry matched: client-supplied text never reaches the ledger. Length
-- checks keep every text column bounded even for a future direct caller of the
-- sink. The ledger stores only stable codes and server-derived identifiers: no
-- request body, no clinical narrative and no patient field is written here.
--
-- The table is tenant-owned: the primary key includes organization_id, forced
-- row-level security confines every reader and writer to the active organization,
-- and the policy's WITH CHECK prevents writing another tenant's decision even
-- when the caller supplies a foreign organization in the command. Append-only is
-- enforced twice: the runtime role gets no UPDATE, DELETE or TRUNCATE grant and
-- triggers reject those operations for every role, including the migration owner.
-- This migration is additive; removing the ledger would be a separate contract
-- migration with an explicit forward-fix, never an edit to this file.

CREATE TABLE public.authorization_decision (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    subject         text NOT NULL CHECK (length(subject) BETWEEN 1 AND 256),
    -- Empty action and correlation id are the gate's bounded sentinels for a
    -- command that never resolved or a request without a correlation id.
    action          text NOT NULL CHECK (length(action) BETWEEN 0 AND 128),
    resource_type   text NOT NULL CHECK (length(resource_type) BETWEEN 1 AND 128),
    decision        text NOT NULL CHECK (decision IN ('allow', 'deny')),
    reason_code     text NOT NULL CHECK (length(reason_code) BETWEEN 1 AND 64)
        CHECK (reason_code ~ '^[A-Z][A-Z0-9_]*$'),
    correlation_id  text NOT NULL CHECK (length(correlation_id) BETWEEN 0 AND 128),
    decided_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id)
);

ALTER TABLE public.authorization_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.authorization_decision FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON public.authorization_decision
    USING (organization_id = public.app_org())
    WITH CHECK (organization_id = public.app_org());

DO $$
BEGIN
    EXECUTE $d$CREATE TRIGGER authorization_decision_no_update_delete
        BEFORE UPDATE OR DELETE ON public.authorization_decision
        FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$d$;
    EXECUTE $d$CREATE TRIGGER authorization_decision_no_truncate
        BEFORE TRUNCATE ON public.authorization_decision
        FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$d$;
END $$;

GRANT SELECT, INSERT ON public.authorization_decision TO aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.authorization_decision FROM aurevia_app;
