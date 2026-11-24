-- Requirement IDs: XCH-04, ARC-01, ARC-02, ARC-03, OPS-02
-- Change: expand
--
-- S-G2-04 result routing (XCH-04). Self-contained slice: sibling exchange
-- stories (V19-V21) are not present on this branch, so the expected-order
-- projection this story matches against is registered locally and every table
-- carries an xch04_ prefix to avoid collisions when those stories merge.
--
-- Invariant: an inbound result is imported only after all five issuer-scoped
-- identifiers (patient, source, accession, destination, order) agree with the
-- locally registered expectation. Unmatched or conflicting reports go to a
-- reconciliation queue without creating a clinical review task. Corrections
-- append a new immutable revision; the prior revision, its acknowledgment and
-- the prior recipients are preserved, and the new revision starts unreviewed.
--
-- Forced RLS from V2 is declared explicitly per table (V2 policies do not cover
-- later tables). Every foreign key includes organization_id so a cross-tenant
-- reference cannot resolve under the non-owner runtime role.

CREATE TABLE public.xch04_expected_order (
    organization_id  uuid NOT NULL REFERENCES public.organization(organization_id),
    id               uuid NOT NULL DEFAULT gen_random_uuid(),
    order_ref        text NOT NULL,
    patient_id       uuid NOT NULL,
    source_code      text NOT NULL,
    accession        text NOT NULL,
    destination_code text NOT NULL,
    owner_subject    text NOT NULL,
    coverage_queue   text NOT NULL,
    created_by       uuid NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, order_ref),
    CHECK (length(order_ref) BETWEEN 1 AND 128),
    CHECK (length(source_code) BETWEEN 1 AND 128),
    CHECK (length(accession) BETWEEN 1 AND 128),
    CHECK (length(destination_code) BETWEEN 1 AND 128),
    CHECK (length(owner_subject) BETWEEN 1 AND 128),
    CHECK (length(coverage_queue) BETWEEN 1 AND 128),
    FOREIGN KEY (organization_id, patient_id)
        REFERENCES public.patient_record(organization_id, id),
    FOREIGN KEY (organization_id, created_by)
        REFERENCES public.user_identity(organization_id, id)
);

CREATE TABLE public.xch04_report_revision (
    organization_id     uuid NOT NULL REFERENCES public.organization(organization_id),
    id                  uuid NOT NULL DEFAULT gen_random_uuid(),
    version             integer NOT NULL CHECK (version > 0),
    order_ref           text NOT NULL,
    patient_id          uuid NOT NULL,
    source_code         text NOT NULL,
    accession           text NOT NULL,
    destination_code    text NOT NULL,
    kind                text NOT NULL CHECK (kind IN ('final', 'corrected', 'critical')),
    supersedes_version  integer,
    previous_id         uuid,
    owner_subject       text NOT NULL,
    coverage_queue      text NOT NULL,
    effective_at        timestamptz NOT NULL,
    received_at         timestamptz NOT NULL DEFAULT now(),
    checksum            text NOT NULL,
    created_by          uuid NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    -- The order reference is part of the lineage key: two orders in one tenant
    -- may carry the same issuer accession, and their revisions must not collide.
    UNIQUE (organization_id, order_ref, source_code, accession, version),
    CHECK (supersedes_version IS NULL OR supersedes_version > 0),
    CHECK ((kind = 'corrected') = (supersedes_version IS NOT NULL)),
    CHECK ((supersedes_version IS NULL) = (previous_id IS NULL)),
    CHECK (length(checksum) BETWEEN 1 AND 256),
    FOREIGN KEY (organization_id, patient_id)
        REFERENCES public.patient_record(organization_id, id),
    FOREIGN KEY (organization_id, previous_id)
        REFERENCES public.xch04_report_revision(organization_id, id),
    FOREIGN KEY (organization_id, created_by)
        REFERENCES public.user_identity(organization_id, id)
);

CREATE TABLE public.xch04_reconciliation (
    organization_id  uuid NOT NULL REFERENCES public.organization(organization_id),
    id               uuid NOT NULL DEFAULT gen_random_uuid(),
    order_ref        text NOT NULL,
    patient_id       uuid NOT NULL,
    source_code      text NOT NULL,
    accession        text NOT NULL,
    destination_code text NOT NULL,
    reason           text NOT NULL CHECK (reason IN ('unmatched', 'conflict')),
    mismatches       text NOT NULL DEFAULT '',
    detail           text NOT NULL,
    status           text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved')),
    created_by       uuid NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    FOREIGN KEY (organization_id, patient_id)
        REFERENCES public.patient_record(organization_id, id),
    FOREIGN KEY (organization_id, created_by)
        REFERENCES public.user_identity(organization_id, id)
);

CREATE TABLE public.xch04_notification (
    organization_id    uuid NOT NULL REFERENCES public.organization(organization_id),
    id                 uuid NOT NULL DEFAULT gen_random_uuid(),
    report_id          uuid NOT NULL,
    recipient_subject  text NOT NULL,
    superseded_version integer NOT NULL CHECK (superseded_version > 0),
    created_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, report_id, recipient_subject, superseded_version),
    FOREIGN KEY (organization_id, report_id)
        REFERENCES public.xch04_report_revision(organization_id, id)
);

-- Clinical review is a separate mutable task bound to one immutable revision.
-- Acknowledging the prior revision is never rewritten by a correction: the
-- correction gets its own unreviewed task, which is how "reopen review even
-- after acknowledgment" is represented without losing the original attestation.
CREATE TABLE public.xch04_review (
    organization_id  uuid NOT NULL REFERENCES public.organization(organization_id),
    id               uuid NOT NULL DEFAULT gen_random_uuid(),
    report_id        uuid NOT NULL,
    version          integer NOT NULL DEFAULT 1 CHECK (version > 0),
    review_state     text NOT NULL DEFAULT 'unreviewed'
                         CHECK (review_state IN ('unreviewed', 'acknowledged')),
    acknowledged_at  timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, report_id),
    CHECK ((review_state = 'acknowledged') = (acknowledged_at IS NOT NULL)),
    FOREIGN KEY (organization_id, report_id)
        REFERENCES public.xch04_report_revision(organization_id, id)
);

CREATE INDEX xch04_report_lineage_idx ON public.xch04_report_revision
    (organization_id, order_ref, source_code, accession, version DESC);
CREATE INDEX xch04_reconciliation_queue_idx ON public.xch04_reconciliation
    (organization_id, status, created_at DESC);
CREATE INDEX xch04_notification_report_idx ON public.xch04_notification
    (organization_id, report_id);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['xch04_expected_order', 'xch04_report_revision',
                             'xch04_reconciliation', 'xch04_notification', 'xch04_review']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            $p$CREATE POLICY tenant_isolation ON public.%I
                 USING (organization_id = public.app_org())
                 WITH CHECK (organization_id = public.app_org())$p$, t);
    END LOOP;
END $$;

-- The result, expectation, reconciliation and notification ledgers are
-- insert-only in this slice. A correction appends a new revision; it never
-- rewrites a prior one. Queue resolution and escalation are separate stories
-- and can widen the grants through a later expand migration. The review task
-- is the one mutable table and is guarded below.
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['xch04_expected_order', 'xch04_report_revision',
                             'xch04_reconciliation', 'xch04_notification']
    LOOP
        EXECUTE format($d$CREATE TRIGGER %I_no_update_delete
            BEFORE UPDATE OR DELETE ON public.%I
            FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
        EXECUTE format($d$CREATE TRIGGER %I_no_truncate
            BEFORE TRUNCATE ON public.%I
            FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
    END LOOP;
END $$;

-- The author of a registered order or an imported revision is derived from the
-- transaction-local actor, never from a caller-supplied value.
CREATE OR REPLACE FUNCTION public.set_xch04_created_by_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'xch04 author must be an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.created_by := v_actor;
    RETURN NEW;
END $$;

CREATE TRIGGER xch04_expected_order_created_by_actor
    BEFORE INSERT ON public.xch04_expected_order
    FOR EACH ROW EXECUTE FUNCTION public.set_xch04_created_by_from_actor();
CREATE TRIGGER xch04_report_revision_created_by_actor
    BEFORE INSERT ON public.xch04_report_revision
    FOR EACH ROW EXECUTE FUNCTION public.set_xch04_created_by_from_actor();
CREATE TRIGGER xch04_reconciliation_created_by_actor
    BEFORE INSERT ON public.xch04_reconciliation
    FOR EACH ROW EXECUTE FUNCTION public.set_xch04_created_by_from_actor();

-- A review task may advance only from unreviewed to acknowledged, keep its
-- identity and revision, and advance its version by exactly one. The actor and
-- timestamp are server-owned so a caller cannot backdate an acknowledgment.
CREATE OR REPLACE FUNCTION public.guard_xch04_review_update()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.id <> OLD.id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.report_id <> OLD.report_id
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'review task identity columns are immutable'
            USING ERRCODE = '42501';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'review task version must advance by exactly one'
            USING ERRCODE = '23514';
    END IF;
    IF NOT (OLD.review_state = 'unreviewed' AND NEW.review_state = 'acknowledged'
            AND NEW.acknowledged_at IS NOT NULL) THEN
        RAISE EXCEPTION 'illegal review transition % -> %', OLD.review_state, NEW.review_state
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER xch04_review_update_guard
    BEFORE UPDATE ON public.xch04_review
    FOR EACH ROW EXECUTE FUNCTION public.guard_xch04_review_update();
CREATE TRIGGER xch04_review_no_delete
    BEFORE DELETE ON public.xch04_review
    FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation();
CREATE TRIGGER xch04_review_no_truncate
    BEFORE TRUNCATE ON public.xch04_review
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation();

GRANT SELECT, INSERT ON public.xch04_expected_order, public.xch04_report_revision,
                         public.xch04_reconciliation, public.xch04_notification
    TO aurevia_app;
GRANT SELECT, INSERT, UPDATE ON public.xch04_review TO aurevia_app;
REVOKE DELETE, TRUNCATE ON public.xch04_review FROM aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.xch04_expected_order,
                                  public.xch04_report_revision,
                                  public.xch04_reconciliation,
                                  public.xch04_notification
    FROM aurevia_app;
REVOKE ALL ON FUNCTION public.set_xch04_created_by_from_actor() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_xch04_review_update() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.set_xch04_created_by_from_actor() TO aurevia_app;
