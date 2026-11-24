-- Requirement IDs: OPS-08
-- Change: expand
-- Durable provenance for records admitted by a controlled onboarding import.
-- A dry run writes nothing; the separately authorized commit story inserts one
-- row per imported record. Each row keeps the source identity, the source version
-- and the original historical signature evidence, and is marked as an import
-- that created no new attestation. The CHECK constraints make that promise
-- durable even if an application path is wrong.
--
-- The table is tenant-owned: organization_id is part of every key, forced RLS
-- confines every statement to the active organization, and the runtime role can
-- only read and append. New tables are not covered by V2's static RLS list, so
-- the policy, grants and immutability triggers are declared here.
--
-- Lock and volume impact: CREATE TABLE on a new relation takes no table lock on
-- existing data. Import volume is bounded by the controlled batch limit (100
-- records per batch) in the manifest contract. Forward-fix: drop this table and
-- its policy to revert an unfinished onboarding rollout; no existing table is
-- altered, so the contract step is a plain DROP with no data backfill.

CREATE TABLE public.import_record_provenance (
    organization_id    uuid NOT NULL REFERENCES public.organization(organization_id),
    id                 uuid NOT NULL DEFAULT gen_random_uuid(),
    manifest_version   integer NOT NULL CHECK (manifest_version > 0),
    source_id          text NOT NULL CHECK (btrim(source_id) <> ''),
    source_system      text NOT NULL CHECK (btrim(source_system) <> ''),
    source_version     text NOT NULL CHECK (btrim(source_version) <> ''),
    historical_signature jsonb NOT NULL,
    imported           boolean NOT NULL DEFAULT true CHECK (imported),
    new_attestation    boolean NOT NULL DEFAULT false CHECK (NOT new_attestation),
    created_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, manifest_version, source_id)
);

COMMENT ON TABLE public.import_record_provenance IS
    'OPS-08 append-only import provenance; historical signatures preserved, no new attestation.';

ALTER TABLE public.import_record_provenance ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.import_record_provenance FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.import_record_provenance
    USING (organization_id = public.app_org())
    WITH CHECK (organization_id = public.app_org());

-- Append-only for every role, including the migration owner: a GRANT alone cannot
-- bind the owner, so an immutable-mutation trigger enforces the invariant.
CREATE TRIGGER import_record_provenance_no_update_delete
    BEFORE UPDATE OR DELETE ON public.import_record_provenance
    FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation();
CREATE TRIGGER import_record_provenance_no_truncate
    BEFORE TRUNCATE ON public.import_record_provenance
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation();

GRANT SELECT, INSERT ON public.import_record_provenance TO aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.import_record_provenance FROM aurevia_app;
