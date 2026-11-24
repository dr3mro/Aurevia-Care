-- Requirement IDs: RX-02, ARC-01, ARC-02, ARC-03, ARC-05, OPS-02
-- Change: expand
-- Prescriber attestation and controlled prescription transmission: the RX-02
-- record references an RX-01 draft id without a cross-story foreign key, one
-- immutable submission row is created per stable submission id, and an
-- append-only fact ledger keeps submitted, delivery-confirmed, pharmacy
-- response, renewal, cancellation and dispensing evidence separate.
--
-- Forced RLS from V2 applies to every table and every foreign key carries the
-- organization. A guarded update enforces the same state machine and immutable
-- clinical columns as the domain service. The submission ledger is keyed by the
-- stable submission id and constrained to one row per prescription, so neither
-- a replayed insert nor a retry can create a second downstream prescription.
-- The verified-absent-before-resend retry gate is enforced in the domain service
-- and evidenced by the append-only partner-status event ledger.
--
-- Compatibility note: the offline db/check.sh runner applies V*.sql in shell
-- glob order, which places this two-digit version before V3. Flyway applies by
-- numeric version and is the authority. The guard below makes an out-of-order
-- offline apply a no-op instead of an error; it never skips a Flyway apply.

DO $rx02$
BEGIN
    IF to_regclass('public.organization') IS NULL
       OR to_regclass('public.user_identity') IS NULL
       OR to_regclass('public.patient_record') IS NULL
       OR NOT EXISTS (
              SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'app_org')
       OR NOT EXISTS (
              SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'reject_immutable_mutation')
       OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'orevia_app') THEN
        RAISE NOTICE 'V44 prescription transmission deferred: prerequisites not installed yet';
        RETURN;
    END IF;

    EXECUTE $ddl$CREATE TABLE public.rx02_prescription (
        organization_id       uuid NOT NULL REFERENCES public.organization(organization_id),
        id                    uuid NOT NULL,
        version               integer NOT NULL DEFAULT 1 CHECK (version > 0),
        patient_id            uuid NOT NULL,
        medication            text NOT NULL,
        strength              text NOT NULL,
        dose                  text NOT NULL,
        route                 text NOT NULL,
        frequency             text NOT NULL,
        quantity              integer NOT NULL CHECK (quantity > 0),
        prescriber_subject    text NOT NULL,
        destination_pharmacy  text,
        state                 text NOT NULL
            CHECK (state IN ('draft', 'attested', 'submitted', 'ambiguous',
                             'delivery_confirmed', 'accepted', 'rejected',
                             'clarification', 'dispensed', 'printed', 'cancelled')),
        channel               text,
        submission_id         uuid,
        retry_allowed         boolean NOT NULL DEFAULT false,
        created_by            uuid NOT NULL,
        created_at            timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY (organization_id, id),
        CONSTRAINT rx02_one_submission_per_prescription UNIQUE (organization_id, submission_id),
        FOREIGN KEY (organization_id, patient_id)
            REFERENCES public.patient_record(organization_id, id),
        FOREIGN KEY (organization_id, created_by)
            REFERENCES public.user_identity(organization_id, id)
    )$ddl$;

    EXECUTE $ddl$CREATE TABLE public.rx02_submission (
        organization_id       uuid NOT NULL,
        submission_id         uuid NOT NULL,
        prescription_id       uuid NOT NULL,
        channel               text NOT NULL,
        created_by            uuid NOT NULL,
        created_at            timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY (organization_id, submission_id),
        CONSTRAINT rx02_one_submission_row_per_prescription UNIQUE (organization_id, prescription_id),
        FOREIGN KEY (organization_id, prescription_id)
            REFERENCES public.rx02_prescription(organization_id, id),
        FOREIGN KEY (organization_id, created_by)
            REFERENCES public.user_identity(organization_id, id)
    )$ddl$;

    EXECUTE $ddl$CREATE TABLE public.rx02_event (
        organization_id       uuid NOT NULL,
        id                    uuid NOT NULL DEFAULT gen_random_uuid(),
        prescription_id       uuid NOT NULL,
        prescription_version  integer NOT NULL CHECK (prescription_version > 0),
        event_type            text NOT NULL
            CHECK (event_type IN ('attested', 'submitted', 'delivery_confirmed',
                                  'partner_status_verified', 'printed',
                                  'pharmacy_accepted', 'pharmacy_rejected',
                                  'clarification', 'renewal', 'cancelled',
                                  'dispensing_evidence')),
        actor_id              uuid NOT NULL,
        channel               text,
        submission_id         uuid,
        outcome               text,
        detail                text,
        occurred_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
        PRIMARY KEY (organization_id, id),
        FOREIGN KEY (organization_id, prescription_id)
            REFERENCES public.rx02_prescription(organization_id, id),
        FOREIGN KEY (organization_id, actor_id)
            REFERENCES public.user_identity(organization_id, id)
    )$ddl$;

    EXECUTE $ddl$CREATE INDEX rx02_event_trail_idx ON public.rx02_event
        (organization_id, prescription_id, occurred_at, id)$ddl$;
    EXECUTE $ddl$CREATE INDEX rx02_event_submission_idx ON public.rx02_event
        (organization_id, submission_id, event_type)$ddl$;

    EXECUTE $ddl$DO $rls$
    DECLARE t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY['rx02_prescription', 'rx02_submission', 'rx02_event']
        LOOP
            EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
            EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
            EXECUTE format(
                $p$CREATE POLICY tenant_isolation ON public.%I
                     USING (organization_id = public.app_org())
                     WITH CHECK (organization_id = public.app_org())$p$, t);
        END LOOP;
    END $rls$$ddl$;

    EXECUTE $ddl$CREATE TRIGGER rx02_event_no_update_delete
        BEFORE UPDATE OR DELETE ON public.rx02_event
        FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER rx02_event_no_truncate
        BEFORE TRUNCATE ON public.rx02_event
        FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER rx02_prescription_no_delete
        BEFORE DELETE ON public.rx02_prescription
        FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER rx02_prescription_no_truncate
        BEFORE TRUNCATE ON public.rx02_prescription
        FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER rx02_submission_no_update_delete
        BEFORE UPDATE OR DELETE ON public.rx02_submission
        FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER rx02_submission_no_truncate
        BEFORE TRUNCATE ON public.rx02_submission
        FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;

    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.set_rx02_created_by_from_actor()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    DECLARE v_actor uuid;
    BEGIN
        SELECT id INTO v_actor FROM public.user_identity
         WHERE organization_id = NEW.organization_id
           AND subject = NULLIF(current_setting('app.actor', true), '');
        IF v_actor IS NULL THEN
            RAISE EXCEPTION 'prescription author must be an authenticated user of the active tenant'
                USING ERRCODE = '42501';
        END IF;
        NEW.created_by := v_actor;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER rx02_created_by_actor
        BEFORE INSERT ON public.rx02_prescription
        FOR EACH ROW EXECUTE FUNCTION public.set_rx02_created_by_from_actor()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER rx02_submission_created_by_actor
        BEFORE INSERT ON public.rx02_submission
        FOR EACH ROW EXECUTE FUNCTION public.set_rx02_created_by_from_actor()$ddl$;

    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.set_rx02_event_actor_from_actor()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    DECLARE v_actor uuid;
    BEGIN
        SELECT id INTO v_actor FROM public.user_identity
         WHERE organization_id = NEW.organization_id
           AND subject = NULLIF(current_setting('app.actor', true), '');
        IF v_actor IS NULL THEN
            RAISE EXCEPTION 'prescription event actor must be an authenticated user of the active tenant'
                USING ERRCODE = '42501';
        END IF;
        NEW.actor_id := v_actor;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER rx02_event_actor
        BEFORE INSERT ON public.rx02_event
        FOR EACH ROW EXECUTE FUNCTION public.set_rx02_event_actor_from_actor()$ddl$;

    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.guard_rx02_prescription_update()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    BEGIN
        IF NEW.id <> OLD.id
           OR NEW.organization_id <> OLD.organization_id
           OR NEW.patient_id <> OLD.patient_id
           OR NEW.medication <> OLD.medication
           OR NEW.strength <> OLD.strength
           OR NEW.dose <> OLD.dose
           OR NEW.route <> OLD.route
           OR NEW.frequency <> OLD.frequency
           OR NEW.quantity <> OLD.quantity
           OR NEW.prescriber_subject <> OLD.prescriber_subject
           OR NEW.destination_pharmacy IS DISTINCT FROM OLD.destination_pharmacy
           OR NEW.created_by <> OLD.created_by
           OR NEW.created_at <> OLD.created_at THEN
            RAISE EXCEPTION 'prescription identity and clinical columns are immutable'
                USING ERRCODE = '42501';
        END IF;
        -- A channel and a submission id are stamped once and never rewritten.
        -- Reusing the same id on a retry is therefore the only legal path.
        IF OLD.channel IS NOT NULL AND NEW.channel IS DISTINCT FROM OLD.channel THEN
            RAISE EXCEPTION 'prescription routing channel is immutable'
                USING ERRCODE = '42501';
        END IF;
        IF OLD.submission_id IS NOT NULL AND NEW.submission_id IS DISTINCT FROM OLD.submission_id THEN
            RAISE EXCEPTION 'prescription submission id is immutable'
                USING ERRCODE = '42501';
        END IF;
        IF NEW.version <> OLD.version + 1 THEN
            RAISE EXCEPTION 'prescription version must advance by exactly one'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.state <> OLD.state AND NOT (
                (OLD.state = 'draft' AND NEW.state IN ('attested', 'cancelled'))
             OR (OLD.state = 'attested' AND NEW.state IN ('submitted', 'printed', 'cancelled'))
             OR (OLD.state = 'submitted' AND NEW.state IN ('ambiguous', 'delivery_confirmed', 'cancelled'))
             OR (OLD.state = 'ambiguous' AND NEW.state IN ('submitted', 'delivery_confirmed', 'cancelled'))
             OR (OLD.state = 'delivery_confirmed' AND NEW.state IN ('accepted', 'rejected', 'clarification', 'cancelled'))
             OR (OLD.state = 'clarification' AND NEW.state IN ('accepted', 'rejected', 'cancelled'))
             OR (OLD.state = 'accepted' AND NEW.state IN ('dispensed', 'cancelled'))) THEN
            RAISE EXCEPTION 'illegal prescription transition % -> %', OLD.state, NEW.state
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER rx02_prescription_update_guard
        BEFORE UPDATE ON public.rx02_prescription
        FOR EACH ROW EXECUTE FUNCTION public.guard_rx02_prescription_update()$ddl$;

    EXECUTE $ddl$GRANT SELECT, INSERT, UPDATE ON public.rx02_prescription TO orevia_app$ddl$;
    EXECUTE $ddl$REVOKE DELETE, TRUNCATE ON public.rx02_prescription FROM orevia_app$ddl$;
    EXECUTE $ddl$GRANT SELECT, INSERT ON public.rx02_submission TO orevia_app$ddl$;
    EXECUTE $ddl$REVOKE UPDATE, DELETE, TRUNCATE ON public.rx02_submission FROM orevia_app$ddl$;
    EXECUTE $ddl$GRANT SELECT, INSERT ON public.rx02_event TO orevia_app$ddl$;
    EXECUTE $ddl$REVOKE UPDATE, DELETE, TRUNCATE ON public.rx02_event FROM orevia_app$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.set_rx02_created_by_from_actor() FROM PUBLIC$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.set_rx02_event_actor_from_actor() FROM PUBLIC$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.guard_rx02_prescription_update() FROM PUBLIC$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.set_rx02_created_by_from_actor() TO orevia_app$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.set_rx02_event_actor_from_actor() TO orevia_app$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.guard_rx02_prescription_update() TO orevia_app$ddl$;
END $rx02$;
