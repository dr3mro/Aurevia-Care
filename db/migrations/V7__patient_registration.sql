-- Requirement IDs: IAM-03, ARC-01, ARC-02, OPS-02
-- Patient registration foundation. Records are append-only: a correction is a
-- new version, never an UPDATE. MRN uniqueness is scoped to the issuing tenant
-- and issuer. Search names are normalized while the original spelling is kept.
-- A likely-duplicate warning is computed on read; it never links records.

CREATE TABLE public.patient_record (
    organization_id   uuid NOT NULL REFERENCES public.organization(organization_id),
    id                uuid NOT NULL DEFAULT gen_random_uuid(),
    version           integer NOT NULL CHECK (version > 0),
    mrn               text NOT NULL,
    mrn_issuer        text NOT NULL,
    display_name      text NOT NULL,
    normalized_name   text NOT NULL,
    dob               date,
    dob_unknown_reason text,
    language          text,
    contacts          jsonb NOT NULL DEFAULT '{}'::jsonb,
    identifiers       jsonb NOT NULL DEFAULT '[]'::jsonb,
    consents          jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_by        uuid NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, id, version),
    UNIQUE (organization_id, mrn_issuer, mrn, version),
    CHECK ((dob IS NOT NULL) <> (dob_unknown_reason IS NOT NULL)),
    FOREIGN KEY (organization_id, created_by)
        REFERENCES public.user_identity(organization_id, id)
);

CREATE INDEX patient_name_idx ON public.patient_record
    (organization_id, normalized_name, display_name);

DO $$
BEGIN
    EXECUTE 'ALTER TABLE public.patient_record ENABLE ROW LEVEL SECURITY';
    EXECUTE 'ALTER TABLE public.patient_record FORCE ROW LEVEL SECURITY';
    EXECUTE $p$CREATE POLICY tenant_isolation ON public.patient_record
         USING (organization_id = public.app_org())
         WITH CHECK (organization_id = public.app_org())$p$;
    EXECUTE $d$CREATE TRIGGER patient_record_no_update_delete
        BEFORE UPDATE OR DELETE ON public.patient_record
        FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$d$;
    EXECUTE $d$CREATE TRIGGER patient_record_no_truncate
        BEFORE TRUNCATE ON public.patient_record
        FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$d$;
END $$;

-- The author is always the authenticated actor of the active tenant.
CREATE OR REPLACE FUNCTION public.set_patient_created_by_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'patient registration requires an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.created_by := v_actor;
    RETURN NEW;
END $$;
CREATE TRIGGER patient_record_created_by_actor
    BEFORE INSERT ON public.patient_record
    FOR EACH ROW EXECUTE FUNCTION public.set_patient_created_by_from_actor();

-- Version is trigger-owned per (org, patient id) under an advisory lock.
CREATE OR REPLACE FUNCTION public.next_patient_version(p_id uuid)
RETURNS integer LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_org uuid := public.app_org();
BEGIN
    IF v_org IS NULL THEN
        RAISE EXCEPTION 'tenant context required' USING ERRCODE = '42501';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(v_org::text || ':' || p_id::text, 0));
    RETURN coalesce(max(version), 0) + 1 FROM public.patient_record WHERE id = p_id;
END $$;

-- One effective version per patient: the latest by version.
CREATE OR REPLACE FUNCTION public.current_patients()
RETURNS TABLE (organization_id uuid, id uuid, version integer, mrn text, mrn_issuer text,
               display_name text, normalized_name text, dob date, dob_unknown_reason text,
               language text, contacts jsonb, identifiers jsonb, consents jsonb,
               created_by uuid, created_at timestamptz)
LANGUAGE sql STABLE SET search_path = pg_catalog
AS $$
    SELECT DISTINCT ON (p.organization_id, p.id)
           p.organization_id, p.id, p.version, p.mrn, p.mrn_issuer, p.display_name,
           p.normalized_name, p.dob, p.dob_unknown_reason, p.language, p.contacts,
           p.identifiers, p.consents, p.created_by, p.created_at
      FROM public.patient_record p
     ORDER BY p.organization_id, p.id, p.version DESC
$$;

REVOKE ALL ON FUNCTION public.next_patient_version(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.current_patients() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.next_patient_version(uuid) TO orevia_app;
GRANT EXECUTE ON FUNCTION public.current_patients() TO orevia_app;
GRANT SELECT, INSERT ON public.patient_record TO orevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.patient_record FROM orevia_app;
