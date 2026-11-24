-- Requirement IDs: CLN-04, ARC-02, OPS-02
-- Change: expand
--
-- Canonical, versioned CLN-04 baseline templates and their approved site
-- dictionaries. This is additive reference configuration: no V80 object is
-- changed, no tenant row is written and no clinical payload is stored here.
--
-- Why a global catalog instead of seeding template_version (V80): those tables
-- are tenant owned with FORCE RLS and a WITH CHECK on app_org(), and migrations
-- run before any tenant exists, so a migration cannot satisfy the policy or the
-- organization foreign key. Moreover, dictionary and version approval is a
-- governance role-gated workflow; a migration has no governance actor and must
-- not fabricate an approved tenant version. This catalog is the governed source
-- a tenant adopts as version one through that workflow.
--
-- Each row freezes one family's field meanings for one baseline version: label,
-- type, requiredness, dictionary reference and free-text language. Requiredness
-- and dictionary entries are therefore approved configuration carried with the
-- version, never a Java constant. A new baseline is a new append-only row, so an
-- old note keeps the meaning of the exact version it referenced.
--
-- This table has no organization_id: it holds the same synthetic definitions for
-- every tenant and no tenant data. RLS is enabled and FORCEd with an explicit
-- permissive policy (the standard tenant policy cannot be written without a
-- tenant key); immutability is bound by triggers for every role, including the
-- owner, and the application is granted SELECT only.
--
-- Lock and volume impact: one small new table and view, five seed rows, no
-- backfill and no update of existing rows. Forward-fix: drop this catalog and
-- view to revert; nothing else depends on them yet.

CREATE TABLE public.clinical_template_baseline (
    kind              text NOT NULL CHECK (kind IN
        ('SOAP', 'OPERATIVE', 'OBGYN', 'PERINATAL', 'PEDIATRIC_DEVELOPMENT')),
    baseline_version  integer NOT NULL CHECK (baseline_version > 0),
    fields            jsonb NOT NULL CHECK (jsonb_typeof(fields) = 'array'),
    dictionaries      jsonb NOT NULL CHECK (jsonb_typeof(dictionaries) = 'object'),
    provenance        text NOT NULL CHECK (btrim(provenance) <> ''),
    created_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, baseline_version)
);

COMMENT ON TABLE public.clinical_template_baseline IS
    'CLN-04 non-tenant governance baselines: frozen field meanings and approved site dictionaries for each template version.';

-- FORCE RLS also binds the migration owner, so the seed needs a policy that
-- admits the row. The permissive policy is safe here because the table has no
-- tenant key; column access and append-only behavior are enforced by GRANT and
-- by the triggers below, not by this policy.
ALTER TABLE public.clinical_template_baseline ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.clinical_template_baseline FORCE ROW LEVEL SECURITY;
CREATE POLICY baseline_catalog_access ON public.clinical_template_baseline
    USING (true) WITH CHECK (true);

-- The shared reject_immutable_mutation() reads OLD.organization_id in its error
-- path and would fail misleadingly on this tenant-free table, so a dedicated
-- function is used. Plain CREATE (not OR REPLACE) matches the V76 convention.
CREATE FUNCTION public.reject_baseline_catalog_mutation()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION '% is append-only (TRUNCATE)', TG_TABLE_NAME USING ERRCODE = '44000';
    END IF;
    RAISE EXCEPTION '% is append-only (%); promote a new baseline version instead',
        TG_TABLE_NAME, TG_OP USING ERRCODE = '44000';
END $$;

CREATE TRIGGER clinical_template_baseline_no_update_delete
    BEFORE UPDATE OR DELETE ON public.clinical_template_baseline
    FOR EACH ROW EXECUTE FUNCTION public.reject_baseline_catalog_mutation();
CREATE TRIGGER clinical_template_baseline_no_truncate
    BEFORE TRUNCATE ON public.clinical_template_baseline
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_baseline_catalog_mutation();

-- Baseline version one for all five families. Field JSON property names are the
-- serialized FieldDefinition record; the free-text language and requiredness are
-- part of the frozen version. Dictionary entries are synthetic site codes.
INSERT INTO public.clinical_template_baseline
    (kind, baseline_version, fields, dictionaries, provenance)
VALUES
    ('SOAP', 1,
     '[{"key":"chiefConcern","label":"chief concern","type":"FREE_TEXT","required":true,"dictionaryKey":null,"language":"en"},{"key":"history","label":"history","type":"FREE_TEXT","required":true,"dictionaryKey":null,"language":"en"},{"key":"exam","label":"examination","type":"FREE_TEXT","required":true,"dictionaryKey":null,"language":"en"},{"key":"assessment","label":"assessment","type":"FREE_TEXT","required":true,"dictionaryKey":null,"language":"en"},{"key":"plan","label":"plan","type":"FREE_TEXT","required":true,"dictionaryKey":null,"language":"en"}]'::jsonb,
     '{}'::jsonb, 'CLN-04 approved synthetic baseline version 1'),
    ('OPERATIVE', 1,
     '[{"key":"procedure","label":"procedure","type":"TEXT","required":true,"dictionaryKey":null,"language":null},{"key":"procedureDate","label":"procedure date","type":"DATE","required":true,"dictionaryKey":null,"language":null},{"key":"team","label":"team","type":"TEXT","required":true,"dictionaryKey":null,"language":null},{"key":"findings","label":"findings","type":"FREE_TEXT","required":true,"dictionaryKey":null,"language":"en"},{"key":"complications","label":"complications","type":"TEXT","required":true,"dictionaryKey":null,"language":null},{"key":"followUp","label":"follow up","type":"TEXT","required":true,"dictionaryKey":null,"language":null}]'::jsonb,
     '{}'::jsonb, 'CLN-04 approved synthetic baseline version 1'),
    ('OBGYN', 1,
     '[{"key":"lmp","label":"last menstrual period","type":"DATE","required":true,"dictionaryKey":null,"language":null},{"key":"obstetricHistory","label":"obstetric history","type":"CODE","required":true,"dictionaryKey":"obstetric_history","language":null}]'::jsonb,
     '{"obstetric_history":["G0P0","G1P0","G2P0","G3P0","G4P0"]}'::jsonb,
     'CLN-04 approved synthetic baseline version 1'),
    ('PERINATAL', 1,
     '[{"key":"birthHistory","label":"birth history","type":"FREE_TEXT","required":true,"dictionaryKey":null,"language":"en"},{"key":"neonatalHistory","label":"neonatal history","type":"FREE_TEXT","required":true,"dictionaryKey":null,"language":"en"}]'::jsonb,
     '{}'::jsonb, 'CLN-04 approved synthetic baseline version 1'),
    ('PEDIATRIC_DEVELOPMENT', 1,
     '[{"key":"milestoneDomain","label":"milestone domain","type":"CODE","required":true,"dictionaryKey":"milestone_domain","language":null},{"key":"observation","label":"observation","type":"FREE_TEXT","required":true,"dictionaryKey":null,"language":"en"},{"key":"date","label":"date","type":"DATE","required":true,"dictionaryKey":null,"language":null}]'::jsonb,
     '{"milestone_domain":["gross_motor","fine_motor","language","social_emotional","cognitive"]}'::jsonb,
     'CLN-04 approved synthetic baseline version 1');

-- Read model: the highest approved baseline per family. A note always names the
-- exact version it validated against, so this fold is only an adoption aid.
CREATE VIEW public.current_clinical_template_baseline AS
    SELECT DISTINCT ON (kind) kind, baseline_version, fields, dictionaries, provenance, created_at
      FROM public.clinical_template_baseline
     ORDER BY kind, baseline_version DESC;

REVOKE ALL ON public.clinical_template_baseline FROM PUBLIC;
REVOKE ALL ON public.current_clinical_template_baseline FROM PUBLIC;
REVOKE ALL ON FUNCTION public.reject_baseline_catalog_mutation() FROM PUBLIC;
GRANT SELECT ON public.clinical_template_baseline TO orevia_app;
GRANT SELECT ON public.current_clinical_template_baseline TO orevia_app;
GRANT EXECUTE ON FUNCTION public.reject_baseline_catalog_mutation() TO orevia_app;
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON public.clinical_template_baseline FROM orevia_app;
