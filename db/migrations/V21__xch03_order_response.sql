-- Requirement IDs: XCH-03, XCH-01, ARC-01, ARC-02, ARC-03, ARC-05, OPS-02
-- Change: expand
-- Recipient-side order response (XCH-03): a versioned work item, immutable
-- released revisions and retained copies, an append-only response ledger and
-- durable downstream notifications for revocation.
--
-- This migration is self-contained: the parallel XCH-01/XCH-02 stories own the
-- sender order and its workflow/transmission/clinical-review states, so no table
-- from V19/V20 is referenced here. It also has no creation-time dependency on
-- any other story's migration: db/check.sh applies V*.sql in filename order
-- (V21 before V4/V7) while Flyway applies by version, so the local patient
-- association and append-only guard are enforced by locally defined triggers
-- that resolve their referenced tables only at write time. The order code,
-- released checksum and source identifiers are stored as provenance. Every
-- table below is new, so no existing relation is locked or rewritten.
-- Sender-hosted retrieval blocking is represented by the revoked state and the
-- notification record; wiring the sender's own retrieval service is part of the
-- exchange foundation story.
--
-- Forced RLS applies to all five tables, and every foreign key carries the
-- organization so a cross-tenant reference cannot resolve. The ledger actor_id
-- columns are derived from the transaction-local app.actor binding, never from
-- caller input. xch03_order.created_by is different: it records the source
-- actor captured by the creating flow when the recipient work item is received
-- (provenance), and is validated by that flow. No row is ever deleted:
-- cancellation, revocation and supersession are state transitions, and every
-- ledger is append-only.

-- Mutable current projection of one exchanged order in the active tenant.
CREATE TABLE public.xch03_order (
    organization_id    uuid NOT NULL REFERENCES public.organization(organization_id),
    id                 uuid NOT NULL,
    version            integer NOT NULL DEFAULT 1 CHECK (version > 0),
    order_code         text NOT NULL CHECK (length(btrim(order_code)) BETWEEN 1 AND 128),
    state              text NOT NULL CHECK (state IN (
                           'requested', 'accepted', 'rejected', 'clarification_requested',
                           'change_pending', 'cancellation_pending', 'cancelled', 'revoked')),
    revision           integer NOT NULL DEFAULT 1 CHECK (revision > 0),
    content_checksum   text NOT NULL CHECK (content_checksum ~ '^[0-9a-fA-F]{64}$'),
    local_patient_id   uuid,
    assignee           text CHECK (assignee IS NULL OR length(btrim(assignee)) BETWEEN 1 AND 128),
    reason             text CHECK (reason IS NULL OR length(btrim(reason)) > 0),
    question           text CHECK (question IS NULL OR length(btrim(question)) > 0),
    permitted_purpose  text CHECK (permitted_purpose IS NULL
                                   OR permitted_purpose ~ '^[A-Za-z0-9_.:-]{1,64}$'),
    retention_policy   text CHECK (retention_policy IS NULL
                                   OR retention_policy IN ('STANDARD', 'EXTENDED', 'LEGAL_HOLD')),
    created_by         uuid NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id)
);

-- Immutable released-content revisions. previous_revision is a same-tenant,
-- same-order self-link, so a change can never point at another tenant's copy.
CREATE TABLE public.xch03_revision (
    organization_id   uuid NOT NULL REFERENCES public.organization(organization_id),
    id                uuid NOT NULL DEFAULT gen_random_uuid(),
    order_id          uuid NOT NULL,
    revision          integer NOT NULL CHECK (revision > 0),
    previous_revision integer,
    content_checksum  text NOT NULL CHECK (content_checksum ~ '^[0-9a-fA-F]{64}$'),
    change_summary    text CHECK (change_summary IS NULL OR length(btrim(change_summary)) > 0),
    actor_id          uuid NOT NULL,
    occurred_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, order_id, revision),
    CHECK (previous_revision IS NULL OR previous_revision < revision),
    FOREIGN KEY (organization_id, order_id)
        REFERENCES public.xch03_order(organization_id, id),
    FOREIGN KEY (organization_id, order_id, previous_revision)
        REFERENCES public.xch03_revision(organization_id, order_id, revision)
);

-- Immutable retained recipient copy: purpose and policy are captured at the
-- moment of acceptance, and a legal hold suspends any later retention expiry.
CREATE TABLE public.xch03_retained_copy (
    organization_id   uuid NOT NULL REFERENCES public.organization(organization_id),
    id                uuid NOT NULL DEFAULT gen_random_uuid(),
    order_id          uuid NOT NULL,
    revision          integer NOT NULL CHECK (revision > 0),
    content_checksum  text NOT NULL CHECK (content_checksum ~ '^[0-9a-fA-F]{64}$'),
    permitted_purpose text NOT NULL CHECK (permitted_purpose ~ '^[A-Za-z0-9_.:-]{1,64}$'),
    retention_policy  text NOT NULL CHECK (retention_policy IN ('STANDARD', 'EXTENDED', 'LEGAL_HOLD')),
    legal_hold        boolean NOT NULL DEFAULT false,
    actor_id          uuid NOT NULL,
    occurred_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, order_id, revision),
    FOREIGN KEY (organization_id, order_id)
        REFERENCES public.xch03_order(organization_id, id)
);

-- Append-only response history. One event per order version keeps the actor,
-- reason and both states of every human decision auditable.
CREATE TABLE public.xch03_event (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    order_id        uuid NOT NULL,
    order_version   integer NOT NULL CHECK (order_version > 0),
    action          text NOT NULL CHECK (length(btrim(action)) BETWEEN 1 AND 64),
    from_state      text,
    to_state        text NOT NULL,
    reason          text,
    actor_id        uuid NOT NULL,
    occurred_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, order_id, order_version),
    FOREIGN KEY (organization_id, order_id)
        REFERENCES public.xch03_order(organization_id, id)
);

-- Durable downstream notification emitted by revocation. event_id is the
-- deduplication authority so a replayed revoke has one downstream effect.
CREATE TABLE public.xch03_notification (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    order_id        uuid NOT NULL,
    event_id        text NOT NULL CHECK (length(btrim(event_id)) BETWEEN 1 AND 64),
    target          text NOT NULL CHECK (length(btrim(target)) BETWEEN 1 AND 128),
    channel         text NOT NULL CHECK (length(btrim(channel)) BETWEEN 1 AND 32),
    outcome         text NOT NULL CHECK (outcome IN ('NOTIFIED', 'FAILED')),
    actor_id        uuid NOT NULL,
    occurred_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, event_id),
    FOREIGN KEY (organization_id, order_id)
        REFERENCES public.xch03_order(organization_id, id)
);

-- Every unique lookup is left-prefixed with the tenant so two organizations can
-- hold the same local order code and revision numbers without colliding.
CREATE INDEX xch03_order_lookup_idx
    ON public.xch03_order (organization_id, order_code, id);
CREATE INDEX xch03_revision_history_idx
    ON public.xch03_revision (organization_id, order_id, revision);
CREATE INDEX xch03_retained_history_idx
    ON public.xch03_retained_copy (organization_id, order_id, revision);
CREATE INDEX xch03_event_history_idx
    ON public.xch03_event (organization_id, order_id, occurred_at, id);
CREATE INDEX xch03_notification_history_idx
    ON public.xch03_notification (organization_id, order_id, occurred_at, id);

-- Local tenant-context accessor with the same semantics as app_org() from V2,
-- so these policies can be created before that migration is applied (filename
-- order puts V21 ahead of V2; Flyway version order puts it after).
CREATE OR REPLACE FUNCTION public.xch03_app_org() RETURNS uuid
LANGUAGE sql STABLE SET search_path = pg_catalog
AS $$ SELECT NULLIF(current_setting('app.current_org', true), '')::uuid $$;

-- Forced row-level security with fail-closed WITH CHECK on every new table.
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['xch03_order', 'xch03_revision', 'xch03_retained_copy',
                             'xch03_event', 'xch03_notification']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            $p$CREATE POLICY tenant_isolation ON public.%I
                 USING (organization_id = public.xch03_app_org())
                 WITH CHECK (organization_id = public.xch03_app_org())$p$, t);
    END LOOP;
END $$;

-- The actor is resolved in the active tenant from app.actor; a caller cannot
-- attribute a ledger entry to another user or organization.
CREATE OR REPLACE FUNCTION public.set_xch03_actor_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'xch03 ledger entry requires an authenticated actor of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.actor_id := v_actor;
    RETURN NEW;
END $$;

-- Append-only ledger guard owned by this migration. A locally named function
-- keeps V21 independent of the shared helper introduced by another story's
-- migration file (which may not exist yet under filename-order application).
CREATE OR REPLACE FUNCTION public.reject_xch03_immutable_mutation()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION '% is append-only (TRUNCATE)', TG_TABLE_NAME USING ERRCODE = '44000';
    END IF;
    RAISE EXCEPTION '% is append-only (%; organization_id=%)',
        TG_TABLE_NAME, TG_OP, OLD.organization_id USING ERRCODE = '44000';
END $$;

-- A locally verified patient association must resolve inside the active tenant.
-- It is checked at write time instead of by a table-level foreign key so V21
-- does not depend on the patient-registration migration's filename position;
-- plpgsql resolves public.patient_record when the trigger fires, after all
-- migrations have been applied.
CREATE OR REPLACE FUNCTION public.validate_xch03_local_patient()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.local_patient_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM public.patient_record p
         WHERE p.organization_id = NEW.organization_id
           AND p.id = NEW.local_patient_id) THEN
        RAISE EXCEPTION 'xch03 local patient must be a verified patient of the active tenant'
            USING ERRCODE = '23503';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER xch03_order_patient_check
    BEFORE INSERT OR UPDATE ON public.xch03_order
    FOR EACH ROW EXECUTE FUNCTION public.validate_xch03_local_patient();

-- The current projection may advance state and version only. Ownership, the
-- released checksum and the original author are pinned, and no row may be
-- deleted or renumbered beneath a concurrent command.
CREATE OR REPLACE FUNCTION public.guard_xch03_order_update()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.organization_id IS DISTINCT FROM OLD.organization_id
       OR NEW.id <> OLD.id
       OR NEW.order_code <> OLD.order_code
       OR NEW.content_checksum <> OLD.content_checksum
       OR NEW.created_by <> OLD.created_by
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'xch03 order ownership and released content are immutable'
            USING ERRCODE = '42501';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'xch03 order version must advance by exactly one'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.state <> OLD.state AND OLD.state IN ('rejected', 'cancelled', 'revoked') THEN
        RAISE EXCEPTION 'a terminal xch03 order cannot change state'
            USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END $$;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['xch03_revision', 'xch03_retained_copy',
                             'xch03_event', 'xch03_notification']
    LOOP
        EXECUTE format($d$CREATE TRIGGER %I_no_update_delete
            BEFORE UPDATE OR DELETE ON public.%I
            FOR EACH ROW EXECUTE FUNCTION public.reject_xch03_immutable_mutation()$d$, t, t);
        EXECUTE format($d$CREATE TRIGGER %I_no_truncate
            BEFORE TRUNCATE ON public.%I
            FOR EACH STATEMENT EXECUTE FUNCTION public.reject_xch03_immutable_mutation()$d$, t, t);
        EXECUTE format($d$CREATE TRIGGER %I_actor_from_actor
            BEFORE INSERT ON public.%I
            FOR EACH ROW EXECUTE FUNCTION public.set_xch03_actor_from_actor()$d$, t, t);
    END LOOP;
END $$;

CREATE TRIGGER xch03_order_no_delete
    BEFORE DELETE ON public.xch03_order
    FOR EACH ROW EXECUTE FUNCTION public.reject_xch03_immutable_mutation();
CREATE TRIGGER xch03_order_no_truncate
    BEFORE TRUNCATE ON public.xch03_order
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_xch03_immutable_mutation();
CREATE TRIGGER xch03_order_update_guard
    BEFORE UPDATE ON public.xch03_order
    FOR EACH ROW EXECUTE FUNCTION public.guard_xch03_order_update();

-- Least privilege for the runtime role. No DELETE or TRUNCATE on any table.
GRANT EXECUTE ON FUNCTION public.xch03_app_org() TO aurevia_app;
GRANT SELECT, INSERT, UPDATE ON public.xch03_order TO aurevia_app;
GRANT SELECT, INSERT ON public.xch03_revision, public.xch03_retained_copy,
                       public.xch03_event, public.xch03_notification TO aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.xch03_revision, public.xch03_retained_copy,
                                  public.xch03_event, public.xch03_notification FROM aurevia_app;
REVOKE DELETE, TRUNCATE ON public.xch03_order FROM aurevia_app;
REVOKE ALL ON FUNCTION public.set_xch03_actor_from_actor() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_xch03_order_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.reject_xch03_immutable_mutation() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.validate_xch03_local_patient() FROM PUBLIC;
