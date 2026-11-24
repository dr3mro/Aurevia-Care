-- Requirement IDs: CLN-05, ARC-01, ARC-02, ARC-03, OPS-02
-- Change: expand
--
-- Draft-to-sign clinical documentation ledger (CLN-05). One row per immutable
-- revision, keyed by (organization_id, note id, version). Drafts and amendment
-- drafts are editable only by appending a new version; signing, addenda and
-- entered-in-error append further immutable revisions. No signed row is ever
-- updated or deleted, so the original is preserved by construction.
--
-- Version, actors and content checksum are trigger-owned: a caller cannot chose
-- a version, attribute an action to another user, or submit a trusted checksum.
-- A self-referential prior-version key and a successor guard prevent stale,
-- foreign or forked lineage. Forced RLS from V2 applies to every row.
--
-- encounter_id is deliberately a plain uuid: the encounter table (V12, S-G1-02)
-- is not part of this baseline. A follow-up migration must add the composite
-- (organization_id, patient_id, encounter_id) foreign key when that table lands.

CREATE TABLE public.clinical_note_revision (
    organization_id       uuid NOT NULL REFERENCES public.organization(organization_id),
    id                    uuid NOT NULL,
    version               integer NOT NULL CHECK (version > 0),
    state                 text NOT NULL
        CHECK (state IN ('draft', 'signed', 'amendment_draft', 'addendum', 'entered_in_error')),
    patient_id            uuid NOT NULL,
    encounter_id          uuid,
    title                 text NOT NULL CHECK (btrim(title) <> ''),
    content               text NOT NULL CHECK (btrim(content) <> ''),
    language              text NOT NULL CHECK (language IN ('en', 'ar')),
    template_code         text NOT NULL CHECK (btrim(template_code) <> ''),
    template_version      integer NOT NULL CHECK (template_version > 0),
    clinical_author_subject text NOT NULL,
    data_entry_subject    text NOT NULL,
    effective_at          timestamptz NOT NULL,
    signed_at             timestamptz,
    signer_subject        text,
    attestation           text,
    content_checksum      text NOT NULL CHECK (content_checksum ~ '^[0-9a-f]{64}$'),
    supersedes_version    integer,
    entered_error_reason  text,
    entered_error_actor   text,
    affected_recipients   jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at            timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id, version),
    CHECK (supersedes_version IS NULL OR supersedes_version < version),
    CHECK (
        (state IN ('draft', 'amendment_draft')
            AND signed_at IS NULL AND signer_subject IS NULL AND attestation IS NULL)
        OR (state IN ('signed', 'addendum')
            AND signed_at IS NOT NULL AND signer_subject IS NOT NULL
            AND btrim(attestation) <> '' AND content_checksum IS NOT NULL)
        OR (state = 'entered_in_error'
            AND entered_error_reason IS NOT NULL AND btrim(entered_error_reason) <> ''
            AND entered_error_actor IS NOT NULL
            AND jsonb_typeof(affected_recipients) = 'array')
    ),
    CHECK (signed_at IS NULL OR effective_at IS NULL OR signed_at >= effective_at),
    FOREIGN KEY (organization_id, patient_id)
        REFERENCES public.patient_record(organization_id, id),
    -- A prior version must exist in the same tenant and the same note lineage.
    FOREIGN KEY (organization_id, id, supersedes_version)
        REFERENCES public.clinical_note_revision(organization_id, id, version),
    FOREIGN KEY (organization_id, clinical_author_subject)
        REFERENCES public.user_identity(organization_id, subject),
    FOREIGN KEY (organization_id, data_entry_subject)
        REFERENCES public.user_identity(organization_id, subject),
    FOREIGN KEY (organization_id, signer_subject)
        REFERENCES public.user_identity(organization_id, subject),
    FOREIGN KEY (organization_id, entered_error_actor)
        REFERENCES public.user_identity(organization_id, subject)
);

CREATE INDEX clinical_note_chart_idx ON public.clinical_note_revision
    (organization_id, patient_id, id, version DESC);
CREATE INDEX clinical_note_signed_idx ON public.clinical_note_revision
    (organization_id, signed_at DESC) WHERE signed_at IS NOT NULL;
CREATE INDEX clinical_note_author_idx ON public.clinical_note_revision
    (organization_id, clinical_author_subject, created_at DESC);
CREATE INDEX clinical_note_error_idx ON public.clinical_note_revision
    (organization_id, created_at DESC) WHERE state = 'entered_in_error';

DO $$
BEGIN
    EXECUTE 'ALTER TABLE public.clinical_note_revision ENABLE ROW LEVEL SECURITY';
    EXECUTE 'ALTER TABLE public.clinical_note_revision FORCE ROW LEVEL SECURITY';
    EXECUTE $p$CREATE POLICY tenant_isolation ON public.clinical_note_revision
         USING (organization_id = public.app_org())
         WITH CHECK (organization_id = public.app_org())$p$;
END $$;

-- History is append-only for every role, including the migration owner.
CREATE TRIGGER clinical_note_revision_no_update_delete
    BEFORE UPDATE OR DELETE ON public.clinical_note_revision
    FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation();
CREATE TRIGGER clinical_note_revision_no_truncate
    BEFORE TRUNCATE ON public.clinical_note_revision
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation();

-- Version is trigger-owned per note under an advisory lock, as in V7. The same
-- lock key is taken by the service before it reads the head, so a stale If-Match
-- cannot advance a revision that another writer has already changed.
CREATE OR REPLACE FUNCTION public.next_clinical_note_version(p_org uuid, p_id uuid)
RETURNS integer LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_tenant uuid := public.app_org();
BEGIN
    IF v_tenant IS NULL OR p_org IS DISTINCT FROM v_tenant THEN
        RAISE EXCEPTION 'tenant context required for clinical note version'
            USING ERRCODE = '42501';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(p_org::text || ':' || p_id::text, 0));
    RETURN coalesce((SELECT max(version) FROM public.clinical_note_revision
                      WHERE organization_id = p_org AND id = p_id), 0) + 1;
END $$;

-- A trigger function returning integer is invalid for a BEFORE trigger; wrap the
-- version function in a trigger-shaped function that assigns NEW.version.
CREATE OR REPLACE FUNCTION public.next_clinical_note_version_stamp()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    NEW.version := public.next_clinical_note_version(NEW.organization_id, NEW.id);
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION public.stamp_clinical_note_revision_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_subject text;
BEGIN
    SELECT u.subject INTO v_subject FROM public.user_identity u
     WHERE u.organization_id = NEW.organization_id
       AND u.subject = NULLIF(current_setting('app.actor', true), '');
    IF v_subject IS NULL THEN
        RAISE EXCEPTION 'clinical note revision requires an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.data_entry_subject := v_subject;
    -- Single-field canonical material: SHA-256 of the UTF-8 content bytes. The
    -- application computes the identical value; the database value is authoritative.
    NEW.content_checksum := encode(sha256(convert_to(NEW.content, 'UTF8')), 'hex');
    IF NEW.state IN ('signed', 'addendum') THEN
        NEW.signer_subject := v_subject;
        NEW.signed_at := coalesce(NEW.signed_at, clock_timestamp());
    END IF;
    IF NEW.state = 'entered_in_error' THEN
        NEW.entered_error_actor := v_subject;
    END IF;
    RETURN NEW;
END $$;

-- Transitions are inserts. Guard the predecessor so a signed revision cannot be
-- bypassed, an original cannot be replaced, and a single version cannot be
-- superseded twice.
CREATE OR REPLACE FUNCTION public.guard_clinical_note_revision_succession()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_prior_state text;
BEGIN
    IF NEW.supersedes_version IS NULL THEN
        IF NEW.state <> 'draft' THEN
            RAISE EXCEPTION 'a non-draft revision requires a prior version'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    SELECT r.state INTO v_prior_state
      FROM public.clinical_note_revision r
     WHERE r.organization_id = NEW.organization_id
       AND r.id = NEW.id AND r.version = NEW.supersedes_version;
    IF v_prior_state IS NULL THEN
        RAISE EXCEPTION 'prior clinical note revision is not visible'
            USING ERRCODE = '23514';
    END IF;
    IF NOT (
            (NEW.state = 'draft'           AND v_prior_state IN ('draft', 'amendment_draft'))
         OR (NEW.state = 'signed'           AND v_prior_state = 'draft')
         OR (NEW.state = 'amendment_draft'  AND v_prior_state IN ('draft', 'amendment_draft', 'signed', 'addendum'))
         OR (NEW.state = 'addendum'         AND v_prior_state = 'amendment_draft')
         OR (NEW.state = 'entered_in_error' AND v_prior_state IN ('signed', 'addendum'))
       ) THEN
        RAISE EXCEPTION 'illegal clinical note revision predecessor: % -> %',
            v_prior_state, NEW.state USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM public.clinical_note_revision c
                WHERE c.organization_id = NEW.organization_id AND c.id = NEW.id
                  AND c.supersedes_version = NEW.supersedes_version) THEN
        RAISE EXCEPTION 'prior clinical note revision is already superseded'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

-- BEFORE triggers fire in name order; version must precede actor/checksum, and
-- the successor guard must see the trigger-assigned version.
CREATE TRIGGER clinical_note_01_version
    BEFORE INSERT ON public.clinical_note_revision
    FOR EACH ROW EXECUTE FUNCTION public.next_clinical_note_version_stamp();
CREATE TRIGGER clinical_note_02_actor
    BEFORE INSERT ON public.clinical_note_revision
    FOR EACH ROW EXECUTE FUNCTION public.stamp_clinical_note_revision_actor();
CREATE TRIGGER clinical_note_03_succession
    BEFORE INSERT ON public.clinical_note_revision
    FOR EACH ROW EXECUTE FUNCTION public.guard_clinical_note_revision_succession();

-- Effective current revision fold for reads, mirroring V7's current_patients().
CREATE OR REPLACE FUNCTION public.current_clinical_note_revision()
RETURNS TABLE (
    id uuid, version integer, state text, patient_id uuid, encounter_id uuid,
    title text, content text, language text, template_code text, template_version integer,
    clinical_author_subject text, data_entry_subject text, effective_at timestamptz,
    signed_at timestamptz, signer_subject text, attestation text, content_checksum text,
    supersedes_version integer, entered_error_reason text, entered_error_actor text,
    affected_recipients jsonb, created_at timestamptz
)
LANGUAGE sql STABLE SET search_path = pg_catalog
AS $$
    SELECT DISTINCT ON (r.organization_id, r.id)
           r.id, r.version, r.state, r.patient_id, r.encounter_id, r.title, r.content,
           r.language, r.template_code, r.template_version, r.clinical_author_subject,
           r.data_entry_subject, r.effective_at, r.signed_at, r.signer_subject,
           r.attestation, r.content_checksum, r.supersedes_version,
           r.entered_error_reason, r.entered_error_actor, r.affected_recipients, r.created_at
      FROM public.clinical_note_revision r
     ORDER BY r.organization_id, r.id, r.version DESC
$$;

REVOKE ALL ON FUNCTION public.next_clinical_note_version(uuid, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.next_clinical_note_version_stamp() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.stamp_clinical_note_revision_actor() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_clinical_note_revision_succession() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.current_clinical_note_revision() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.next_clinical_note_version(uuid, uuid) TO aurevia_app;
GRANT EXECUTE ON FUNCTION public.next_clinical_note_version_stamp() TO aurevia_app;
GRANT EXECUTE ON FUNCTION public.stamp_clinical_note_revision_actor() TO aurevia_app;
GRANT EXECUTE ON FUNCTION public.guard_clinical_note_revision_succession() TO aurevia_app;
GRANT EXECUTE ON FUNCTION public.current_clinical_note_revision() TO aurevia_app;

GRANT SELECT, INSERT ON public.clinical_note_revision TO aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.clinical_note_revision FROM aurevia_app;
