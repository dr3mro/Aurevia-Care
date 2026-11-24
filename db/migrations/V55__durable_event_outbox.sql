-- Requirement IDs: ARC-05, ARC-01, ARC-02, ARC-03, OPS-02
-- Change: expand
-- Durable event outbox, consumer deduplication, bounded retry, restricted
-- dead-letter review and replay provenance.
--
-- The outbox row, its audit fact and its idempotency key are committed in one
-- transaction by the application service; this schema makes the idempotency key
-- unique inside (organization, sender, event_type) and makes the append-only
-- fact ledgers immutable for every role, including the migration owner.
--
-- Forced RLS from V2 applies to every table and each foreign key carries the
-- organization, so identical local event or receipt identifiers in two tenants
-- remain isolated. Replays keep the original event id and are separate facts.
--
-- Compatibility note: the offline db/check.sh runner applies V*.sql in shell
-- glob order, which places V55 before V5 and later two-digit versions. Flyway
-- applies by numeric version and is the authority. The guard below makes an
-- out-of-order offline apply a no-op; prerequisites (V1-V3) are present at that
-- point in both orders.

DO $durable_event$
BEGIN
    IF to_regclass('public.organization') IS NULL
       OR NOT EXISTS (
              SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'app_org')
       OR NOT EXISTS (
              SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'reject_immutable_mutation')
       OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'aurevia_app') THEN
        RAISE NOTICE 'V55 durable events deferred: prerequisites not installed yet';
        RETURN;
    END IF;

    EXECUTE $ddl$CREATE TABLE public.delivery_outbox (
        organization_id       uuid NOT NULL REFERENCES public.organization(organization_id),
        id                    uuid NOT NULL DEFAULT gen_random_uuid(),
        aggregate_id          uuid NOT NULL,
        aggregate_version     integer NOT NULL CHECK (aggregate_version > 0),
        sender                text NOT NULL,
        event_type            text NOT NULL,
        namespace             text NOT NULL,
        payload_digest        text NOT NULL,
        idempotency_key       text NOT NULL,
        state                 text NOT NULL
            CHECK (state IN ('pending', 'sent', 'delivered', 'failed', 'dead_lettered')),
        attempt               integer NOT NULL DEFAULT 0 CHECK (attempt >= 0),
        next_attempt_at       timestamptz,
        created_at            timestamptz NOT NULL DEFAULT now(),
        detail                text,
        actor_subject         text NOT NULL,
        PRIMARY KEY (organization_id, id),
        UNIQUE (organization_id, sender, event_type, idempotency_key)
    )$ddl$;

    EXECUTE $ddl$CREATE INDEX delivery_outbox_due_idx ON public.delivery_outbox
        (organization_id, state, next_attempt_at)$ddl$;

    EXECUTE $ddl$CREATE TABLE public.delivery_receipt (
        organization_id       uuid NOT NULL REFERENCES public.organization(organization_id),
        id                    uuid NOT NULL DEFAULT gen_random_uuid(),
        external_event_id     text NOT NULL,
        sender                text NOT NULL,
        event_type            text NOT NULL,
        namespace             text NOT NULL,
        aggregate_id          uuid NOT NULL,
        aggregate_version     integer NOT NULL CHECK (aggregate_version > 0),
        order_id              uuid,
        patient_id            uuid,
        outcome               text NOT NULL
            CHECK (outcome IN ('accepted', 'duplicate', 'held_out_of_order', 'rejected')),
        detail                text,
        processed_at          timestamptz NOT NULL DEFAULT now(),
        actor_subject         text NOT NULL,
        PRIMARY KEY (organization_id, id),
        UNIQUE (organization_id, namespace, sender, event_type, external_event_id)
    )$ddl$;

    EXECUTE $ddl$CREATE INDEX delivery_receipt_ordering_idx ON public.delivery_receipt
        (organization_id, namespace, sender, event_type, aggregate_id, aggregate_version DESC)$ddl$;

    EXECUTE $ddl$CREATE TABLE public.delivery_dead_letter (
        organization_id       uuid NOT NULL REFERENCES public.organization(organization_id),
        event_id              uuid NOT NULL,
        reason                text NOT NULL,
        attempt               integer NOT NULL CHECK (attempt >= 0),
        failed_at             timestamptz NOT NULL DEFAULT now(),
        reviewer_subject      text,
        reviewed_at           timestamptz,
        resolution            text CHECK (resolution IN ('requeued', 'resolved')),
        review_reason         text,
        PRIMARY KEY (organization_id, event_id),
        FOREIGN KEY (organization_id, event_id)
            REFERENCES public.delivery_outbox(organization_id, id)
    )$ddl$;

    EXECUTE $ddl$CREATE INDEX delivery_dead_letter_queue_idx ON public.delivery_dead_letter
        (organization_id, failed_at)$ddl$;

    EXECUTE $ddl$CREATE TABLE public.delivery_replay (
        organization_id       uuid NOT NULL REFERENCES public.organization(organization_id),
        id                    uuid NOT NULL DEFAULT gen_random_uuid(),
        original_event_id     uuid NOT NULL,
        reason                text NOT NULL,
        authorized_by_subject text NOT NULL,
        replayed_at           timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY (organization_id, id),
        FOREIGN KEY (organization_id, original_event_id)
            REFERENCES public.delivery_outbox(organization_id, id)
    )$ddl$;

    EXECUTE $ddl$CREATE TABLE public.delivery_link (
        organization_id       uuid NOT NULL REFERENCES public.organization(organization_id),
        id                    uuid NOT NULL DEFAULT gen_random_uuid(),
        external_event_id     text NOT NULL,
        order_id              uuid NOT NULL,
        patient_id            uuid NOT NULL,
        target_aggregate_id   uuid NOT NULL,
        linked_by_subject     text NOT NULL,
        linked_at             timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY (organization_id, id)
    )$ddl$;

    EXECUTE $ddl$CREATE TABLE public.delivery_audit (
        organization_id       uuid NOT NULL REFERENCES public.organization(organization_id),
        id                    uuid NOT NULL DEFAULT gen_random_uuid(),
        actor_subject         text NOT NULL,
        purpose               text NOT NULL,
        action                text NOT NULL,
        resource_id           text NOT NULL,
        correlation_id        text NOT NULL,
        outcome               text NOT NULL,
        occurred_at           timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY (organization_id, id)
    )$ddl$;

    EXECUTE $ddl$DO $rls$
    DECLARE t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY['delivery_outbox', 'delivery_receipt', 'delivery_dead_letter',
                                 'delivery_replay', 'delivery_link', 'delivery_audit']
        LOOP
            EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
            EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
            EXECUTE format(
                $p$CREATE POLICY tenant_isolation ON public.%I
                     USING (organization_id = public.app_org())
                     WITH CHECK (organization_id = public.app_org())$p$, t);
        END LOOP;
    END $rls$$ddl$;

    -- Receipts, replay facts, links and audit are immutable for every role.
    EXECUTE $ddl$DO $immutable$
    DECLARE t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY['delivery_receipt', 'delivery_replay',
                                 'delivery_link', 'delivery_audit']
        LOOP
            EXECUTE format(
                'CREATE TRIGGER %I_no_update_delete BEFORE UPDATE OR DELETE ON public.%I '
                'FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()',
                t, t);
            EXECUTE format(
                'CREATE TRIGGER %I_no_truncate BEFORE TRUNCATE ON public.%I '
                'FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()',
                t, t);
        END LOOP;
    END $immutable$$ddl$;

    -- Authorship is always the authenticated actor of the active tenant.
    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.set_delivery_actor_subject()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    BEGIN
        NEW.actor_subject := NULLIF(current_setting('app.actor', true), '');
        IF NEW.actor_subject IS NULL THEN
            RAISE EXCEPTION 'delivery facts require an authenticated actor'
                USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END $fn$$ddl$;

    EXECUTE $ddl$DO $actors$
    DECLARE t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY['delivery_outbox', 'delivery_receipt', 'delivery_audit']
        LOOP
            EXECUTE format(
                'CREATE TRIGGER %I_actor BEFORE INSERT ON public.%I '
                'FOR EACH ROW EXECUTE FUNCTION public.set_delivery_actor_subject()', t, t);
        END LOOP;
    END $actors$$ddl$;

    -- Outbox state advances by exactly one version and never rewrites identity
    -- or payload provenance.
    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.guard_delivery_outbox_update()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    BEGIN
        IF NEW.id <> OLD.id
           OR NEW.organization_id <> OLD.organization_id
           OR NEW.aggregate_id <> OLD.aggregate_id
           OR NEW.sender <> OLD.sender
           OR NEW.event_type <> OLD.event_type
           OR NEW.namespace <> OLD.namespace
           OR NEW.payload_digest <> OLD.payload_digest
           OR NEW.idempotency_key <> OLD.idempotency_key
           OR NEW.created_at <> OLD.created_at
           OR NEW.actor_subject <> OLD.actor_subject THEN
            RAISE EXCEPTION 'delivery outbox identity columns are immutable'
                USING ERRCODE = '42501';
        END IF;
        IF NEW.aggregate_version <> OLD.aggregate_version + 1 THEN
            RAISE EXCEPTION 'delivery outbox version must advance by exactly one'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER delivery_outbox_update_guard
        BEFORE UPDATE ON public.delivery_outbox
        FOR EACH ROW EXECUTE FUNCTION public.guard_delivery_outbox_update()$ddl$;

    -- Dead-letter review is a separate, single, audited event: immutable
    -- failure facts, review fields set once.
    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.guard_delivery_dead_letter_review()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    BEGIN
        IF NEW.event_id <> OLD.event_id
           OR NEW.organization_id <> OLD.organization_id
           OR NEW.reason <> OLD.reason
           OR NEW.attempt <> OLD.attempt
           OR NEW.failed_at <> OLD.failed_at THEN
            RAISE EXCEPTION 'dead-letter failure facts are immutable'
                USING ERRCODE = '42501';
        END IF;
        IF OLD.reviewer_subject IS NOT NULL
           OR NEW.reviewer_subject IS NULL
           OR NEW.reviewed_at IS NULL
           OR NEW.resolution IS NULL THEN
            RAISE EXCEPTION 'dead-letter review requires an unreviewed entry and a complete decision'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER delivery_dead_letter_review_guard
        BEFORE UPDATE ON public.delivery_dead_letter
        FOR EACH ROW EXECUTE FUNCTION public.guard_delivery_dead_letter_review()$ddl$;

    EXECUTE $ddl$GRANT SELECT, INSERT, UPDATE ON public.delivery_outbox TO aurevia_app$ddl$;
    EXECUTE $ddl$GRANT SELECT, INSERT, UPDATE ON public.delivery_dead_letter TO aurevia_app$ddl$;
    EXECUTE $ddl$GRANT SELECT, INSERT ON public.delivery_receipt TO aurevia_app$ddl$;
    EXECUTE $ddl$GRANT SELECT, INSERT ON public.delivery_replay TO aurevia_app$ddl$;
    EXECUTE $ddl$GRANT SELECT, INSERT ON public.delivery_link TO aurevia_app$ddl$;
    EXECUTE $ddl$GRANT SELECT, INSERT ON public.delivery_audit TO aurevia_app$ddl$;
    EXECUTE $ddl$REVOKE DELETE, TRUNCATE ON public.delivery_outbox FROM aurevia_app$ddl$;
    EXECUTE $ddl$REVOKE DELETE, TRUNCATE ON public.delivery_dead_letter FROM aurevia_app$ddl$;
    EXECUTE $ddl$REVOKE UPDATE, DELETE, TRUNCATE ON public.delivery_receipt FROM aurevia_app$ddl$;
    EXECUTE $ddl$REVOKE UPDATE, DELETE, TRUNCATE ON public.delivery_replay FROM aurevia_app$ddl$;
    EXECUTE $ddl$REVOKE UPDATE, DELETE, TRUNCATE ON public.delivery_link FROM aurevia_app$ddl$;
    EXECUTE $ddl$REVOKE UPDATE, DELETE, TRUNCATE ON public.delivery_audit FROM aurevia_app$ddl$;

    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.set_delivery_actor_subject() FROM PUBLIC$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.guard_delivery_outbox_update() FROM PUBLIC$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.guard_delivery_dead_letter_review() FROM PUBLIC$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.set_delivery_actor_subject() TO aurevia_app$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.guard_delivery_outbox_update() TO aurevia_app$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.guard_delivery_dead_letter_review() TO aurevia_app$ddl$;
END $durable_event$;
