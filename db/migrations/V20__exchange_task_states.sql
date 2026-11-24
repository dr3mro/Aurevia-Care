-- Requirement IDs: XCH-02, ARC-01, ARC-02, ARC-03, ARC-04, OPS-02
-- Change: expand
-- S-G2-02: three independent state channels for one exchange work item, with
-- an append-only event ledger and human-bound clinical review evidence.
-- Self-contained: no reference to V19 (S-G2-01) or any V21+ object. Gaps in
-- migration versions are expected while parallel workers own reserved versions.

CREATE TABLE public.exchange_task (
    organization_id          uuid NOT NULL REFERENCES public.organization(organization_id),
    id                       uuid NOT NULL DEFAULT gen_random_uuid(),
    order_ref                text NOT NULL,
    workflow_state           text NOT NULL DEFAULT 'requested'
        CHECK (workflow_state IN ('requested', 'accepted', 'processing',
                                  'completed', 'rejected', 'cancelled')),
    transmission_state       text NOT NULL DEFAULT 'pending'
        CHECK (transmission_state IN ('pending', 'sent', 'delivered', 'failed')),
    clinical_review_state    text NOT NULL DEFAULT 'unreviewed'
        CHECK (clinical_review_state IN ('unreviewed', 'acknowledged',
                                         'action_pending', 'resolved')),
    version                  integer NOT NULL DEFAULT 1 CHECK (version > 0),
    clinical_reviewed_at     timestamptz,
    clinical_revision        integer,
    clinical_review_actor_id uuid,
    reason                   text,
    created_by               uuid NOT NULL,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, order_ref),
    -- Review evidence exists exactly when the clinical channel left unreviewed.
    -- A delivery receipt can never satisfy this because it does not move the
    -- clinical channel, which is what makes a transmission ack unable to close
    -- a clinical task.
    CHECK (
        (clinical_review_state = 'unreviewed'
            AND clinical_reviewed_at IS NULL AND clinical_revision IS NULL
            AND clinical_review_actor_id IS NULL)
        OR (clinical_review_state IN ('acknowledged', 'action_pending', 'resolved')
            AND clinical_reviewed_at IS NOT NULL AND clinical_revision IS NOT NULL
            AND clinical_review_actor_id IS NOT NULL)
    ),
    FOREIGN KEY (organization_id, created_by)
        REFERENCES public.user_identity(organization_id, id),
    FOREIGN KEY (organization_id, clinical_review_actor_id)
        REFERENCES public.user_identity(organization_id, id)
);

CREATE TABLE public.exchange_task_transition (
    organization_id    uuid NOT NULL,
    id                 uuid NOT NULL DEFAULT gen_random_uuid(),
    task_id            uuid NOT NULL,
    to_version         integer NOT NULL CHECK (to_version > 0),
    channel            text NOT NULL
        CHECK (channel IN ('workflow', 'transmission', 'clinical_review')),
    action             text NOT NULL,
    from_state         text,
    to_state           text NOT NULL,
    reason             text,
    reviewed_revision  integer,
    actor_id           uuid NOT NULL,
    occurred_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, task_id, to_version, channel),
    CHECK (from_state IS NULL OR from_state <> to_state),
    CHECK (channel <> 'clinical_review' OR reviewed_revision IS NOT NULL),
    FOREIGN KEY (organization_id, task_id)
        REFERENCES public.exchange_task(organization_id, id),
    FOREIGN KEY (organization_id, actor_id)
        REFERENCES public.user_identity(organization_id, id)
);

CREATE INDEX exchange_task_workflow_idx ON public.exchange_task
    (organization_id, workflow_state, updated_at DESC);
CREATE INDEX exchange_task_transmission_idx ON public.exchange_task
    (organization_id, transmission_state);
CREATE INDEX exchange_task_clinical_idx ON public.exchange_task
    (organization_id, clinical_review_state, clinical_reviewed_at);
CREATE INDEX exchange_task_transition_history_idx ON public.exchange_task_transition
    (organization_id, task_id, occurred_at, id);

-- V2's static policy list does not cover new tables. Force RLS on both so the
-- non-owner runtime role cannot bypass tenant isolation, including in workers.
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['exchange_task', 'exchange_task_transition']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            $p$CREATE POLICY tenant_isolation ON public.%I
                 USING (organization_id = public.app_org())
                 WITH CHECK (organization_id = public.app_org())$p$, t);
    END LOOP;
END $$;

-- A transition is an immutable fact; a task changes state but is never deleted.
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['exchange_task_transition']
    LOOP
        EXECUTE format($d$CREATE TRIGGER %I_no_update_delete
            BEFORE UPDATE OR DELETE ON public.%I
            FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
        EXECUTE format($d$CREATE TRIGGER %I_no_truncate
            BEFORE TRUNCATE ON public.%I
            FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
    END LOOP;
END $$;

CREATE TRIGGER exchange_task_no_delete
    BEFORE DELETE ON public.exchange_task
    FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation();
CREATE TRIGGER exchange_task_no_truncate
    BEFORE TRUNCATE ON public.exchange_task
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation();

-- Resolve the acting user id from the transaction-local subject; fail closed
-- when there is no authenticated, actively enrolled user of the active tenant.
-- Membership is re-folded here (not cached in a token) so a deactivation denies
-- the next protected write at the database authority too, not just in service.
CREATE OR REPLACE FUNCTION public.xch02_actor_id()
RETURNS uuid LANGUAGE plpgsql STABLE SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = public.app_org()
       AND subject = NULLIF(current_setting('app.actor', true), '')
       AND public.has_active_membership(NULLIF(current_setting('app.actor', true), ''));
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'exchange command requires an active user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    RETURN v_actor;
END $$;

CREATE OR REPLACE FUNCTION public.xch02_set_task_created_by()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    NEW.created_by := public.xch02_actor_id();
    RETURN NEW;
END $$;
CREATE TRIGGER xch02_a_set_task_created_by
    BEFORE INSERT ON public.exchange_task
    FOR EACH ROW EXECUTE FUNCTION public.xch02_set_task_created_by();

-- A work item is always born in the three initial channels. Normalising on
-- INSERT closes the storage-level forgery path: a direct SQL writer cannot
-- create an already-acknowledged or resolved task with a chosen actor,
-- revision and timestamp. The clinical channel can leave 'unreviewed' only
-- through the guarded UPDATE below, which binds a live human actor.
CREATE OR REPLACE FUNCTION public.xch02_initialize_task_channels()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    NEW.workflow_state := 'requested';
    NEW.transmission_state := 'pending';
    NEW.clinical_review_state := 'unreviewed';
    NEW.clinical_reviewed_at := NULL;
    NEW.clinical_revision := NULL;
    NEW.clinical_review_actor_id := NULL;
    RETURN NEW;
END $$;
CREATE TRIGGER xch02_a_initialize_task_channels
    BEFORE INSERT ON public.exchange_task
    FOR EACH ROW EXECUTE FUNCTION public.xch02_initialize_task_channels();

CREATE OR REPLACE FUNCTION public.xch02_set_transition_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    NEW.organization_id := public.app_org();
    NEW.actor_id := public.xch02_actor_id();
    RETURN NEW;
END $$;
CREATE TRIGGER xch02_a_set_transition_actor
    BEFORE INSERT ON public.exchange_task_transition
    FOR EACH ROW EXECUTE FUNCTION public.xch02_set_transition_actor();

-- The database-side authority for XCH-02. It pins ownership, advances the
-- version by exactly one, permits only the legal transition in exactly one
-- channel, and requires a bound human actor, timestamp and revision for every
-- clinical review change. A stray UPDATE cannot perform a delivery and a
-- clinical resolution in the same write.
CREATE OR REPLACE FUNCTION public.xch02_guard_task_update()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_changed integer;
BEGIN
    IF NEW.id <> OLD.id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.order_ref <> OLD.order_ref
       OR NEW.created_by <> OLD.created_by
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'exchange task ownership columns are immutable'
            USING ERRCODE = '42501';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'exchange task version must advance by exactly one'
            USING ERRCODE = '23514';
    END IF;

    v_changed := (NEW.workflow_state IS DISTINCT FROM OLD.workflow_state)::integer
               + (NEW.transmission_state IS DISTINCT FROM OLD.transmission_state)::integer
               + (NEW.clinical_review_state IS DISTINCT FROM OLD.clinical_review_state)::integer;
    IF v_changed <> 1 THEN
        RAISE EXCEPTION 'an exchange update must change exactly one state channel'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.workflow_state IS DISTINCT FROM OLD.workflow_state AND NOT (
            (OLD.workflow_state = 'requested'  AND NEW.workflow_state IN ('accepted', 'rejected', 'cancelled'))
         OR (OLD.workflow_state = 'accepted'   AND NEW.workflow_state IN ('processing', 'cancelled'))
         OR (OLD.workflow_state = 'processing' AND NEW.workflow_state IN ('completed', 'cancelled'))) THEN
        RAISE EXCEPTION 'illegal exchange workflow transition % -> %',
            OLD.workflow_state, NEW.workflow_state USING ERRCODE = '23514';
    END IF;

    IF NEW.transmission_state IS DISTINCT FROM OLD.transmission_state AND NOT (
            (OLD.transmission_state = 'pending' AND NEW.transmission_state IN ('sent', 'failed'))
         OR (OLD.transmission_state = 'sent'    AND NEW.transmission_state IN ('delivered', 'failed'))) THEN
        RAISE EXCEPTION 'illegal exchange transmission transition % -> %',
            OLD.transmission_state, NEW.transmission_state USING ERRCODE = '23514';
    END IF;

    IF NEW.clinical_review_state IS DISTINCT FROM OLD.clinical_review_state THEN
        IF NOT (
            (OLD.clinical_review_state = 'unreviewed'     AND NEW.clinical_review_state = 'acknowledged')
         OR (OLD.clinical_review_state = 'acknowledged'   AND NEW.clinical_review_state = 'action_pending')
         OR (OLD.clinical_review_state = 'action_pending' AND NEW.clinical_review_state = 'resolved')) THEN
            RAISE EXCEPTION 'illegal exchange clinical review transition % -> %',
                OLD.clinical_review_state, NEW.clinical_review_state USING ERRCODE = '23514';
        END IF;
        IF NEW.clinical_revision IS NULL OR NEW.clinical_reviewed_at IS NULL THEN
            RAISE EXCEPTION 'clinical review requires a revision and a timestamp'
                USING ERRCODE = '23514';
        END IF;
        NEW.clinical_review_actor_id := public.xch02_actor_id();
    ELSE
        NEW.clinical_review_actor_id := OLD.clinical_review_actor_id;
        NEW.clinical_revision := OLD.clinical_revision;
        NEW.clinical_reviewed_at := OLD.clinical_reviewed_at;
    END IF;

    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END $$;
CREATE TRIGGER xch02_z_guard_task_update
    BEFORE UPDATE ON public.exchange_task
    FOR EACH ROW EXECUTE FUNCTION public.xch02_guard_task_update();

-- The runtime role may advance states and append evidence, but never delete a
-- task or alter a ledger entry.
GRANT SELECT, INSERT, UPDATE ON public.exchange_task TO orevia_app;
REVOKE DELETE, TRUNCATE ON public.exchange_task FROM orevia_app;
GRANT SELECT, INSERT ON public.exchange_task_transition TO orevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.exchange_task_transition FROM orevia_app;

REVOKE ALL ON FUNCTION public.xch02_actor_id() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.xch02_set_task_created_by() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.xch02_initialize_task_channels() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.xch02_set_transition_actor() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.xch02_guard_task_update() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.xch02_actor_id() TO orevia_app;
GRANT EXECUTE ON FUNCTION public.xch02_set_task_created_by() TO orevia_app;
GRANT EXECUTE ON FUNCTION public.xch02_initialize_task_channels() TO orevia_app;
GRANT EXECUTE ON FUNCTION public.xch02_set_transition_actor() TO orevia_app;
GRANT EXECUTE ON FUNCTION public.xch02_guard_task_update() TO orevia_app;
