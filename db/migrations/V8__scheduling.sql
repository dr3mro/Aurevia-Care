-- Requirement IDs: CLN-01, ARC-01, ARC-02, OPS-02
-- Scheduling foundation for G1: facility time zone, protected clinician and room
-- resources, weekly local availability windows, visit duration, temporary holds
-- with expiry, and appointments with append-only history.
--
-- A protected resource can never hold two live appointments in one slot: two
-- partial GiST exclusion constraints make the database the authority for slot
-- overlap, so two concurrent bookings cannot both commit. The exclusion
-- predicate stays immutable because now() is not allowed in it; an expired hold
-- is therefore released by flipping its state inside the next booking
-- transaction, and a bounded sweep is a follow-on job (OPS-05 queue age).
--
-- Appointment rows are mutable only for the slot, the state and the version, and
-- are never deleted. A guard trigger pins the ownership columns and the state
-- machine, so a stray UPDATE cannot re-point an appointment at another patient or
-- walk it backwards. Every change also appends an immutable appointment_event.
--
-- Forced RLS from V2 applies to every table below, and every foreign key carries
-- the organization so a cross-tenant reference cannot resolve.
--
-- Dependencies: the btree_gist extension (trusted since PostgreSQL 13, so the
-- non-superuser migration owner can install it). Provision it out-of-band in
-- infrastructure as code as well, where the migration role may not own the
-- database.

-- Fail loudly at migrate time when the cluster cannot support protected slots.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'btree_gist') THEN
        RAISE EXCEPTION 'btree_gist is required for protected-slot scheduling';
    END IF;
END $$;

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Facility time zone is the civil clock for availability windows and display.
-- The IANA name is validated in the application (ZoneId), because a CHECK
-- expression cannot perform the zoneinfo lookup. A new column with a constant
-- default is metadata-only on PostgreSQL 11+; the brief ACCESS EXCLUSIVE lock is
-- bounded by the small facility table.
ALTER TABLE public.facility ADD COLUMN time_zone text NOT NULL DEFAULT 'UTC';

-- A facility time zone may be corrected by the application; nothing else on the
-- facility row is writable through the runtime role.
GRANT UPDATE (time_zone) ON public.facility TO aurevia_app;

CREATE TABLE public.visit_type (
    organization_id  uuid NOT NULL REFERENCES public.organization(organization_id),
    code             text NOT NULL,
    duration_minutes integer NOT NULL CHECK (duration_minutes BETWEEN 5 AND 480),
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, code)
);

CREATE TABLE public.scheduling_resource (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    facility_id     uuid NOT NULL,
    kind            text NOT NULL CHECK (kind IN ('clinician', 'room')),
    code            text NOT NULL,
    display_name    text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, code),
    -- Composite keys that let an appointment prove the resource is of the
    -- expected kind and belongs to the appointment's own facility.
    UNIQUE (organization_id, id, kind),
    UNIQUE (organization_id, facility_id, id),
    FOREIGN KEY (organization_id, facility_id)
        REFERENCES public.facility(organization_id, id)
);

CREATE TABLE public.resource_availability (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    resource_id     uuid NOT NULL,
    -- ISO-8601 day of week: 1 = Monday ... 7 = Sunday.
    weekday         smallint NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    start_local     time NOT NULL,
    end_local       time NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    CHECK (end_local > start_local),
    FOREIGN KEY (organization_id, resource_id)
        REFERENCES public.scheduling_resource(organization_id, id)
);

CREATE TABLE public.appointment (
    organization_id       uuid NOT NULL REFERENCES public.organization(organization_id),
    id                    uuid NOT NULL DEFAULT gen_random_uuid(),
    version               integer NOT NULL DEFAULT 1 CHECK (version > 0),
    patient_id            uuid NOT NULL,
    facility_id           uuid NOT NULL,
    clinician_resource_id uuid NOT NULL,
    room_resource_id      uuid,
    visit_type            text NOT NULL,
    starts_at             timestamptz NOT NULL,
    ends_at               timestamptz NOT NULL,
    state                 text NOT NULL
        CHECK (state IN ('held', 'booked', 'checked_in', 'in_progress',
                         'completed', 'cancelled', 'no_show', 'expired')),
    series_id             uuid,
    expires_at            timestamptz,
    override_reason       text,
    created_by            uuid NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    -- Generated tags close the wrong-resource hazard: a room can never be used
    -- as the clinician, nor can a resource of another facility be attached.
    clinician_kind        text GENERATED ALWAYS AS ('clinician') STORED,
    room_kind             text GENERATED ALWAYS AS
        (CASE WHEN room_resource_id IS NULL THEN NULL ELSE 'room' END) STORED,
    PRIMARY KEY (organization_id, id),
    CHECK (ends_at > starts_at),
    CHECK (state <> 'held' OR expires_at IS NOT NULL),
    FOREIGN KEY (organization_id, patient_id)
        REFERENCES public.patient_record(organization_id, id),
    FOREIGN KEY (organization_id, facility_id)
        REFERENCES public.facility(organization_id, id),
    FOREIGN KEY (organization_id, clinician_resource_id, clinician_kind)
        REFERENCES public.scheduling_resource(organization_id, id, kind),
    FOREIGN KEY (organization_id, room_resource_id, room_kind)
        REFERENCES public.scheduling_resource(organization_id, id, kind),
    FOREIGN KEY (organization_id, facility_id, clinician_resource_id)
        REFERENCES public.scheduling_resource(organization_id, facility_id, id),
    FOREIGN KEY (organization_id, facility_id, room_resource_id)
        REFERENCES public.scheduling_resource(organization_id, facility_id, id),
    FOREIGN KEY (organization_id, visit_type)
        REFERENCES public.visit_type(organization_id, code),
    FOREIGN KEY (organization_id, created_by)
        REFERENCES public.user_identity(organization_id, id)
);

-- The live states occupy the slot. A NULL room never conflicts, which is why a
-- clinician-only appointment is still protected by the clinician constraint.
ALTER TABLE public.appointment
    ADD CONSTRAINT appointment_clinician_slot_excl
    EXCLUDE USING gist (
        organization_id WITH =,
        clinician_resource_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (state IN ('held', 'booked', 'checked_in', 'in_progress'));

ALTER TABLE public.appointment
    ADD CONSTRAINT appointment_room_slot_excl
    EXCLUDE USING gist (
        organization_id WITH =,
        room_resource_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (state IN ('held', 'booked', 'checked_in', 'in_progress'));

CREATE INDEX appointment_facility_idx ON public.appointment (organization_id, facility_id, starts_at);
CREATE INDEX appointment_patient_idx ON public.appointment (organization_id, patient_id, starts_at DESC);
CREATE INDEX appointment_stale_hold_idx ON public.appointment (organization_id, expires_at)
    WHERE state = 'held';

CREATE TABLE public.appointment_event (
    organization_id    uuid NOT NULL,
    id                 uuid NOT NULL DEFAULT gen_random_uuid(),
    appointment_id     uuid NOT NULL,
    appointment_version integer NOT NULL,
    action             text NOT NULL
        CHECK (action IN ('held', 'booked', 'confirmed', 'rescheduled', 'checked_in',
                          'started', 'completed', 'cancelled', 'no_show', 'expired')),
    from_state         text,
    to_state           text NOT NULL,
    reason             text,
    previous_starts_at timestamptz,
    previous_ends_at   timestamptz,
    actor_id           uuid NOT NULL,
    occurred_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, id),
    -- History is bound to the exact appointment revision it describes. A foreign
    -- key to appointment(version) is deliberately omitted: the appointment row is
    -- mutated in place, so the revision it names no longer exists after the next
    -- transition. Uniqueness per revision keeps one event per revision instead.
    UNIQUE (organization_id, appointment_id, appointment_version),
    FOREIGN KEY (organization_id, appointment_id)
        REFERENCES public.appointment(organization_id, id),
    FOREIGN KEY (organization_id, actor_id)
        REFERENCES public.user_identity(organization_id, id)
);

CREATE INDEX appointment_event_history_idx
    ON public.appointment_event (organization_id, appointment_id, occurred_at, id);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['visit_type', 'scheduling_resource', 'resource_availability',
                             'appointment', 'appointment_event']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            $p$CREATE POLICY tenant_isolation ON public.%I
                 USING (organization_id = public.app_org())
                 WITH CHECK (organization_id = public.app_org())$p$, t);
    END LOOP;
END $$;

-- Appointment history is append-only for every role, like the other ledgers.
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['appointment_event']
    LOOP
        EXECUTE format($d$CREATE TRIGGER %I_no_update_delete
            BEFORE UPDATE OR DELETE ON public.%I
            FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
        EXECUTE format($d$CREATE TRIGGER %I_no_truncate
            BEFORE TRUNCATE ON public.%I
            FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
    END LOOP;
END $$;

-- An appointment is never deleted: cancellation is a state transition.
CREATE TRIGGER appointment_no_delete
    BEFORE DELETE ON public.appointment
    FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation();
CREATE TRIGGER appointment_no_truncate
    BEFORE TRUNCATE ON public.appointment
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation();

-- The author of an appointment and of a history event is always the
-- authenticated actor of the active tenant, never caller-supplied.
CREATE OR REPLACE FUNCTION public.set_appointment_created_by_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'appointment author must be an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.created_by := v_actor;
    RETURN NEW;
END $$;
CREATE TRIGGER appointment_created_by_actor
    BEFORE INSERT ON public.appointment
    FOR EACH ROW EXECUTE FUNCTION public.set_appointment_created_by_from_actor();

CREATE OR REPLACE FUNCTION public.set_appointment_event_actor_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'appointment history actor must be an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.actor_id := v_actor;
    RETURN NEW;
END $$;
CREATE TRIGGER appointment_event_actor
    BEFORE INSERT ON public.appointment_event
    FOR EACH ROW EXECUTE FUNCTION public.set_appointment_event_actor_from_actor();

-- The state machine is enforced in the database as well as the domain service:
-- ownership columns are pinned, the version advances by exactly one, and only
-- the transitions CLN-01 allows are accepted.
CREATE OR REPLACE FUNCTION public.guard_appointment_update()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.id <> OLD.id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.patient_id <> OLD.patient_id
       OR NEW.facility_id <> OLD.facility_id
       OR NEW.visit_type <> OLD.visit_type
       OR NEW.created_by <> OLD.created_by
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'appointment ownership columns are immutable'
            USING ERRCODE = '42501';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'appointment version must advance by exactly one'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.state <> OLD.state AND NOT (
            (OLD.state = 'held'       AND NEW.state IN ('booked', 'expired', 'cancelled'))
         OR (OLD.state = 'booked'     AND NEW.state IN ('checked_in', 'cancelled', 'no_show'))
         OR (OLD.state = 'checked_in' AND NEW.state IN ('in_progress', 'cancelled'))
         OR (OLD.state = 'in_progress' AND NEW.state = 'completed')) THEN
        RAISE EXCEPTION 'illegal appointment transition % -> %', OLD.state, NEW.state
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER appointment_update_guard
    BEFORE UPDATE ON public.appointment
    FOR EACH ROW EXECUTE FUNCTION public.guard_appointment_update();

-- Reference data is written only through the guarded INSERT path.
GRANT SELECT, INSERT ON public.visit_type, public.scheduling_resource,
                         public.resource_availability TO aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.visit_type, public.scheduling_resource,
       public.resource_availability FROM aurevia_app;

-- An appointment may move through its state machine but never be deleted.
GRANT SELECT, INSERT, UPDATE ON public.appointment TO aurevia_app;
REVOKE DELETE, TRUNCATE ON public.appointment FROM aurevia_app;

GRANT SELECT, INSERT ON public.appointment_event TO aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.appointment_event FROM aurevia_app;

REVOKE ALL ON FUNCTION public.guard_appointment_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.set_appointment_created_by_from_actor() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.set_appointment_event_actor_from_actor() FROM PUBLIC;
