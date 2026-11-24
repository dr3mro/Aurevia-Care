-- Requirement IDs: IAM-06, ARC-02, OPS-02
-- Change: expand
-- Emergency access and platform support incident records (IAM-06).
-- Every sensitive action keeps its business purpose, role, facility and subject in
-- an immutable row, and is additionally written to the V3 append-only audit chain.
-- Forced RLS from V2 confines both tables to the active organization: a grant or
-- incident in one tenant cannot unlock data in another.

CREATE TABLE public.emergency_access_grant (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    subject         text NOT NULL,
    facility_code   text NOT NULL,
    purpose         text NOT NULL,
    reason          text NOT NULL,
    role_key        text NOT NULL,
    granted_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    revoked_at      timestamptz,
    revoke_reason   text,
    reviewed_by     text,
    reviewed_at     timestamptz,
    review_note     text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    -- The composite FK includes the tenant, so an Org B facility cannot be
    -- referenced by an Org A grant even if both use the same local code.
    FOREIGN KEY (organization_id, facility_code)
        REFERENCES public.facility(organization_id, code),
    CHECK (expires_at > granted_at),
    CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL)),
    CHECK ((reviewed_at IS NULL) = (reviewed_by IS NULL AND review_note IS NULL)),
    CHECK (reviewed_by IS NULL OR reviewed_by <> subject)
);

CREATE TABLE public.support_access_incident (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    reference       text NOT NULL,
    subject         text NOT NULL,
    reason          text NOT NULL,
    status          text NOT NULL DEFAULT 'pending'
                        CHECK (status IN ('pending', 'approved')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    approved_by     text,
    approved_at     timestamptz,
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, reference),
    CHECK (expires_at > created_at),
    CHECK ((status = 'approved')
           = (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
    -- Separation of duties is enforced in the database as well as the service.
    CHECK (approved_by IS NULL OR approved_by <> subject)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['emergency_access_grant', 'support_access_incident']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            $p$CREATE POLICY tenant_isolation ON public.%I
                 USING (organization_id = public.app_org())
                 WITH CHECK (organization_id = public.app_org())$p$, t);
    END LOOP;
END $$;

CREATE INDEX emergency_grant_subject_idx
    ON public.emergency_access_grant (organization_id, subject, granted_at DESC);
CREATE INDEX support_incident_subject_idx
    ON public.support_access_incident (organization_id, subject, reference);

-- Lifecycle transitions are bounded UPDATEs; there is no delete or truncate path.
GRANT SELECT, INSERT, UPDATE ON public.emergency_access_grant,
                                public.support_access_incident TO aurevia_app;
REVOKE DELETE, TRUNCATE ON public.emergency_access_grant,
                          public.support_access_incident FROM aurevia_app;
