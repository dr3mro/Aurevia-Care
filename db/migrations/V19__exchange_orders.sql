-- Requirement IDs: XCH-01, ARC-01, ARC-02, OPS-02
-- Change: expand
-- Order exchange foundation. The sender owns the order, the immutable released
-- snapshot and the disclosure ledger. The destination work item is a bounded
-- projection: it carries only the XCH-01 exchange fields, has no foreign key into
-- any sender chart table, and cannot grant general chart access.
--
-- The projection is readable by the sender and by the verified destination under
-- an explicit two-party row policy. The sender writes it with its own
-- organization_id and a destination_org that must match an approved
-- authorization (enforced by a composite foreign key). The receiver verifies a
-- local patient through a guarded update. No SECURITY DEFINER function and no
-- transaction-level tenant switch is needed, so no command can leave the
-- transaction bound to the wrong tenant.
--
-- Forced RLS from V2 is enabled on every table below. Snapshots and disclosures
-- are append-only for every role.

CREATE TABLE public.exchange_authorization (
    organization_id      uuid NOT NULL REFERENCES public.organization(organization_id),
    id                   uuid NOT NULL DEFAULT gen_random_uuid(),
    order_code           text NOT NULL,
    destination_org      uuid NOT NULL REFERENCES public.organization(organization_id),
    purpose              text NOT NULL,
    max_duration_minutes integer NOT NULL CHECK (max_duration_minutes BETWEEN 1 AND 43200),
    effective_from       timestamptz NOT NULL DEFAULT now(),
    expires_at           timestamptz NOT NULL,
    active               boolean NOT NULL DEFAULT true,
    created_by           uuid NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, id, destination_org),
    CHECK (expires_at > effective_from)
);

CREATE TABLE public.exchange_order (
    organization_id       uuid NOT NULL REFERENCES public.organization(organization_id),
    id                    uuid NOT NULL DEFAULT gen_random_uuid(),
    version               integer NOT NULL DEFAULT 1 CHECK (version = 1),
    patient_id            uuid NOT NULL,
    encounter_ref         text NOT NULL,
    order_code            text NOT NULL,
    priority              text NOT NULL CHECK (priority IN ('routine', 'urgent', 'stat')),
    clinical_question     text NOT NULL,
    destination_org       uuid NOT NULL REFERENCES public.organization(organization_id),
    source_actor          text NOT NULL,
    responsible_clinician text NOT NULL,
    work_queue            text NOT NULL,
    authorization_id      uuid NOT NULL,
    snapshot_id           uuid NOT NULL,
    disclosure_id         uuid NOT NULL,
    work_item_id          uuid NOT NULL,
    created_by            uuid NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    FOREIGN KEY (organization_id, patient_id)
        REFERENCES public.patient_record(organization_id, id),
    FOREIGN KEY (organization_id, authorization_id)
        REFERENCES public.exchange_authorization(organization_id, id),
    CHECK (organization_id <> destination_org)
);

CREATE TABLE public.exchange_order_snapshot (
    organization_id uuid NOT NULL,
    id              uuid NOT NULL,
    order_id        uuid NOT NULL,
    version         integer NOT NULL CHECK (version = 1),
    content         text NOT NULL,
    fingerprint     uuid NOT NULL,
    actor_id        uuid NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, order_id, version),
    FOREIGN KEY (organization_id, order_id)
        REFERENCES public.exchange_order(organization_id, id),
    FOREIGN KEY (organization_id, actor_id)
        REFERENCES public.user_identity(organization_id, id)
);

CREATE TABLE public.exchange_disclosure (
    organization_id  uuid NOT NULL,
    id               uuid NOT NULL,
    order_id         uuid NOT NULL,
    recipient_org    uuid NOT NULL,
    purpose          text NOT NULL,
    duration_minutes integer NOT NULL CHECK (duration_minutes BETWEEN 1 AND 43200),
    expires_at       timestamptz NOT NULL,
    fingerprint      uuid NOT NULL,
    actor_id         uuid NOT NULL,
    occurred_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, id),
    -- One disclosure per released revision; a retry cannot double-record it.
    UNIQUE (organization_id, order_id),
    FOREIGN KEY (organization_id, order_id)
        REFERENCES public.exchange_order(organization_id, id),
    FOREIGN KEY (organization_id, actor_id)
        REFERENCES public.user_identity(organization_id, id)
);

CREATE TABLE public.exchange_work_item (
    organization_id     uuid NOT NULL REFERENCES public.organization(organization_id),
    id                  uuid NOT NULL DEFAULT gen_random_uuid(),
    destination_org     uuid NOT NULL REFERENCES public.organization(organization_id),
    authorization_id    uuid NOT NULL,
    order_ref           uuid NOT NULL,
    patient_ref         uuid NOT NULL,
    patient_mrn         text NOT NULL,
    mrn_issuer          text NOT NULL,
    local_patient_id    uuid,
    patient_verified    boolean NOT NULL DEFAULT false,
    patient_verified_by uuid,
    patient_verified_at timestamptz,
    priority            text NOT NULL CHECK (priority IN ('routine', 'urgent', 'stat')),
    clinical_question   text NOT NULL,
    work_queue          text NOT NULL,
    fingerprint         uuid NOT NULL,
    state               text NOT NULL DEFAULT 'awaiting_local_patient'
        CHECK (state IN ('awaiting_local_patient', 'ready')),
    created_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    -- A destination can receive one work item per sender order revision.
    UNIQUE (destination_org, order_ref),
    FOREIGN KEY (organization_id, authorization_id, destination_org)
        REFERENCES public.exchange_authorization(organization_id, id, destination_org),
    CHECK (organization_id <> destination_org),
    CHECK (patient_verified = (local_patient_id IS NOT NULL)),
    CHECK (patient_verified = (patient_verified_by IS NOT NULL)
           AND patient_verified = (patient_verified_at IS NOT NULL))
);

CREATE INDEX exchange_order_patient_idx
    ON public.exchange_order (organization_id, patient_id, created_at DESC);
CREATE INDEX exchange_work_item_destination_idx
    ON public.exchange_work_item (destination_org, created_at, id);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['exchange_authorization', 'exchange_order',
                             'exchange_order_snapshot', 'exchange_disclosure',
                             'exchange_work_item']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
    END LOOP;
END $$;

-- Sender-owned exchange tables keep the standard single-tenant policy.
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['exchange_authorization', 'exchange_order',
                             'exchange_order_snapshot', 'exchange_disclosure']
    LOOP
        EXECUTE format(
            $p$CREATE POLICY tenant_isolation ON public.%I
                 USING (organization_id = public.app_org())
                 WITH CHECK (organization_id = public.app_org())$p$, t);
    END LOOP;
END $$;

-- The work item is the one deliberate bounded two-party projection. The sender
-- may only insert a projection for a destination different from itself; either
-- party may read it; only the destination may verify its local patient.
CREATE POLICY exchange_work_item_read ON public.exchange_work_item FOR SELECT
    USING (organization_id = public.app_org() OR destination_org = public.app_org());
CREATE POLICY exchange_work_item_sender_insert ON public.exchange_work_item FOR INSERT
    WITH CHECK (organization_id = public.app_org() AND organization_id <> destination_org);
CREATE POLICY exchange_work_item_receiver_update ON public.exchange_work_item FOR UPDATE
    USING (destination_org = public.app_org() AND organization_id <> public.app_org())
    WITH CHECK (destination_org = public.app_org() AND organization_id <> public.app_org());

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['exchange_authorization', 'exchange_order_snapshot',
                             'exchange_disclosure']
    LOOP
        EXECUTE format($d$CREATE TRIGGER %I_no_update_delete
            BEFORE UPDATE OR DELETE ON public.%I
            FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
        EXECUTE format($d$CREATE TRIGGER %I_no_truncate
            BEFORE TRUNCATE ON public.%I
            FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
    END LOOP;
END $$;

-- Actors are derived from the transaction-local subject, never from the payload.
CREATE OR REPLACE FUNCTION public.set_exchange_created_by_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'exchange author must be an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.created_by := v_actor;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION public.set_exchange_actor_id_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'exchange actor must be an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.actor_id := v_actor;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION public.set_exchange_source_actor_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_subject text := NULLIF(current_setting('app.actor', true), '');
BEGIN
    IF v_subject IS NULL THEN
        RAISE EXCEPTION 'exchange source actor must be authenticated' USING ERRCODE = '42501';
    END IF;
    NEW.source_actor := v_subject;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION public.set_exchange_verified_by_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.destination_org
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'work item verifier must be an authenticated receiver user'
            USING ERRCODE = '42501';
    END IF;
    NEW.patient_verified_by := v_actor;
    NEW.patient_verified_at := clock_timestamp();
    RETURN NEW;
END $$;

CREATE TRIGGER exchange_authorization_created_by_actor
    BEFORE INSERT ON public.exchange_authorization
    FOR EACH ROW EXECUTE FUNCTION public.set_exchange_created_by_from_actor();
CREATE TRIGGER exchange_order_source_actor_actor
    BEFORE INSERT ON public.exchange_order
    FOR EACH ROW EXECUTE FUNCTION public.set_exchange_source_actor_from_actor();
CREATE TRIGGER exchange_order_created_by_actor
    BEFORE INSERT ON public.exchange_order
    FOR EACH ROW EXECUTE FUNCTION public.set_exchange_created_by_from_actor();
CREATE TRIGGER exchange_snapshot_actor
    BEFORE INSERT ON public.exchange_order_snapshot
    FOR EACH ROW EXECUTE FUNCTION public.set_exchange_actor_id_from_actor();
CREATE TRIGGER exchange_disclosure_actor
    BEFORE INSERT ON public.exchange_disclosure
    FOR EACH ROW EXECUTE FUNCTION public.set_exchange_actor_id_from_actor();
CREATE TRIGGER exchange_work_item_verified_by_actor
    BEFORE UPDATE ON public.exchange_work_item
    FOR EACH ROW EXECUTE FUNCTION public.set_exchange_verified_by_from_actor();

-- Local verification is one-way and cannot change the projected content.
CREATE OR REPLACE FUNCTION public.guard_exchange_work_item_update()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.id <> OLD.id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.destination_org <> OLD.destination_org
       OR NEW.authorization_id <> OLD.authorization_id
       OR NEW.order_ref <> OLD.order_ref
       OR NEW.patient_ref <> OLD.patient_ref
       OR NEW.patient_mrn <> OLD.patient_mrn
       OR NEW.mrn_issuer <> OLD.mrn_issuer
       OR NEW.priority <> OLD.priority
       OR NEW.clinical_question <> OLD.clinical_question
       OR NEW.work_queue <> OLD.work_queue
       OR NEW.fingerprint <> OLD.fingerprint
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'exchange work item projection columns are immutable'
            USING ERRCODE = '42501';
    END IF;
    IF OLD.patient_verified OR NOT NEW.patient_verified
       OR NEW.local_patient_id IS NULL OR NEW.local_patient_id = OLD.local_patient_id
       OR OLD.state <> 'awaiting_local_patient' OR NEW.state <> 'ready' THEN
        RAISE EXCEPTION 'exchange work item can only be verified once'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER exchange_work_item_update_guard
    BEFORE UPDATE ON public.exchange_work_item
    FOR EACH ROW EXECUTE FUNCTION public.guard_exchange_work_item_update();

GRANT SELECT, INSERT ON public.exchange_authorization, public.exchange_order,
                        public.exchange_order_snapshot, public.exchange_disclosure
    TO orevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.exchange_authorization,
                                  public.exchange_order_snapshot,
                                  public.exchange_disclosure FROM orevia_app;
REVOKE DELETE, TRUNCATE ON public.exchange_order FROM orevia_app;

GRANT SELECT, INSERT, UPDATE ON public.exchange_work_item TO orevia_app;
REVOKE DELETE, TRUNCATE ON public.exchange_work_item FROM orevia_app;

REVOKE ALL ON FUNCTION public.set_exchange_created_by_from_actor() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.set_exchange_actor_id_from_actor() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.set_exchange_source_actor_from_actor() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.set_exchange_verified_by_from_actor() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_exchange_work_item_update() FROM PUBLIC;
