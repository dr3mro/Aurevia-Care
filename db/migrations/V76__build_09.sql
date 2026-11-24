-- Requirement IDs: RX-01, ARC-01, ARC-02, OPS-02
-- Change: expand
-- Versioned market-approved medication catalog (RX-01). Adds the approved
-- administration route to each catalog entry, binds every prescription draft to
-- the catalog version it was built from, and creates the append-only registry of
-- published catalog versions. Only new objects are created; no existing table,
-- trigger or function from V43 is rewritten.
--
-- Both existing tables are append-only for every role (V43 triggers), so the new
-- columns are added as additive metadata. A constant DEFAULT is metadata-only in
-- supported PostgreSQL versions and does not rewrite the stored rows. Legacy
-- tenants with no published version read the documented baseline version 1.

DO $rx01$
BEGIN
    IF to_regclass('public.organization') IS NULL
       OR to_regclass('public.drug_catalog_entry') IS NULL
       OR to_regclass('public.prescription_draft') IS NULL
       OR NOT EXISTS (
              SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'app_org')
       OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'orevia_app') THEN
        RAISE NOTICE 'V76 medication catalog version deferred: prerequisites not installed yet';
        RETURN;
    END IF;

    EXECUTE $ddl$ALTER TABLE public.drug_catalog_entry
        ADD COLUMN IF NOT EXISTS route text NOT NULL DEFAULT 'unspecified'$ddl$;
    EXECUTE $ddl$ALTER TABLE public.prescription_draft
        ADD COLUMN IF NOT EXISTS catalog_version integer$ddl$;

    EXECUTE $ddl$CREATE TABLE public.medication_catalog_version (
        organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
        version         integer NOT NULL CHECK (version > 0),
        market          text NOT NULL CHECK (length(trim(market)) > 0),
        published_at    timestamptz NOT NULL DEFAULT now(),
        provenance      text NOT NULL CHECK (length(trim(provenance)) > 0),
        PRIMARY KEY (organization_id, version)
    )$ddl$;
    EXECUTE $ddl$CREATE INDEX medication_catalog_version_current_idx
        ON public.medication_catalog_version (organization_id, version DESC)$ddl$;

    EXECUTE $ddl$ALTER TABLE public.medication_catalog_version ENABLE ROW LEVEL SECURITY$ddl$;
    EXECUTE $ddl$ALTER TABLE public.medication_catalog_version FORCE ROW LEVEL SECURITY$ddl$;
    EXECUTE $ddl$CREATE POLICY tenant_isolation ON public.medication_catalog_version
        USING (organization_id = public.app_org())
        WITH CHECK (organization_id = public.app_org())$ddl$;

    -- A new helper name is used deliberately: V43's immutability function is
    -- shared by several tables and must not be replaced or re-granted here.
    EXECUTE $ddl$CREATE FUNCTION public.reject_catalog_version_mutation()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    BEGIN
        IF TG_OP = 'TRUNCATE' THEN
            RAISE EXCEPTION '% is append-only (TRUNCATE)', TG_TABLE_NAME USING ERRCODE = '44000';
        END IF;
        RAISE EXCEPTION '% is append-only (%; organization_id=%)',
            TG_TABLE_NAME, TG_OP, OLD.organization_id USING ERRCODE = '44000';
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER medication_catalog_version_no_update_delete
        BEFORE UPDATE OR DELETE ON public.medication_catalog_version
        FOR EACH ROW EXECUTE FUNCTION public.reject_catalog_version_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER medication_catalog_version_no_truncate
        BEFORE TRUNCATE ON public.medication_catalog_version
        FOR EACH STATEMENT EXECUTE FUNCTION public.reject_catalog_version_mutation()$ddl$;

    EXECUTE $ddl$GRANT SELECT, INSERT ON public.medication_catalog_version TO orevia_app$ddl$;
    EXECUTE $ddl$REVOKE UPDATE, DELETE, TRUNCATE ON public.medication_catalog_version FROM orevia_app$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.reject_catalog_version_mutation() FROM PUBLIC$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.reject_catalog_version_mutation() TO orevia_app$ddl$;
END $rx01$;
