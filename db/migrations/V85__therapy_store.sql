-- Requirement IDs: PT-01, ARC-01, ARC-02, OPS-02
-- Change: expand
-- Storage the V77 therapy domain needs for its JDBC adapter. V77 owns the
-- episode, evaluation, measurement, goal, scale, reading, intervention, note,
-- summary and audit tables with forced row-level security. Two narrow gaps
-- remain for a durable port:
--   * an evaluation's free-text problem list had no table;
--   * scale-version allocation was process-local and must be durable and atomic
--     across requests.
-- This migration adds one append-only tenant table for problems and one mutable
-- per-tenant counter table whose ON CONFLICT upsert allocates versions atomically.
-- Every table is tenant-owned with ENABLE + FORCE RLS, a policy using app_org()
-- with WITH CHECK, and explicit grants/revokes. This migration is additive only.

CREATE TABLE public.therapy_evaluation_problem (
    organization_id uuid NOT NULL,
    episode_id      uuid NOT NULL,
    ordinal         integer NOT NULL CHECK (ordinal > 0),
    problem         text NOT NULL CHECK (char_length(problem) BETWEEN 1 AND 500),
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, episode_id, ordinal),
    FOREIGN KEY (organization_id, episode_id)
        REFERENCES public.therapy_evaluation(organization_id, episode_id)
);

-- A scale version is allocated by one atomic upsert, so two concurrent definers
-- cannot both receive the same version. No advisory lock is required because the
-- increment and the row write commit together.
CREATE TABLE public.therapy_scale_sequence (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    key             text NOT NULL CHECK (char_length(key) BETWEEN 1 AND 64),
    next_version    integer NOT NULL DEFAULT 1 CHECK (next_version > 0),
    PRIMARY KEY (organization_id, key)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['therapy_evaluation_problem', 'therapy_scale_sequence']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON public.%I
             USING (organization_id = public.app_org())
             WITH CHECK (organization_id = public.app_org())$p$, t);
    END LOOP;
END $$;

-- A problem is part of an immutable evaluation; it can never be rewritten, even
-- by the migration owner. The counter is intentionally mutable.
CREATE TRIGGER therapy_evaluation_problem_no_update_delete
    BEFORE UPDATE OR DELETE ON public.therapy_evaluation_problem
    FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation();
CREATE TRIGGER therapy_evaluation_problem_no_truncate
    BEFORE TRUNCATE ON public.therapy_evaluation_problem
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation();

GRANT SELECT, INSERT ON public.therapy_evaluation_problem TO aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.therapy_evaluation_problem FROM aurevia_app;

GRANT SELECT, INSERT, UPDATE ON public.therapy_scale_sequence TO aurevia_app;
REVOKE DELETE, TRUNCATE ON public.therapy_scale_sequence FROM aurevia_app;
