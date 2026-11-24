-- Requirement IDs: XCH-06, PAT-02, ARC-01, ARC-02, OPS-02
-- Change: expand
-- Durable patient chart links, immutable report revisions and append-only release
-- decisions. A patient download authorizes against the current revision state; a
-- withdrawal or correction updates that state and appends a new decision row, so
-- prior disclosures remain provable. Every table is tenant-owned, enforced with
-- row-level security and declared here because V2's static list does not cover new
-- tables. This migration is additive only.
--
-- Local identifiers (report id, patient id, subject) are unique only inside a
-- tenant. Every key therefore includes organization_id, and the forced policy's
-- WITH CHECK rejects a row written for an organization other than the active one.
-- No DELETE or TRUNCATE is granted to the runtime role; release history is also
-- guarded by triggers so the migration owner cannot rewrite it.

CREATE TABLE public.report_chart_link (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    subject         text NOT NULL,
    patient_id      text NOT NULL,
    verified        boolean NOT NULL,
    active          boolean NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, subject)
);

CREATE TABLE public.report_release_revision (
    organization_id      uuid NOT NULL REFERENCES public.organization(organization_id),
    id                   text NOT NULL CHECK (length(btrim(id)) BETWEEN 2 AND 64),
    revision             integer NOT NULL CHECK (revision > 0),
    patient_id           text NOT NULL,
    issuing_organization text NOT NULL,
    effective_date       date NOT NULL,
    language             text NOT NULL CHECK (language IN ('en', 'ar')),
    document_id          text NOT NULL,
    checksum             text NOT NULL,
    status               text NOT NULL CHECK (status IN ('RELEASED', 'WITHDRAWN', 'HELD')),
    correction_revision  integer,
    correction_reason    text,
    policy_version       integer NOT NULL DEFAULT 1 CHECK (policy_version > 0),
    created_at           timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id, revision),
    CHECK ((correction_revision IS NULL) = (correction_reason IS NULL))
);
CREATE INDEX report_release_revision_patient_idx
    ON public.report_release_revision (organization_id, patient_id, effective_date DESC, id, revision);

CREATE TABLE public.report_release_history (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    report_id       text NOT NULL,
    revision        integer NOT NULL CHECK (revision > 0),
    policy_version  integer NOT NULL CHECK (policy_version > 0),
    effective_time  timestamptz NOT NULL,
    hold_reason     text,
    hold_expires_at timestamptz,
    status          text NOT NULL CHECK (status IN ('RELEASED', 'WITHDRAWN', 'HELD')),
    recorded_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    CHECK (status <> 'HELD' OR (
        length(btrim(coalesce(hold_reason, ''))) > 0 AND hold_expires_at IS NOT NULL)),
    FOREIGN KEY (organization_id, report_id, revision)
        REFERENCES public.report_release_revision (organization_id, id, revision)
);
CREATE INDEX report_release_history_revision_idx
    ON public.report_release_history (organization_id, report_id, revision, recorded_at, id);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['report_chart_link', 'report_release_revision', 'report_release_history']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON public.%I
             USING (organization_id = public.app_org())
             WITH CHECK (organization_id = public.app_org())$p$, t);
    END LOOP;

    -- The decision ledger must be immutable for every role, including the
    -- migration owner; a GRANT alone would not cover the owner.
    EXECUTE $d$CREATE TRIGGER report_release_history_no_update_delete
        BEFORE UPDATE OR DELETE ON public.report_release_history
        FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$d$;
    EXECUTE $d$CREATE TRIGGER report_release_history_no_truncate
        BEFORE TRUNCATE ON public.report_release_history
        FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$d$;

    -- A revision is append-only for every role as well: only its current access
    -- state and latest policy version may advance. The identity guard below
    -- covers UPDATE; these triggers cover removal of a revision with no history.
    EXECUTE $d$CREATE TRIGGER report_release_revision_no_delete
        BEFORE DELETE ON public.report_release_revision
        FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$d$;
    -- TRUNCATE is already refused while report_release_history references this
    -- table by a composite foreign key, so no additional trigger is needed.
END $$;

-- The revision's identity and rendered artifact never change; only its current
-- access state and latest policy version may advance with a decision.
CREATE OR REPLACE FUNCTION public.guard_report_revision_update()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.organization_id <> OLD.organization_id
       OR NEW.id <> OLD.id
       OR NEW.revision <> OLD.revision
       OR NEW.patient_id <> OLD.patient_id
       OR NEW.issuing_organization <> OLD.issuing_organization
       OR NEW.effective_date <> OLD.effective_date
       OR NEW.language <> OLD.language
       OR NEW.document_id <> OLD.document_id
       OR NEW.checksum <> OLD.checksum
       OR NEW.correction_revision IS DISTINCT FROM OLD.correction_revision
       OR NEW.correction_reason IS DISTINCT FROM OLD.correction_reason THEN
        RAISE EXCEPTION 'report revision identity is immutable' USING ERRCODE = '42501';
    END IF;
    -- A withdrawal is terminal: a stray UPDATE must not make a withdrawn
    -- patient result downloadable again (XCH-06). The guarded upsert in the
    -- adapter does not touch such a row, so this only blocks direct writes.
    IF OLD.status = 'WITHDRAWN' AND NEW.status <> 'WITHDRAWN' THEN
        RAISE EXCEPTION 'withdrawn report revision cannot be reactivated' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER report_revision_identity_guard
    BEFORE UPDATE ON public.report_release_revision
    FOR EACH ROW EXECUTE FUNCTION public.guard_report_revision_update();

-- Current link and revision state is updated on each protected action; the
-- decision ledger is append-only for the runtime role.
GRANT SELECT, INSERT, UPDATE ON public.report_chart_link TO aurevia_app;
GRANT SELECT, INSERT, UPDATE ON public.report_release_revision TO aurevia_app;
GRANT SELECT, INSERT ON public.report_release_history TO aurevia_app;
REVOKE DELETE, TRUNCATE ON public.report_chart_link FROM aurevia_app;
REVOKE DELETE, TRUNCATE ON public.report_release_revision FROM aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.report_release_history FROM aurevia_app;

REVOKE ALL ON FUNCTION public.guard_report_revision_update() FROM PUBLIC;
