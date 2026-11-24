-- Requirement IDs: XCH-07, ADM-01, ARC-01, ARC-02, OPS-02
-- Change: expand
-- XCH-07 trending foundation. Observations are append-only reported facts with
-- their source unit, reference range, effective time, interpretation and
-- provenance. Comparability is a versioned, effective-dated policy, never a
-- value invented by the trend engine. Both tables are tenant-owned with
-- composite keys, forced RLS and forged-actor protection.
--
-- No anti-join or silent correction is possible: a non-final revision, a
-- qualified or qualitative result, a different test/group or a non-canonical
-- unit is returned as a visible exclusion by the application, not deleted here.
-- Policy thresholds and trendable values remain site clinical decisions
-- (ADM-01); this migration stores only the mechanism and its audit trail.
--
-- Dependencies: V1 organization/user_identity, V3 audit_event, V4
-- reject_immutable_mutation() and the membership folds, V7 patient_record.

CREATE TABLE public.trend_observation (
    organization_id    uuid NOT NULL REFERENCES public.organization(organization_id),
    id                 uuid NOT NULL DEFAULT gen_random_uuid(),
    patient_id         uuid NOT NULL,
    test_code          text NOT NULL,
    test_display       text NOT NULL,
    comparability_group text NOT NULL,
    revision_status    text NOT NULL
        CHECK (revision_status IN ('final', 'preliminary', 'corrected', 'entered_in_error')),
    value              numeric,
    unit               text,
    qualifier          text NOT NULL DEFAULT 'none'
        CHECK (qualifier IN ('none', 'less_than', 'greater_than')),
    reference_low      numeric,
    reference_high     numeric,
    reference_unit     text,
    effective_at       timestamptz NOT NULL,
    interpretation     text,
    qualitative_value  text,
    source_system      text NOT NULL,
    source_version     text,
    created_by         uuid NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    FOREIGN KEY (organization_id, patient_id)
        REFERENCES public.patient_record(organization_id, id),
    FOREIGN KEY (organization_id, created_by)
        REFERENCES public.user_identity(organization_id, id),
    CHECK (value IS NOT NULL OR (qualitative_value IS NOT NULL AND btrim(qualitative_value) <> '')),
    CHECK (qualifier = 'none' OR value IS NOT NULL),
    CHECK (value IS NULL OR (unit IS NOT NULL AND btrim(unit) <> '')),
    CHECK (reference_low IS NULL OR reference_high IS NULL OR reference_low <= reference_high),
    CHECK ((reference_low IS NULL AND reference_high IS NULL) OR reference_unit = unit)
);

CREATE INDEX trend_observation_patient_idx ON public.trend_observation
    (organization_id, patient_id, test_code, effective_at);

CREATE TABLE public.trend_comparability (
    organization_id    uuid NOT NULL REFERENCES public.organization(organization_id),
    test_code          text NOT NULL,
    version            integer NOT NULL CHECK (version > 0),
    canonical_unit     text NOT NULL,
    comparability_group text NOT NULL,
    trendable          boolean NOT NULL,
    effective_from     timestamptz NOT NULL,
    approved_by        uuid NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, test_code, version),
    FOREIGN KEY (organization_id, approved_by)
        REFERENCES public.user_identity(organization_id, id)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['trend_observation', 'trend_comparability']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            $p$CREATE POLICY tenant_isolation ON public.%I
                 USING (organization_id = public.app_org())
                 WITH CHECK (organization_id = public.app_org())$p$, t);
        EXECUTE format(
            $d$CREATE TRIGGER %I_no_mutation
                BEFORE UPDATE OR DELETE ON public.%I
                FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
        EXECUTE format(
            $d$CREATE TRIGGER %I_no_truncate
                BEFORE TRUNCATE ON public.%I
                FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
    END LOOP;
END $$;

-- Author and approver are always the authenticated actor of the active tenant.
CREATE OR REPLACE FUNCTION public.set_trend_observation_author()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'observation author must be an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.created_by := v_actor;
    RETURN NEW;
END $$;
CREATE TRIGGER trend_observation_author_actor
    BEFORE INSERT ON public.trend_observation
    FOR EACH ROW EXECUTE FUNCTION public.set_trend_observation_author();

CREATE OR REPLACE FUNCTION public.set_trend_comparability_approver()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'comparability approver must be an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.approved_by := v_actor;
    RETURN NEW;
END $$;
CREATE TRIGGER trend_comparability_approver_actor
    BEFORE INSERT ON public.trend_comparability
    FOR EACH ROW EXECUTE FUNCTION public.set_trend_comparability_approver();

-- Serialize version allocation per tenant and test code; unset context fails closed.
CREATE OR REPLACE FUNCTION public.next_trend_comparability_version(p_test_code text)
RETURNS integer LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_org uuid := public.app_org();
BEGIN
    IF v_org IS NULL THEN
        RAISE EXCEPTION 'tenant context required' USING ERRCODE = '42501';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(v_org::text || ':' || p_test_code, 0));
    RETURN coalesce(max(version), 0) + 1 FROM public.trend_comparability
     WHERE test_code = p_test_code;
END $$;

-- SECURITY INVOKER with forced RLS: an unset organization yields no rows and a
-- future-dated policy never becomes effective early. The latest effective date
-- wins, not the highest version: a backdated later version must not displace
-- the rule that has been in force since a more recent instant. Version only
-- breaks an effective-date tie.
CREATE OR REPLACE FUNCTION public.current_trend_comparability(p_test_code text)
RETURNS TABLE (test_code text, version integer, canonical_unit text,
               comparability_group text, trendable boolean)
LANGUAGE sql STABLE SET search_path = pg_catalog
AS $$
    SELECT DISTINCT ON (c.organization_id, c.test_code)
           c.test_code, c.version, c.canonical_unit, c.comparability_group, c.trendable
      FROM public.trend_comparability c
     WHERE c.test_code = p_test_code AND c.effective_from <= now()
     ORDER BY c.organization_id, c.test_code, c.effective_from DESC, c.version DESC
$$;

REVOKE ALL ON FUNCTION public.next_trend_comparability_version(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.current_trend_comparability(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.next_trend_comparability_version(text) TO orevia_app;
GRANT EXECUTE ON FUNCTION public.current_trend_comparability(text) TO orevia_app;
GRANT SELECT, INSERT ON public.trend_observation TO orevia_app;
GRANT SELECT, INSERT ON public.trend_comparability TO orevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.trend_observation FROM orevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.trend_comparability FROM orevia_app;
