-- Requirement IDs: CLN-07, ARC-01, ARC-02, ARC-03, OPS-02
-- Original PDF/image associations and immutable localized PDF renders.
--
-- Original bytes stay in bytea inside the tenant boundary: no object-storage
-- adapter exists yet (ARC-06/X-03 is a separate story). The checksum and byte
-- size are derived by a BEFORE INSERT trigger from the stored bytes, so a caller
-- cannot forge them. Rotation is a separate presentation column and never
-- changes the original. Voiding is a guarded state transition that appends an
-- event and preserves the original and its history.
--
-- Subjects are stored as a validated (type, id) pair. Order and result tables
-- do not exist until the G2 stories, so no cross-module foreign keys are added;
-- chart subjects are additionally checked against current_patients() in the
-- service. Verified patient anchoring for order/result follows those modules.
--
-- Forced RLS and the append-only helper are re-declared here identically because
-- db/check.sh applies files in raw glob order: a two-digit version can run before
-- V2/V4. V2/V4 later replace these with the same definitions, so one copy of each
-- function remains after a complete migration run.

CREATE OR REPLACE FUNCTION public.app_org() RETURNS uuid
LANGUAGE sql STABLE SET search_path = pg_catalog
AS $$ SELECT NULLIF(current_setting('app.current_org', true), '')::uuid $$;
GRANT EXECUTE ON FUNCTION public.app_org() TO orevia_app;

CREATE OR REPLACE FUNCTION public.reject_immutable_mutation()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION '% is append-only (TRUNCATE)', TG_TABLE_NAME USING ERRCODE = '44000';
    END IF;
    RAISE EXCEPTION '% is append-only (%; organization_id=%)',
        TG_TABLE_NAME, TG_OP, OLD.organization_id USING ERRCODE = '44000';
END $$;

CREATE TABLE public.document_association (
    organization_id  uuid NOT NULL REFERENCES public.organization(organization_id),
    id               uuid NOT NULL DEFAULT gen_random_uuid(),
    version          integer NOT NULL DEFAULT 1 CHECK (version > 0),
    subject_type     text NOT NULL CHECK (subject_type IN ('chart', 'order', 'result')),
    subject_id       uuid NOT NULL,
    file_name        text NOT NULL CHECK (length(file_name) BETWEEN 1 AND 180),
    media_type       text NOT NULL
        CHECK (media_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    byte_size        bigint NOT NULL,
    content          bytea NOT NULL,
    sha256           text NOT NULL,
    rotation_degrees smallint NOT NULL DEFAULT 0 CHECK (rotation_degrees IN (0, 90, 180, 270)),
    status           text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'voided')),
    void_reason      text,
    created_by       uuid NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    voided_at        timestamptz,
    PRIMARY KEY (organization_id, id),
    CHECK (octet_length(content) BETWEEN 1 AND 1048576),
    CHECK (status <> 'voided' OR void_reason IS NOT NULL),
    FOREIGN KEY (organization_id, created_by)
        REFERENCES public.user_identity(organization_id, id)
);

CREATE INDEX document_association_subject_idx
    ON public.document_association (organization_id, subject_type, subject_id, created_at DESC);

CREATE TABLE public.document_association_event (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    association_id  uuid NOT NULL,
    action          text NOT NULL CHECK (action IN ('link', 'void')),
    reason          text,
    actor_id        uuid NOT NULL,
    occurred_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, association_id, action),
    FOREIGN KEY (organization_id, association_id)
        REFERENCES public.document_association(organization_id, id),
    FOREIGN KEY (organization_id, actor_id)
        REFERENCES public.user_identity(organization_id, id)
);

CREATE INDEX document_association_event_history_idx
    ON public.document_association_event (organization_id, association_id, occurred_at, id);

CREATE TABLE public.rendered_document (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    subject_type    text NOT NULL CHECK (subject_type IN ('chart', 'order', 'result')),
    subject_id      uuid NOT NULL,
    version         integer NOT NULL CHECK (version > 0),
    locale          text NOT NULL CHECK (locale IN ('en', 'ar')),
    direction       text NOT NULL CHECK (direction IN ('LTR', 'RTL')),
    source_system   text NOT NULL,
    source_id       text NOT NULL,
    effective_at    text NOT NULL,
    page_count      integer NOT NULL DEFAULT 1 CHECK (page_count > 0),
    byte_size       bigint NOT NULL,
    sha256          text NOT NULL,
    content         bytea NOT NULL,
    created_by      uuid NOT NULL,
    rendered_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, subject_type, subject_id, locale, version),
    CHECK (octet_length(content) BETWEEN 1 AND 1048576),
    CHECK ((locale = 'en' AND direction = 'LTR') OR (locale = 'ar' AND direction = 'RTL')),
    FOREIGN KEY (organization_id, created_by)
        REFERENCES public.user_identity(organization_id, id)
);

CREATE INDEX rendered_document_versions_idx
    ON public.rendered_document (organization_id, subject_type, subject_id, locale, version);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['document_association', 'document_association_event',
                             'rendered_document']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            $p$CREATE POLICY tenant_isolation ON public.%I
                 USING (organization_id = public.app_org())
                 WITH CHECK (organization_id = public.app_org())$p$, t);
    END LOOP;
END $$;

-- The checksum and size always describe the exact retained bytes.
CREATE OR REPLACE FUNCTION public.set_document_original_digest()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    NEW.byte_size := octet_length(NEW.content);
    NEW.sha256 := encode(sha256(NEW.content), 'hex');
    RETURN NEW;
END $$;
CREATE TRIGGER document_association_digest
    BEFORE INSERT ON public.document_association
    FOR EACH ROW EXECUTE FUNCTION public.set_document_original_digest();

CREATE TRIGGER rendered_document_digest
    BEFORE INSERT ON public.rendered_document
    FOR EACH ROW EXECUTE FUNCTION public.set_document_original_digest();

-- The author is the authenticated actor of the active tenant, never caller supplied.
CREATE OR REPLACE FUNCTION public.set_document_created_by_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'document author must be an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.created_by := v_actor;
    RETURN NEW;
END $$;
CREATE TRIGGER document_association_author
    BEFORE INSERT ON public.document_association
    FOR EACH ROW EXECUTE FUNCTION public.set_document_created_by_from_actor();
CREATE TRIGGER rendered_document_author
    BEFORE INSERT ON public.rendered_document
    FOR EACH ROW EXECUTE FUNCTION public.set_document_created_by_from_actor();

CREATE OR REPLACE FUNCTION public.set_document_event_actor_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'document history actor must be an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.actor_id := v_actor;
    RETURN NEW;
END $$;
CREATE TRIGGER document_association_event_actor
    BEFORE INSERT ON public.document_association_event
    FOR EACH ROW EXECUTE FUNCTION public.set_document_event_actor_from_actor();

-- Only active -> voided is writable. Identity, original bytes, digest and
-- rotation are pinned; the version advances by exactly one and the reason is
-- mandatory. NULL comparisons use IS DISTINCT FROM so the trigger is strict.
CREATE OR REPLACE FUNCTION public.guard_document_association_update()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
       OR NEW.subject_type IS DISTINCT FROM OLD.subject_type
       OR NEW.subject_id IS DISTINCT FROM OLD.subject_id
       OR NEW.file_name IS DISTINCT FROM OLD.file_name
       OR NEW.media_type IS DISTINCT FROM OLD.media_type
       OR NEW.byte_size IS DISTINCT FROM OLD.byte_size
       OR NEW.sha256 IS DISTINCT FROM OLD.sha256
       OR NEW.content IS DISTINCT FROM OLD.content
       OR NEW.rotation_degrees IS DISTINCT FROM OLD.rotation_degrees
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'association identity and original bytes are immutable'
            USING ERRCODE = '42501';
    END IF;
    IF OLD.status <> 'active' OR NEW.status <> 'voided' THEN
        RAISE EXCEPTION 'an association may only move from active to voided'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.version <> OLD.version + 1 OR NEW.void_reason IS NULL THEN
        RAISE EXCEPTION 'a void must carry a reason and advance exactly one version'
            USING ERRCODE = '23514';
    END IF;
    NEW.voided_at := clock_timestamp();
    RETURN NEW;
END $$;
CREATE TRIGGER document_association_update_guard
    BEFORE UPDATE ON public.document_association
    FOR EACH ROW EXECUTE FUNCTION public.guard_document_association_update();

-- Version reservation is serialized inside the caller's transaction per
-- (tenant, subject, locale), matching V7's advisory-lock fold.
CREATE OR REPLACE FUNCTION public.next_render_version(
    p_subject_type text, p_subject_id uuid, p_locale text)
RETURNS integer LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_version integer;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        public.app_org()::text || '|' || p_subject_type || '|'
        || p_subject_id::text || '|' || p_locale, 0));
    SELECT COALESCE(MAX(version), 0) + 1 INTO v_version
      FROM public.rendered_document
     WHERE subject_type = p_subject_type AND subject_id = p_subject_id AND locale = p_locale;
    RETURN v_version;
END $$;

-- History and renders are append-only for every role.
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['document_association_event', 'rendered_document']
    LOOP
        EXECUTE format($d$CREATE TRIGGER %I_no_update_delete
            BEFORE UPDATE OR DELETE ON public.%I
            FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
        EXECUTE format($d$CREATE TRIGGER %I_no_truncate
            BEFORE TRUNCATE ON public.%I
            FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
    END LOOP;
END $$;

CREATE TRIGGER document_association_no_delete
    BEFORE DELETE ON public.document_association
    FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation();
CREATE TRIGGER document_association_no_truncate
    BEFORE TRUNCATE ON public.document_association
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation();

GRANT SELECT, INSERT ON public.document_association, public.document_association_event,
                         public.rendered_document TO orevia_app;
GRANT UPDATE (status, version, void_reason) ON public.document_association TO orevia_app;
REVOKE DELETE, TRUNCATE ON public.document_association FROM orevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.document_association_event,
                                  public.rendered_document FROM orevia_app;

REVOKE ALL ON FUNCTION public.set_document_original_digest() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.set_document_created_by_from_actor() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.set_document_event_actor_from_actor() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_document_association_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.next_render_version(text, uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.next_render_version(text, uuid, text) TO orevia_app;
