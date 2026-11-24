-- Requirement IDs: IAM-06, ARC-02, OPS-02
-- Change: expand
-- A grant must alert the privacy auditor and open a review at the moment it is
-- created, and that review must be closed with an explicit outcome by an
-- independent reviewer. V11 records the purpose, role, facility, subject and the
-- audit chain, but leaves the review itself implicit in audit text. This migration
-- adds one tenant-owned review row per grant:
--
--   * a privacy-auditor recipient and an OPEN state are created with the grant;
--   * closing sets status, outcome, reviewer, note and closed_at together, and the
--     CHECK constraint makes a closed review without an outcome impossible;
--   * a composite foreign key includes the tenant, so an Org A grant cannot be
--     linked to an Org B review even if local identifiers collide.
--
-- Lock and volume impact: CREATE TABLE on a new relation takes no lock on existing
-- data. One bounded row is inserted per emergency grant and updated once per
-- review. Forward-fix: drop this table, its policy and its index to revert an
-- unfinished rollout; no existing column is altered or removed.

CREATE TABLE public.emergency_access_review (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    grant_id        uuid NOT NULL,
    subject         text NOT NULL,
    recipient_role  text NOT NULL DEFAULT 'privacy_auditor'
                        CHECK (recipient_role = 'privacy_auditor'),
    status          text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed')),
    outcome         text CHECK (outcome IN ('approved', 'concerns_noted',
                                            'action_required', 'recorded')),
    reviewer        text,
    note            text,
    opened_at       timestamptz NOT NULL DEFAULT now(),
    closed_at       timestamptz,
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, grant_id),
    FOREIGN KEY (organization_id, grant_id)
        REFERENCES public.emergency_access_grant(organization_id, id),
    -- IAM-06 closure rule: an open review has no decision evidence; a closed one
    -- must carry an outcome, an independent reviewer, a note and a closure time.
    CHECK ((status = 'closed') =
           (outcome IS NOT NULL AND reviewer IS NOT NULL
            AND note IS NOT NULL AND closed_at IS NOT NULL)),
    CHECK (reviewer IS NULL OR reviewer <> subject)
);

COMMENT ON TABLE public.emergency_access_review IS
    'IAM-06 privacy-auditor review opened with each emergency grant and closed with an explicit outcome.';

-- New tables are not covered by V2's static RLS list: enable and force RLS with
-- an explicit tenant policy and explicit grants here.
ALTER TABLE public.emergency_access_review ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.emergency_access_review FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.emergency_access_review
    USING (organization_id = public.app_org())
    WITH CHECK (organization_id = public.app_org());

CREATE INDEX emergency_access_review_status_idx
    ON public.emergency_access_review (organization_id, status, opened_at DESC);

GRANT SELECT, INSERT, UPDATE ON public.emergency_access_review TO orevia_app;
REVOKE DELETE, TRUNCATE ON public.emergency_access_review FROM orevia_app;
