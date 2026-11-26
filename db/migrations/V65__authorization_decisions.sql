-- Requirement IDs: OPS-02, ARC-04
-- Change: expand
<<<<<<< HEAD
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
=======
-- Append-only, tenant-owned ledger of OPS-02 authorization decisions. One
-- immutable row is written for every allow or deny evaluated by the command
-- gate before the caller is answered. Only coded authorization facts are
-- stored: subject, action, resource discriminator, outcome, reason code,
-- correlation id and decision time. No request body, role list, credential or
-- patient field is copied here. Creating a new relation takes no lock on
-- existing data; one insert is issued per evaluated command.
--
-- Tenant isolation is declared here because V2's static table list does not
-- cover new tables. FORCE RLS binds the migration owner as well as the runtime
-- role. Grants are INSERT and SELECT only; the reject-mutation triggers also
-- cover the owner, whom GRANT and REVOKE alone cannot constrain. A failed or
-- rolled-back command therefore never erases a recorded decision.

CREATE TABLE public.authorization_decision (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    subject         text NOT NULL DEFAULT '',
    action          text NOT NULL DEFAULT '',
    resource_type   text NOT NULL DEFAULT 'COMMAND' CHECK (resource_type = 'COMMAND'),
    decision        text NOT NULL CHECK (decision IN ('allow', 'deny')),
    reason_code     text NOT NULL CHECK (reason_code IN (
        'ALLOWED', 'UNAUTHENTICATED', 'INVALID_COMMAND', 'UNKNOWN_COMMAND',
        'MEMBERSHIP_INACTIVE', 'MEMBERSHIP_UNAVAILABLE', 'POLICY_UNAVAILABLE',
        'COMMAND_FORBIDDEN', 'AUDIT_UNAVAILABLE')),
    correlation_id  text NOT NULL DEFAULT '',
    decided_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    CHECK (length(subject) <= 256 AND length(action) <= 128),
    CHECK (length(resource_type) <= 128 AND length(correlation_id) <= 128)
);

-- The schema has no command_id column: the bounded action code identifies the
-- evaluated command, and resource_type is a fixed discriminator. Client command
-- identifiers and role lists stay with the owning service's V3 audit event.
COMMENT ON TABLE public.authorization_decision IS
    'Append-only coded OPS-02 authorization decision facts; no request body or patient data.';
COMMENT ON COLUMN public.authorization_decision.resource_type IS
    'Fixed ledger discriminator COMMAND; never a client-supplied free-text value.';

ALTER TABLE public.authorization_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.authorization_decision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.authorization_decision
    USING (organization_id = public.app_org())
    WITH CHECK (organization_id = public.app_org());

-- Time-ordered reads for one tenant do not use the primary key's leading id column.
CREATE INDEX authorization_decision_org_decided_at_idx
    ON public.authorization_decision (organization_id, decided_at DESC, id DESC);
>>>>>>> origin/main

CREATE TRIGGER authorization_decision_no_update_delete
    BEFORE UPDATE OR DELETE ON public.authorization_decision
    FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation();
CREATE TRIGGER authorization_decision_no_truncate
    BEFORE TRUNCATE ON public.authorization_decision
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation();

<<<<<<< HEAD
-- Only append and read are granted; UPDATE and DELETE are never granted, and the
-- triggers above close the gap for roles that own the table.
=======
>>>>>>> origin/main
GRANT SELECT, INSERT ON public.authorization_decision TO aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.authorization_decision FROM aurevia_app;
