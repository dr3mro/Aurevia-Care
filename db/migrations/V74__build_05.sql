-- Requirement IDs: CLN-06, ARC-01, ARC-02, OPS-02
-- Change: expand
-- Durable follow-up suggestions and their care-team action tasks. Both tables are
-- tenant-owned and protected by forced row-level security: a new table is not
-- covered by V2's static table list, so every policy, grant and constraint is
-- declared here. The migration is additive only and needs no backfill.
--
-- A suggestion keeps the current version at its immutable identifier. Each
-- state change creates a new version that overwrites the current row, while the
-- original rule explanation, the override provenance columns and the append-only
-- OPS-02 audit_event rows preserve what changed, who changed it and why. Local
-- identifiers are unique only inside a tenant, so organization_id is part of
-- every primary key and every business lookup.

CREATE TABLE public.follow_up_suggestion (
    organization_id          uuid NOT NULL REFERENCES public.organization(organization_id),
    id                       uuid NOT NULL DEFAULT gen_random_uuid(),
    patient_id               uuid NOT NULL,
    facility_code            text NOT NULL,
    visit_type               text NOT NULL,
    encounter_at             timestamptz NOT NULL,
    time_zone                text NOT NULL,
    suggested_date           date NOT NULL,
    interval_days            integer,
    basis                    text NOT NULL CHECK (basis IN ('CONFIGURED_INTERVAL', 'PRIOR_FOLLOW_UP')),
    rule_explanation         text NOT NULL,
    override_actor           text,
    override_reason          text,
    override_recorded_at     timestamptz,
    override_original_date   date,
    override_replacement_date date,
    confirmed                boolean NOT NULL DEFAULT false,
    booking_id               text,
    version                  integer NOT NULL CHECK (version >= 1),
    created_at               timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id)
);

CREATE TABLE public.care_team_task (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    follow_up_id    uuid NOT NULL,
    owner           text NOT NULL,
    due_date        date NOT NULL,
    instruction     text NOT NULL,
    status          text NOT NULL CHECK (status IN ('OPEN', 'COMPLETED')),
    outcome         text,
    created_by      text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id)
);

CREATE INDEX care_team_task_follow_up_idx
    ON public.care_team_task (organization_id, follow_up_id);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['follow_up_suggestion', 'care_team_task']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON public.%I
             USING (organization_id = public.app_org())
             WITH CHECK (organization_id = public.app_org())$p$, t);
    END LOOP;
END $$;

-- The runtime role writes suggestions and tasks through the domain service;
-- deletion and truncation are reserved for the migration owner.
GRANT SELECT, INSERT, UPDATE ON public.follow_up_suggestion TO aurevia_app;
GRANT SELECT, INSERT, UPDATE ON public.care_team_task TO aurevia_app;
REVOKE DELETE, TRUNCATE ON public.follow_up_suggestion FROM aurevia_app;
REVOKE DELETE, TRUNCATE ON public.care_team_task FROM aurevia_app;
