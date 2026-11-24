-- Requirement IDs: MSG-02, ARC-07, ARC-01, ARC-02, ARC-03, OPS-02
-- Change: expand
-- Conversation lifecycle foundation for patient-to-unit messaging (MSG-02).
--
-- A conversation belongs to one recipient unit and one verified patient
-- connection. Messages are an append-only ledger with a per-conversation
-- sequence; work-item facts (created, reopened, assigned, transferred,
-- resolved, closed) are a separate append-only ledger. The current projection
-- carries a version, a lifecycle state, an assignee and two read watermarks so
-- unread counts can be derived in the database without scanning clinical text.
--
-- Delivery, notification and first view (MSG-05) are deliberately NOT part of
-- this slice; a message row is the single publication fact here. Forced RLS
-- from V2 applies to all three tables and every foreign key carries the
-- organization, so a conversation cannot be re-pointed at another tenant.
--
-- Compatibility note: the offline db/check.sh runner applies V*.sql in shell
-- glob order, which places V33 before V4..V8. Flyway applies by numeric version
-- and is the authority. The guard below makes an out-of-order offline apply a
-- no-op instead of an error; it never skips a Flyway apply, because Flyway
-- always installs the prerequisites first.

DO $conversation_lifecycle$
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
        RAISE NOTICE 'V33 conversation lifecycle deferred: prerequisites not installed yet';
        RETURN;
    END IF;

    -- A verified patient-account -> chart link (IAM-05/MSG-01 prerequisite). It
    -- is intentionally self-contained so this slice does not depend on S-G3-04
    -- table names. Revocation is an audited status flip; the fold is re-read on
    -- every protected request, including a reopen of a resolved thread.
    EXECUTE $ddl$CREATE TABLE public.patient_link (
        organization_id    uuid NOT NULL REFERENCES public.organization(organization_id),
        subject            text NOT NULL,
        patient_id         uuid NOT NULL,
        active             boolean NOT NULL DEFAULT true,
        created_by         uuid NOT NULL,
        created_at         timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY (organization_id, subject, patient_id),
        FOREIGN KEY (organization_id, patient_id)
            REFERENCES public.patient_record(organization_id, id),
        FOREIGN KEY (organization_id, created_by)
            REFERENCES public.user_identity(organization_id, id)
    )$ddl$;

    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.patient_link_active(p_subject text, p_patient_id uuid)
    RETURNS boolean LANGUAGE sql STABLE SET search_path = pg_catalog
    AS $fn$
        SELECT EXISTS (
            SELECT 1 FROM public.patient_link l
             WHERE l.organization_id = public.app_org()
               AND l.subject = p_subject
               AND l.patient_id = p_patient_id
               AND l.active)
    $fn$$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.patient_link_active(text, uuid) FROM PUBLIC$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.patient_link_active(text, uuid) TO orevia_app$ddl$;

    EXECUTE $ddl$CREATE TABLE public.conversation (
        organization_id          uuid NOT NULL REFERENCES public.organization(organization_id),
        id                       uuid NOT NULL DEFAULT gen_random_uuid(),
        version                  integer NOT NULL DEFAULT 1 CHECK (version > 0),
        patient_id               uuid NOT NULL,
        patient_subject          text NOT NULL,
        unit_key                 text NOT NULL
            CHECK (unit_key IN ('clinic', 'lab', 'radiology', 'pt', 'pharmacy')),
        connection_id            text NOT NULL,
        topic                    text,
        state                    text NOT NULL
            CHECK (state IN ('awaiting_staff', 'awaiting_patient', 'resolved', 'closed')),
        assignee_subject         text,
        patient_read_sequence    integer NOT NULL DEFAULT 0 CHECK (patient_read_sequence >= 0),
        staff_read_sequence      integer NOT NULL DEFAULT 0 CHECK (staff_read_sequence >= 0),
        created_by               uuid NOT NULL,
        created_at               timestamptz NOT NULL DEFAULT now(),
        updated_at               timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY (organization_id, id),
        FOREIGN KEY (organization_id, patient_id)
            REFERENCES public.patient_record(organization_id, id),
        FOREIGN KEY (organization_id, created_by)
            REFERENCES public.user_identity(organization_id, id)
    )$ddl$;

    EXECUTE $ddl$CREATE INDEX conversation_inbox_idx ON public.conversation
        (organization_id, patient_subject, updated_at)$ddl$;
    EXECUTE $ddl$CREATE INDEX conversation_queue_idx ON public.conversation
        (organization_id, unit_key, state, updated_at)$ddl$;

    EXECUTE $ddl$CREATE TABLE public.conversation_message (
        organization_id     uuid NOT NULL,
        id                  uuid NOT NULL DEFAULT gen_random_uuid(),
        conversation_id     uuid NOT NULL,
        sequence            integer NOT NULL CHECK (sequence > 0),
        sender_kind         text NOT NULL CHECK (sender_kind IN ('patient', 'staff')),
        sender_subject      text NOT NULL,
        sender_role         text NOT NULL,
        body                text NOT NULL,
        created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
        PRIMARY KEY (organization_id, id),
        UNIQUE (organization_id, conversation_id, sequence),
        FOREIGN KEY (organization_id, conversation_id)
            REFERENCES public.conversation(organization_id, id)
    )$ddl$;

    EXECUTE $ddl$CREATE INDEX conversation_message_unread_idx ON public.conversation_message
        (organization_id, conversation_id, sender_kind, sequence)$ddl$;

    EXECUTE $ddl$CREATE TABLE public.work_item_event (
        organization_id       uuid NOT NULL,
        id                    uuid NOT NULL DEFAULT gen_random_uuid(),
        conversation_id       uuid NOT NULL,
        conversation_version  integer NOT NULL CHECK (conversation_version > 0),
        event_type            text NOT NULL
            CHECK (event_type IN ('created', 'reopened', 'assigned', 'transferred',
                                  'resolved', 'closed')),
        actor_subject         text,
        from_state            text,
        to_state              text,
        reason                text,
        occurred_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
        PRIMARY KEY (organization_id, id),
        FOREIGN KEY (organization_id, conversation_id)
            REFERENCES public.conversation(organization_id, id)
    )$ddl$;

    EXECUTE $ddl$CREATE INDEX work_item_event_trail_idx ON public.work_item_event
        (organization_id, conversation_id, occurred_at, id)$ddl$;

    EXECUTE $ddl$DO $rls$
    DECLARE t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY['patient_link', 'conversation', 'conversation_message', 'work_item_event']
        LOOP
            EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
            EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
            EXECUTE format(
                $p$CREATE POLICY tenant_isolation ON public.%I
                     USING (organization_id = public.app_org())
                     WITH CHECK (organization_id = public.app_org())$p$, t);
        END LOOP;
    END $rls$$ddl$;

    -- Message and work-item ledgers are immutable for every role. A conversation
    -- is never deleted; it is closed through the guarded state transition.
    EXECUTE $ddl$CREATE TRIGGER conversation_message_no_update_delete
        BEFORE UPDATE OR DELETE ON public.conversation_message
        FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER conversation_message_no_truncate
        BEFORE TRUNCATE ON public.conversation_message
        FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER work_item_event_no_update_delete
        BEFORE UPDATE OR DELETE ON public.work_item_event
        FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER work_item_event_no_truncate
        BEFORE TRUNCATE ON public.work_item_event
        FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER conversation_no_delete
        BEFORE DELETE ON public.conversation
        FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER conversation_no_truncate
        BEFORE TRUNCATE ON public.conversation
        FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;

    -- Authorship is always the authenticated actor of the active tenant.
    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.set_conversation_created_by_from_actor()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    DECLARE v_actor uuid;
    BEGIN
        SELECT id INTO v_actor FROM public.user_identity
         WHERE organization_id = NEW.organization_id
           AND subject = NULLIF(current_setting('app.actor', true), '');
        IF v_actor IS NULL THEN
            RAISE EXCEPTION 'conversation author must be an authenticated user of the active tenant'
                USING ERRCODE = '42501';
        END IF;
        NEW.created_by := v_actor;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER conversation_created_by_actor
        BEFORE INSERT ON public.conversation
        FOR EACH ROW EXECUTE FUNCTION public.set_conversation_created_by_from_actor()$ddl$;

    -- Database second line of defence: a conversation may only be opened for a
    -- patient whose verified link to the supplied subject is still active. The
    -- same fold is re-read by the service on every reply, including a reopen.
    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.guard_conversation_insert()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    BEGIN
        IF NOT public.patient_link_active(NEW.patient_subject, NEW.patient_id) THEN
            RAISE EXCEPTION 'no active verified patient link for this conversation'
                USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER conversation_verified_link_guard
        BEFORE INSERT ON public.conversation
        FOR EACH ROW EXECUTE FUNCTION public.guard_conversation_insert()$ddl$;

    -- A message sender is the authenticated actor, never a caller-supplied identity.
    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.set_message_sender_from_actor()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    BEGIN
        NEW.sender_subject := NULLIF(current_setting('app.actor', true), '');
        IF NEW.sender_subject IS NULL THEN
            RAISE EXCEPTION 'message sender must be an authenticated user of the active tenant'
                USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER conversation_message_sender_actor
        BEFORE INSERT ON public.conversation_message
        FOR EACH ROW EXECUTE FUNCTION public.set_message_sender_from_actor()$ddl$;

    -- The database is the second line of defence for the MSG-02 lifecycle.
    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.guard_conversation_update()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    BEGIN
        IF NEW.id <> OLD.id
           OR NEW.organization_id <> OLD.organization_id
           OR NEW.patient_id <> OLD.patient_id
           OR NEW.patient_subject <> OLD.patient_subject
           OR NEW.unit_key <> OLD.unit_key
           OR NEW.connection_id <> OLD.connection_id
           OR NEW.created_by <> OLD.created_by
           OR NEW.created_at <> OLD.created_at THEN
            RAISE EXCEPTION 'conversation ownership columns are immutable'
                USING ERRCODE = '42501';
        END IF;
        IF NEW.version <> OLD.version + 1 THEN
            RAISE EXCEPTION 'conversation version must advance by exactly one'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.patient_read_sequence < OLD.patient_read_sequence
           OR NEW.staff_read_sequence < OLD.staff_read_sequence THEN
            RAISE EXCEPTION 'read watermarks must not move backwards'
                USING ERRCODE = '23514';
        END IF;
        -- Every forward transition is legal except leaving a closed thread;
        -- a closed thread is read-only for all authorized viewers.
        IF NEW.state <> OLD.state AND OLD.state = 'closed' THEN
            RAISE EXCEPTION 'a closed conversation is read-only'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER conversation_update_guard
        BEFORE UPDATE ON public.conversation
        FOR EACH ROW EXECUTE FUNCTION public.guard_conversation_update()$ddl$;

    EXECUTE $ddl$GRANT SELECT, INSERT, UPDATE ON public.patient_link TO orevia_app$ddl$;
    EXECUTE $ddl$REVOKE DELETE, TRUNCATE ON public.patient_link FROM orevia_app$ddl$;
    EXECUTE $ddl$GRANT SELECT, INSERT, UPDATE ON public.conversation TO orevia_app$ddl$;
    EXECUTE $ddl$REVOKE DELETE, TRUNCATE ON public.conversation FROM orevia_app$ddl$;
    EXECUTE $ddl$GRANT SELECT, INSERT ON public.conversation_message TO orevia_app$ddl$;
    EXECUTE $ddl$REVOKE UPDATE, DELETE, TRUNCATE ON public.conversation_message FROM orevia_app$ddl$;
    EXECUTE $ddl$GRANT SELECT, INSERT ON public.work_item_event TO orevia_app$ddl$;
    EXECUTE $ddl$REVOKE UPDATE, DELETE, TRUNCATE ON public.work_item_event FROM orevia_app$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.guard_conversation_insert() FROM PUBLIC$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.guard_conversation_insert() TO orevia_app$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.set_conversation_created_by_from_actor() FROM PUBLIC$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.set_message_sender_from_actor() FROM PUBLIC$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.guard_conversation_update() FROM PUBLIC$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.set_conversation_created_by_from_actor() TO orevia_app$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.set_message_sender_from_actor() TO orevia_app$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.guard_conversation_update() TO orevia_app$ddl$;
END $conversation_lifecycle$;
