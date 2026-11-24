-- Requirement IDs: OPS-07
-- Change: expand
-- Durable job metadata for tenant export artifacts. One job is written per
-- organization; the manifest itself and each authorized file live in the
-- private object store behind the ObjectStorePort, and this row records the
-- manifest checksum and its expiry instant. Source documents, provenance and
-- audit history are read from the existing document and append-only audit
-- tables, so no source data is duplicated here.
--
-- The table is tenant-owned: organization_id is part of the key, forced RLS
-- confines every statement to the active organization, and the runtime role
-- can insert, read and advance the lifecycle status but cannot delete history.
-- New tables are not covered by V2's static RLS list, so the policy, grants and
-- immutability of identity columns are declared here. Status is the only
-- mutable column and is guarded by a trigger that forbids leaving EXPIRED.
--
-- Lock and volume impact: CREATE TABLE on a new relation takes no table lock on
-- existing data. Export volume is bounded by the released documents in one
-- tenant. Forward-fix: drop this table and its policy to revert an unfinished
-- export rollout; no existing table is altered and no data is backfilled.

CREATE TABLE public.export_job (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    status          text NOT NULL CHECK (status IN ('COMPLETE', 'EXPIRED')),
    object_id       text NOT NULL CHECK (btrim(object_id) <> ''),
    object_version  integer NOT NULL CHECK (object_version > 0),
    checksum        text NOT NULL CHECK (checksum ~ '^[0-9a-f]{64}$'),
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    PRIMARY KEY (organization_id, id)
);

COMMENT ON TABLE public.export_job IS
    'OPS-07 tenant export job: manifest checksum, object identity and expiry.';

ALTER TABLE public.export_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.export_job FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.export_job
    USING (organization_id = public.app_org())
    WITH CHECK (organization_id = public.app_org());

CREATE OR REPLACE FUNCTION public.export_job_binding_immutable()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.organization_id IS DISTINCT FROM OLD.organization_id
       OR NEW.id IS DISTINCT FROM OLD.id
       OR NEW.object_id IS DISTINCT FROM OLD.object_id
       OR NEW.object_version IS DISTINCT FROM OLD.object_version
       OR NEW.checksum IS DISTINCT FROM OLD.checksum
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
       OR OLD.status = 'EXPIRED' THEN
        RAISE EXCEPTION 'export job binding is immutable (id=%)', OLD.id
            USING ERRCODE = '44000';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER export_job_binding_guard
    BEFORE UPDATE ON public.export_job
    FOR EACH ROW EXECUTE FUNCTION public.export_job_binding_immutable();
CREATE TRIGGER export_job_no_delete
    BEFORE DELETE ON public.export_job
    FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation();
CREATE TRIGGER export_job_no_truncate
    BEFORE TRUNCATE ON public.export_job
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation();

GRANT SELECT, INSERT, UPDATE ON public.export_job TO orevia_app;
REVOKE DELETE, TRUNCATE ON public.export_job FROM orevia_app;

CREATE INDEX export_job_created_idx ON public.export_job (organization_id, created_at DESC);
