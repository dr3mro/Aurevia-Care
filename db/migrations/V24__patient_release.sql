-- Requirement IDs: XCH-06, PAT-02, ARC-01, ARC-02, ARC-05, OPS-02
-- Change: expand
-- Patient release authorization, timed/hold policies and withdrawn releases.
--
-- At most one authorization may ever exist per (organization, patient, immutable
-- revision) by a unique index over all states. The current row carries only mutable
-- state/version;
-- every decision appends to release_event and release_notification, and audit is
-- appended by the service in the same transaction. Withdrawal and correction move
-- the live row to a terminal state, so future access checks fail closed while
-- prior disclosures remain in history.
--
-- Ordering note: db/check.sh applies V*.sql in lexicographic order, so a two-digit
-- version is applied before V2-V8 there. This migration therefore depends only on
-- V1 (organization, user_identity, audit_event) and defines its own RLS expression
-- and immutability function instead of relying on V2's app_org() or V4's
-- reject_immutable_mutation(). Flyway applies it in numeric order in production.
--
-- The immutable report revision is referenced by opaque id, version and issuing
-- organization rather than a foreign key: the report-revision table belongs to the
-- XCH-04 story (V22), which is merged independently by another worker.

CREATE TABLE public.patient_release (
    organization_id        uuid NOT NULL REFERENCES public.organization(organization_id),
    id                     uuid NOT NULL DEFAULT gen_random_uuid(),
    patient_id             uuid NOT NULL,
    revision_id            text NOT NULL,
    revision_version       integer NOT NULL CHECK (revision_version > 0),
    issuing_organization_id uuid NOT NULL,
    policy_version         integer NOT NULL CHECK (policy_version > 0),
    kind                   text NOT NULL CHECK (kind IN ('IMMEDIATE', 'TIMED', 'HOLD')),
    state                  text NOT NULL
        CHECK (state IN ('HELD', 'AUTHORIZED', 'WITHDRAWN', 'CORRECTED')),
    effective_from         timestamptz NOT NULL,
    hold_reason            text,
    hold_expires_at        timestamptz,
    reviewed_by            uuid,
    reviewed_at            timestamptz,
    supersedes_id          uuid,
    version                integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_by             uuid NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    CHECK (kind <> 'HOLD' OR (
        length(btrim(coalesce(hold_reason, ''))) > 0 AND hold_expires_at IS NOT NULL)),
    CHECK (reviewed_by IS NULL OR reviewed_at IS NOT NULL),
    FOREIGN KEY (organization_id, created_by)
        REFERENCES public.user_identity(organization_id, id),
    FOREIGN KEY (organization_id, reviewed_by)
        REFERENCES public.user_identity(organization_id, id),
    FOREIGN KEY (organization_id, supersedes_id)
        REFERENCES public.patient_release(organization_id, id)
);

-- One-way revision identity survives process loss: at most one release may ever exist
-- for a (tenant, patient, immutable revision), including terminal rows. A withdrawn or
-- corrected revision therefore cannot be re-published by a concurrent insert.
CREATE UNIQUE INDEX patient_release_revision_uq
    ON public.patient_release (organization_id, patient_id, revision_id);
CREATE INDEX patient_release_patient_idx
    ON public.patient_release (organization_id, patient_id, state);

CREATE TABLE public.release_event (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    release_id      uuid NOT NULL,
    action          text NOT NULL
        CHECK (action IN ('RELEASE_AUTHORIZE', 'RELEASE_LIFT_HOLD',
                          'RELEASE_WITHDRAW', 'RELEASE_CORRECT')),
    from_state      text,
    to_state        text NOT NULL,
    reason          text,
    actor_id        uuid NOT NULL,
    occurred_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, id),
    FOREIGN KEY (organization_id, release_id)
        REFERENCES public.patient_release(organization_id, id),
    FOREIGN KEY (organization_id, actor_id)
        REFERENCES public.user_identity(organization_id, id)
);
CREATE INDEX release_event_history_idx
    ON public.release_event (organization_id, release_id, occurred_at, id);

CREATE TABLE public.release_notification (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    release_id      uuid NOT NULL,
    event_type      text NOT NULL
        CHECK (event_type IN ('RELEASE_AUTHORIZED', 'HOLD_LIFTED',
                              'RELEASE_WITHDRAWN', 'RELEASE_CORRECTED')),
    recipient_scope text NOT NULL,
    correlation_id  text,
    occurred_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    processed       boolean NOT NULL DEFAULT false,
    PRIMARY KEY (organization_id, id),
    FOREIGN KEY (organization_id, release_id)
        REFERENCES public.patient_release(organization_id, id)
);
CREATE INDEX release_notification_pending_idx
    ON public.release_notification (organization_id, processed, occurred_at);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['patient_release', 'release_event', 'release_notification']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            $p$CREATE POLICY tenant_isolation ON public.%I
                 USING (organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid)
                 WITH CHECK (organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid)$p$, t);
    END LOOP;
END $$;

-- Local append-only guard; independent of the version in which the shared helper
-- is introduced so this migration is safe under both apply orders.
CREATE OR REPLACE FUNCTION public.reject_release_ledger_mutation()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    RAISE EXCEPTION 'release ledger is append-only' USING ERRCODE = '42501';
END $$;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['release_event', 'release_notification']
    LOOP
        EXECUTE format($d$CREATE TRIGGER %I_no_update_delete
            BEFORE UPDATE OR DELETE ON public.%I
            FOR EACH ROW EXECUTE FUNCTION public.reject_release_ledger_mutation()$d$, t, t);
        EXECUTE format($d$CREATE TRIGGER %I_no_truncate
            BEFORE TRUNCATE ON public.%I
            FOR EACH STATEMENT EXECUTE FUNCTION public.reject_release_ledger_mutation()$d$, t, t);
    END LOOP;
END $$;

CREATE TRIGGER patient_release_no_delete
    BEFORE DELETE ON public.patient_release
    FOR EACH ROW EXECUTE FUNCTION public.reject_release_ledger_mutation();
CREATE TRIGGER patient_release_no_truncate
    BEFORE TRUNCATE ON public.patient_release
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_release_ledger_mutation();

-- Author and reviewer always come from the authenticated actor of the active
-- tenant. A client-supplied author or reviewer is overwritten or rejected.
CREATE OR REPLACE FUNCTION public.set_release_actor_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'release decision requires an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    IF TG_OP = 'INSERT' THEN
        NEW.created_by := v_actor;
    ELSIF OLD.state = 'HELD' AND NEW.state = 'AUTHORIZED' THEN
        NEW.reviewed_by := v_actor;
        NEW.reviewed_at := clock_timestamp();
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER release_actor_from_actor
    BEFORE INSERT OR UPDATE ON public.patient_release
    FOR EACH ROW EXECUTE FUNCTION public.set_release_actor_from_actor();

-- Decision-event actors are also derived from the authenticated actor; the
-- service deliberately omits actor_id from its INSERT.
CREATE OR REPLACE FUNCTION public.set_release_event_actor_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'release event actor must be an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.actor_id := v_actor;
    RETURN NEW;
END $$;
CREATE TRIGGER release_event_actor_from_actor
    BEFORE INSERT ON public.release_event
    FOR EACH ROW EXECUTE FUNCTION public.set_release_event_actor_from_actor();

-- The database repeats the state machine and version precondition so a stray
-- UPDATE cannot re-point a release or walk it backwards.
CREATE OR REPLACE FUNCTION public.guard_release_update()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.id <> OLD.id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.patient_id <> OLD.patient_id
       OR NEW.revision_id <> OLD.revision_id
       OR NEW.revision_version <> OLD.revision_version
       OR NEW.policy_version <> OLD.policy_version
       OR NEW.kind <> OLD.kind
       OR NEW.effective_from <> OLD.effective_from
       OR NEW.created_by <> OLD.created_by
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'release ownership columns are immutable' USING ERRCODE = '42501';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'release version must advance by exactly one' USING ERRCODE = '23514';
    END IF;
    IF NEW.state <> OLD.state AND NOT (
            (OLD.state = 'HELD'       AND NEW.state IN ('AUTHORIZED', 'WITHDRAWN'))
         OR (OLD.state = 'AUTHORIZED' AND NEW.state IN ('WITHDRAWN', 'CORRECTED'))) THEN
        RAISE EXCEPTION 'illegal release transition % -> %', OLD.state, NEW.state
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER release_update_guard
    BEFORE UPDATE ON public.patient_release
    FOR EACH ROW EXECUTE FUNCTION public.guard_release_update();

GRANT SELECT, INSERT, UPDATE ON public.patient_release TO aurevia_app;
REVOKE DELETE, TRUNCATE ON public.patient_release FROM aurevia_app;
GRANT SELECT, INSERT ON public.release_event, public.release_notification TO aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.release_event, public.release_notification FROM aurevia_app;

REVOKE ALL ON FUNCTION public.reject_release_ledger_mutation() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.set_release_actor_from_actor() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.set_release_event_actor_from_actor() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_release_update() FROM PUBLIC;
