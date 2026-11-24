-- Requirement IDs: CLN-02, IAM-02, IAM-03, ARC-01, ARC-02, OPS-02
-- Change: expand
-- Check-in identity evidence, the organization/facility worklist with assignment
-- and configurable priority, appendable handoff events, and unscheduled-arrival
-- intake linkage.
--
-- This is an additive (expand) migration. It does not alter the V8 appointment
-- state machine or its history trigger: S-G1-01 owns booked -> checked_in and
-- one immutable appointment_event per revision. Reception verification is a
-- separate, append-only fact that the CLN-02 service commits in the same
-- transaction as the check-in transition.
--
-- Every table carries the tenant key in its primary key and in each foreign key,
-- and is under FORCE ROW LEVEL SECURITY conditioned on app_org(). The runtime
-- role cannot delete or truncate a ledger and cannot fabricate an actor: the
-- actor columns are populated by triggers from the transaction-local app.actor.
-- An assignee must have active membership in the same organization, re-derived
-- by public.has_active_membership() at write time so deactivation fails closed
-- on the next assignment or handoff (IAM-02).

-- Immutable evidence that identity was confirmed before check-in. Only the
-- identifier categories used are stored, never the identifier values, so the
-- ledger carries no unnecessary patient data.
CREATE TABLE public.check_in_verification (
    organization_id   uuid NOT NULL REFERENCES public.organization(organization_id),
    id                uuid NOT NULL DEFAULT gen_random_uuid(),
    appointment_id    uuid NOT NULL,
    patient_id        uuid NOT NULL,
    facility_id       uuid NOT NULL,
    method            text NOT NULL
        CHECK (method IN ('two_identifiers', 'documented_alternate')),
    identifiers       jsonb NOT NULL DEFAULT '[]'::jsonb,
    alternate_code    text,
    alternate_reason  text,
    verified_by       uuid NOT NULL,
    verified_at       timestamptz NOT NULL DEFAULT now(),
    created_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, appointment_id),
    CHECK (method <> 'two_identifiers' OR jsonb_array_length(identifiers) >= 2),
    CHECK (method <> 'documented_alternate'
           OR (alternate_code IS NOT NULL AND alternate_reason IS NOT NULL)),
    FOREIGN KEY (organization_id, appointment_id)
        REFERENCES public.appointment(organization_id, id),
    FOREIGN KEY (organization_id, patient_id)
        REFERENCES public.patient_record(organization_id, id),
    FOREIGN KEY (organization_id, facility_id)
        REFERENCES public.facility(organization_id, id),
    FOREIGN KEY (organization_id, verified_by)
        REFERENCES public.user_identity(organization_id, id)
);

-- One assignable item per scheduled appointment, or one unscheduled arrival
-- linked to intake. Exactly one of appointment_id and intake_reason is present.
-- Priority is resolved from the approved site configuration at creation and is
-- stored so a later configuration change does not silently reorder history.
CREATE TABLE public.worklist_entry (
    organization_id   uuid NOT NULL REFERENCES public.organization(organization_id),
    id                uuid NOT NULL DEFAULT gen_random_uuid(),
    appointment_id    uuid,
    intake_reason     text,
    patient_id        uuid NOT NULL,
    facility_id       uuid NOT NULL,
    priority          integer NOT NULL DEFAULT 0 CHECK (priority BETWEEN 0 AND 9),
    assignee_subject  text,
    assigned_at       timestamptz,
    version           integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_by        uuid NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    CHECK ((appointment_id IS NULL) <> (intake_reason IS NULL)),
    CHECK ((assignee_subject IS NULL) = (assigned_at IS NULL)),
    FOREIGN KEY (organization_id, appointment_id)
        REFERENCES public.appointment(organization_id, id),
    FOREIGN KEY (organization_id, patient_id)
        REFERENCES public.patient_record(organization_id, id),
    FOREIGN KEY (organization_id, facility_id)
        REFERENCES public.facility(organization_id, id),
    FOREIGN KEY (organization_id, created_by)
        REFERENCES public.user_identity(organization_id, id)
);

CREATE UNIQUE INDEX worklist_entry_appointment_idx
    ON public.worklist_entry (organization_id, appointment_id)
    WHERE appointment_id IS NOT NULL;
CREATE INDEX worklist_entry_facility_idx
    ON public.worklist_entry (organization_id, facility_id, priority DESC, created_at, id);

-- Append-only handoff history: a transfer names the previous assignee, the new
-- assignee, the reason and the actor, and can never be edited or removed.
CREATE TABLE public.handoff_event (
    organization_id   uuid NOT NULL,
    id                uuid NOT NULL DEFAULT gen_random_uuid(),
    worklist_entry_id uuid NOT NULL,
    from_subject      text,
    to_subject        text NOT NULL,
    reason            text,
    actor_id          uuid NOT NULL,
    occurred_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, id),
    -- A first assignment has no previous assignee and no reason; a transfer
    -- records both. The pair is never half populated.
    CHECK ((from_subject IS NULL) = (reason IS NULL)),
    FOREIGN KEY (organization_id, worklist_entry_id)
        REFERENCES public.worklist_entry(organization_id, id),
    FOREIGN KEY (organization_id, actor_id)
        REFERENCES public.user_identity(organization_id, id)
);

CREATE INDEX handoff_event_entry_idx
    ON public.handoff_event (organization_id, worklist_entry_id, occurred_at, id);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['check_in_verification', 'worklist_entry', 'handoff_event']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            $p$CREATE POLICY tenant_isolation ON public.%I
                 USING (organization_id = public.app_org())
                 WITH CHECK (organization_id = public.app_org())$p$, t);
    END LOOP;
END $$;

-- Verification and handoff records are ledgers, append-only for every role.
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['check_in_verification', 'handoff_event']
    LOOP
        EXECUTE format($d$CREATE TRIGGER %I_no_update_delete
            BEFORE UPDATE OR DELETE ON public.%I
            FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
        EXECUTE format($d$CREATE TRIGGER %I_no_truncate
            BEFORE TRUNCATE ON public.%I
            FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
    END LOOP;
END $$;

CREATE TRIGGER worklist_entry_no_delete
    BEFORE DELETE ON public.worklist_entry
    FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation();
CREATE TRIGGER worklist_entry_no_truncate
    BEFORE TRUNCATE ON public.worklist_entry
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation();

-- Actors and authors come from the authenticated subject of the active tenant,
-- never from a caller-supplied field.
CREATE OR REPLACE FUNCTION public.set_check_in_verification_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'check-in verification requires an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.verified_by := v_actor;
    RETURN NEW;
END $$;
CREATE TRIGGER check_in_verification_actor
    BEFORE INSERT ON public.check_in_verification
    FOR EACH ROW EXECUTE FUNCTION public.set_check_in_verification_actor();

CREATE OR REPLACE FUNCTION public.set_worklist_created_by()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'worklist creation requires an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.created_by := v_actor;
    RETURN NEW;
END $$;
CREATE TRIGGER worklist_entry_created_by
    BEFORE INSERT ON public.worklist_entry
    FOR EACH ROW EXECUTE FUNCTION public.set_worklist_created_by();

CREATE OR REPLACE FUNCTION public.set_handoff_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'handoff requires an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.actor_id := v_actor;
    RETURN NEW;
END $$;
CREATE TRIGGER handoff_event_actor
    BEFORE INSERT ON public.handoff_event
    FOR EACH ROW EXECUTE FUNCTION public.set_handoff_actor();

-- A worklist item may only be assigned to a currently active member of the same
-- organization. Re-deriving membership here makes deactivation fail closed even
-- if an application check is bypassed.
CREATE OR REPLACE FUNCTION public.guard_worklist_assignment()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.assignee_subject IS NOT NULL
       AND NOT public.has_active_membership(NEW.assignee_subject) THEN
        RAISE EXCEPTION 'worklist assignee must have active membership in the active organization'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER worklist_entry_assignment_guard
    BEFORE INSERT OR UPDATE ON public.worklist_entry
    FOR EACH ROW EXECUTE FUNCTION public.guard_worklist_assignment();

-- Assignment, priority and version are the only mutable fields. Ownership and
-- provenance are pinned, the version advances by exactly one, and an assignee
-- change always carries a fresh timestamp.
CREATE OR REPLACE FUNCTION public.guard_worklist_entry_update()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.id <> OLD.id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.appointment_id IS DISTINCT FROM OLD.appointment_id
       OR NEW.intake_reason IS DISTINCT FROM OLD.intake_reason
       OR NEW.patient_id <> OLD.patient_id
       OR NEW.facility_id <> OLD.facility_id
       OR NEW.created_by <> OLD.created_by
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'worklist entry ownership columns are immutable'
            USING ERRCODE = '42501';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'worklist entry version must advance by exactly one'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.assignee_subject IS DISTINCT FROM OLD.assignee_subject
       AND NEW.assigned_at IS NULL THEN
        RAISE EXCEPTION 'an assignment change requires an assignment timestamp'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER worklist_entry_update_guard
    BEFORE UPDATE ON public.worklist_entry
    FOR EACH ROW EXECUTE FUNCTION public.guard_worklist_entry_update();

GRANT SELECT, INSERT ON public.check_in_verification TO aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.check_in_verification FROM aurevia_app;

GRANT SELECT, INSERT, UPDATE ON public.worklist_entry TO aurevia_app;
REVOKE DELETE, TRUNCATE ON public.worklist_entry FROM aurevia_app;

GRANT SELECT, INSERT ON public.handoff_event TO aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.handoff_event FROM aurevia_app;

REVOKE ALL ON FUNCTION public.set_check_in_verification_actor() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.set_worklist_created_by() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.set_handoff_actor() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_worklist_assignment() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_worklist_entry_update() FROM PUBLIC;
