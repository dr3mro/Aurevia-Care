-- Requirement IDs: XCH-05, ARC-01, ARC-02, ARC-03, ARC-05, OPS-02
-- Change: expand
-- Critical-result alerting foundation: verified finding, responsible owner, due
-- time, approved escalation policy snapshot and an append-only fact ledger.
-- Verification, escalation attempts, read-back, acknowledgment, follow-up and
-- patient release are separate immutable facts; delivery or patient release
-- never advances the clinical acknowledgment state. A snapshot of the approved
-- escalation policy is stamped at assignment so a later policy change cannot
-- silently rewrite which channel an overdue alert must use.
--
-- Forced RLS from V2 applies to both tables and every foreign key carries the
-- organization, so an alert cannot be re-pointed at another tenant's patient.
--
-- Compatibility note: the offline db/check.sh runner applies V*.sql in shell
-- glob order, which places every two-digit version before V3. Flyway applies by
-- numeric version and is the authority. The guard below makes an out-of-order
-- offline apply a no-op instead of an error; it never skips a Flyway apply,
-- because Flyway always installs the prerequisites first.

DO $critical_results$
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
       OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'aurevia_app') THEN
        RAISE NOTICE 'V23 critical results deferred: prerequisites not installed yet';
        RETURN;
    END IF;

    EXECUTE $ddl$CREATE TABLE public.critical_result (
        organization_id       uuid NOT NULL REFERENCES public.organization(organization_id),
        id                    uuid NOT NULL DEFAULT gen_random_uuid(),
        version               integer NOT NULL DEFAULT 1 CHECK (version > 0),
        patient_id            uuid NOT NULL,
        source_system         text NOT NULL,
        source_id             text NOT NULL,
        order_reference       text,
        accession             text,
        result_summary        text NOT NULL,
        observed_value        text NOT NULL,
        unit                  text NOT NULL,
        read_back_required    boolean NOT NULL,
        state                 text NOT NULL
            CHECK (state IN ('unverified', 'awaiting_ack', 'acknowledged', 'closed')),
        verified_by_subject   text,
        verified_at           timestamptz,
        owner_subject         text,
        due_at                timestamptz,
        primary_channel       text,
        backup_channel        text,
        backup_recipient      text,
        created_by            uuid NOT NULL,
        created_at            timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY (organization_id, id),
        FOREIGN KEY (organization_id, patient_id)
            REFERENCES public.patient_record(organization_id, id),
        FOREIGN KEY (organization_id, created_by)
            REFERENCES public.user_identity(organization_id, id)
    )$ddl$;

    EXECUTE $ddl$CREATE INDEX critical_result_overdue_idx ON public.critical_result
        (organization_id, state, due_at)$ddl$;

    EXECUTE $ddl$CREATE TABLE public.critical_result_event (
        organization_id         uuid NOT NULL,
        id                      uuid NOT NULL DEFAULT gen_random_uuid(),
        critical_result_id      uuid NOT NULL,
        critical_result_version integer NOT NULL CHECK (critical_result_version > 0),
        event_type              text NOT NULL
            CHECK (event_type IN ('registered', 'owner_assigned', 'verified',
                                  'escalation_attempt', 'read_back', 'acknowledged',
                                  'follow_up', 'patient_released', 'roster_gap')),
        actor_id                uuid NOT NULL,
        recipient               text,
        channel                 text,
        outcome                 text,
        detail                  text,
        occurred_at             timestamptz NOT NULL DEFAULT clock_timestamp(),
        PRIMARY KEY (organization_id, id),
        FOREIGN KEY (organization_id, critical_result_id)
            REFERENCES public.critical_result(organization_id, id),
        FOREIGN KEY (organization_id, actor_id)
            REFERENCES public.user_identity(organization_id, id)
    )$ddl$;

    EXECUTE $ddl$CREATE INDEX critical_result_event_trail_idx ON public.critical_result_event
        (organization_id, critical_result_id, occurred_at, id)$ddl$;
    EXECUTE $ddl$CREATE INDEX critical_result_event_report_idx ON public.critical_result_event
        (organization_id, event_type, outcome, occurred_at)$ddl$;

    EXECUTE $ddl$DO $rls$
    DECLARE t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY['critical_result', 'critical_result_event']
        LOOP
            EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
            EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
            EXECUTE format(
                $p$CREATE POLICY tenant_isolation ON public.%I
                     USING (organization_id = public.app_org())
                     WITH CHECK (organization_id = public.app_org())$p$, t);
        END LOOP;
    END $rls$$ddl$;

    -- The fact ledger is immutable for every role, including the migration owner.
    EXECUTE $ddl$CREATE TRIGGER critical_result_event_no_update_delete
        BEFORE UPDATE OR DELETE ON public.critical_result_event
        FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER critical_result_event_no_truncate
        BEFORE TRUNCATE ON public.critical_result_event
        FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER critical_result_no_delete
        BEFORE DELETE ON public.critical_result
        FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER critical_result_no_truncate
        BEFORE TRUNCATE ON public.critical_result
        FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;

    -- Authorship is always the authenticated actor of the active tenant.
    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.set_critical_result_created_by_from_actor()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    DECLARE v_actor uuid;
    BEGIN
        SELECT id INTO v_actor FROM public.user_identity
         WHERE organization_id = NEW.organization_id
           AND subject = NULLIF(current_setting('app.actor', true), '');
        IF v_actor IS NULL THEN
            RAISE EXCEPTION 'critical result author must be an authenticated user of the active tenant'
                USING ERRCODE = '42501';
        END IF;
        NEW.created_by := v_actor;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER critical_result_created_by_actor
        BEFORE INSERT ON public.critical_result
        FOR EACH ROW EXECUTE FUNCTION public.set_critical_result_created_by_from_actor()$ddl$;

    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.set_critical_event_actor_from_actor()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    DECLARE v_actor uuid;
    BEGIN
        SELECT id INTO v_actor FROM public.user_identity
         WHERE organization_id = NEW.organization_id
           AND subject = NULLIF(current_setting('app.actor', true), '');
        IF v_actor IS NULL THEN
            RAISE EXCEPTION 'critical result event actor must be an authenticated user of the active tenant'
                USING ERRCODE = '42501';
        END IF;
        NEW.actor_id := v_actor;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER critical_result_event_actor
        BEFORE INSERT ON public.critical_result_event
        FOR EACH ROW EXECUTE FUNCTION public.set_critical_event_actor_from_actor()$ddl$;

    -- The database is the second line of defence for the XCH-05 invariants.
    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.guard_critical_result_update()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    BEGIN
        IF NEW.id <> OLD.id
           OR NEW.organization_id <> OLD.organization_id
           OR NEW.patient_id <> OLD.patient_id
           OR NEW.source_system <> OLD.source_system
           OR NEW.source_id <> OLD.source_id
           OR NEW.result_summary <> OLD.result_summary
           OR NEW.observed_value <> OLD.observed_value
           OR NEW.unit <> OLD.unit
           OR NEW.read_back_required <> OLD.read_back_required
           OR NEW.created_by <> OLD.created_by
           OR NEW.created_at <> OLD.created_at THEN
            RAISE EXCEPTION 'critical result ownership columns are immutable'
                USING ERRCODE = '42501';
        END IF;
        IF NEW.version <> OLD.version + 1 THEN
            RAISE EXCEPTION 'critical result version must advance by exactly one'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.state <> OLD.state AND NOT (
                (OLD.state = 'unverified' AND NEW.state = 'awaiting_ack')
             OR (OLD.state = 'awaiting_ack' AND NEW.state = 'acknowledged')
             OR (OLD.state = 'acknowledged' AND NEW.state = 'closed')) THEN
            RAISE EXCEPTION 'illegal critical result transition % -> %', OLD.state, NEW.state
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER critical_result_update_guard
        BEFORE UPDATE ON public.critical_result
        FOR EACH ROW EXECUTE FUNCTION public.guard_critical_result_update()$ddl$;

    EXECUTE $ddl$GRANT SELECT, INSERT, UPDATE ON public.critical_result TO aurevia_app$ddl$;
    EXECUTE $ddl$REVOKE DELETE, TRUNCATE ON public.critical_result FROM aurevia_app$ddl$;
    EXECUTE $ddl$GRANT SELECT, INSERT ON public.critical_result_event TO aurevia_app$ddl$;
    EXECUTE $ddl$REVOKE UPDATE, DELETE, TRUNCATE ON public.critical_result_event FROM aurevia_app$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.set_critical_result_created_by_from_actor() FROM PUBLIC$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.set_critical_event_actor_from_actor() FROM PUBLIC$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.guard_critical_result_update() FROM PUBLIC$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.set_critical_result_created_by_from_actor() TO aurevia_app$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.set_critical_event_actor_from_actor() TO aurevia_app$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.guard_critical_result_update() TO aurevia_app$ddl$;
END $critical_results$;
