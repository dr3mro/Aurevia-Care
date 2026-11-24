-- Requirement IDs: LAB-01, ARC-01, ARC-02, ARC-03, OPS-02
-- Laboratory requisition accession foundation (S-G2-08).
--
-- Accession numbers are unique within (organization, issuer), so two tenants and
-- two issuers may reuse the same local number. Every specimen carries a bounded
-- reference to its source order because the order aggregate belongs to a separate
-- exchange story; this reference grants no general chart access.
--
-- Forced RLS from V2 applies to both tables and every foreign key carries the
-- organization. The mutable accession row is never deleted: collection, receipt,
-- rejection and recollection are versioned state transitions guarded by the
-- database. The history ledger is append-only for every role. The author and
-- history actor are derived from the transaction-local subject, never supplied
-- by the caller.
--
-- Dependencies: patient_record (V7), user_identity (V1), app_org() (V2),
-- reject_immutable_mutation() (V4), has_active_membership/active_roles (V4).

CREATE TABLE public.lab_accession (
    organization_id        uuid NOT NULL REFERENCES public.organization(organization_id),
    id                     uuid NOT NULL DEFAULT gen_random_uuid(),
    version                integer NOT NULL DEFAULT 1 CHECK (version > 0),
    accession_no           text NOT NULL,
    accession_issuer       text NOT NULL,
    patient_id             uuid NOT NULL,
    source_order_ref       text NOT NULL,
    state                  text NOT NULL
        CHECK (state IN ('accessioned', 'collected', 'received', 'rejected', 'recollected')),
    specimen_type          text,
    site_code              text,
    collector              text,
    collected_at           timestamptz,
    received_at            timestamptz,
    quality                text,
    rejection_reason       text,
    rejection_notification text,
    replaces_accession_id  uuid,
    created_by             uuid NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, accession_issuer, accession_no),
    CHECK (state <> 'collected' OR
           (specimen_type IS NOT NULL AND site_code IS NOT NULL AND
            collector IS NOT NULL AND collected_at IS NOT NULL)),
    CHECK (state <> 'received' OR (received_at IS NOT NULL AND quality IS NOT NULL)),
    CHECK (state <> 'rejected' OR
           (rejection_reason IS NOT NULL AND rejection_notification IS NOT NULL)),
    CHECK (state <> 'recollected' OR rejection_reason IS NOT NULL),
    FOREIGN KEY (organization_id, patient_id)
        REFERENCES public.patient_record(organization_id, id),
    FOREIGN KEY (organization_id, replaces_accession_id)
        REFERENCES public.lab_accession(organization_id, id),
    FOREIGN KEY (organization_id, created_by)
        REFERENCES public.user_identity(organization_id, id)
);

CREATE INDEX lab_accession_patient_idx
    ON public.lab_accession (organization_id, patient_id, created_at DESC);
CREATE INDEX lab_accession_recollection_idx
    ON public.lab_accession (organization_id, replaces_accession_id)
    WHERE replaces_accession_id IS NOT NULL;

CREATE TABLE public.lab_accession_event (
    organization_id     uuid NOT NULL,
    id                  uuid NOT NULL DEFAULT gen_random_uuid(),
    accession_id        uuid NOT NULL,
    accession_version   integer NOT NULL,
    action              text NOT NULL
        CHECK (action IN ('accessioned', 'collected', 'received', 'rejected', 'recollected')),
    from_state          text,
    to_state            text NOT NULL,
    reason              text,
    matched_patient_id  uuid,
    actor_id            uuid NOT NULL,
    occurred_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, accession_id, accession_version),
    FOREIGN KEY (organization_id, accession_id)
        REFERENCES public.lab_accession(organization_id, id),
    FOREIGN KEY (organization_id, actor_id)
        REFERENCES public.user_identity(organization_id, id),
    FOREIGN KEY (organization_id, matched_patient_id)
        REFERENCES public.patient_record(organization_id, id)
);

CREATE INDEX lab_accession_event_history_idx
    ON public.lab_accession_event (organization_id, accession_id, occurred_at, id);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['lab_accession', 'lab_accession_event']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            $p$CREATE POLICY tenant_isolation ON public.%I
                 USING (organization_id = public.app_org())
                 WITH CHECK (organization_id = public.app_org())$p$, t);
    END LOOP;
END $$;

DO $$
BEGIN
    EXECUTE $d$CREATE TRIGGER lab_accession_event_no_update_delete
        BEFORE UPDATE OR DELETE ON public.lab_accession_event
        FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$d$;
    EXECUTE $d$CREATE TRIGGER lab_accession_event_no_truncate
        BEFORE TRUNCATE ON public.lab_accession_event
        FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$d$;
    EXECUTE $d$CREATE TRIGGER lab_accession_no_delete
        BEFORE DELETE ON public.lab_accession
        FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$d$;
    EXECUTE $d$CREATE TRIGGER lab_accession_no_truncate
        BEFORE TRUNCATE ON public.lab_accession
        FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$d$;
END $$;

-- The author of an accession and the actor of a history event always come from
-- the authenticated subject of the active tenant.
CREATE OR REPLACE FUNCTION public.set_lab_accession_created_by_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'accession author must be an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.created_by := v_actor;
    RETURN NEW;
END $$;
CREATE TRIGGER lab_accession_created_by_actor
    BEFORE INSERT ON public.lab_accession
    FOR EACH ROW EXECUTE FUNCTION public.set_lab_accession_created_by_from_actor();

CREATE OR REPLACE FUNCTION public.set_lab_accession_event_actor_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'accession history actor must be an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.actor_id := v_actor;
    RETURN NEW;
END $$;
CREATE TRIGGER lab_accession_event_actor
    BEFORE INSERT ON public.lab_accession_event
    FOR EACH ROW EXECUTE FUNCTION public.set_lab_accession_event_actor_from_actor();

-- Ownership and identity columns are pinned; the version advances by exactly one
-- and only the LAB-01 edges are accepted. This mirrors the application state
-- machine so a stray UPDATE cannot re-point or walk back a specimen.
CREATE OR REPLACE FUNCTION public.guard_lab_accession_update()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.id <> OLD.id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.accession_no <> OLD.accession_no
       OR NEW.accession_issuer <> OLD.accession_issuer
       OR NEW.patient_id <> OLD.patient_id
       OR NEW.source_order_ref <> OLD.source_order_ref
       OR NEW.replaces_accession_id IS DISTINCT FROM OLD.replaces_accession_id
       OR NEW.created_by <> OLD.created_by
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'accession ownership columns are immutable'
            USING ERRCODE = '42501';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'accession version must advance by exactly one'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.state <> OLD.state AND NOT (
            (OLD.state = 'accessioned' AND NEW.state = 'collected')
         OR (OLD.state = 'collected' AND NEW.state IN ('received', 'rejected'))
         OR (OLD.state = 'received' AND NEW.state = 'rejected')
         OR (OLD.state = 'rejected' AND NEW.state = 'recollected')) THEN
        RAISE EXCEPTION 'illegal accession transition % -> %', OLD.state, NEW.state
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER lab_accession_update_guard
    BEFORE UPDATE ON public.lab_accession
    FOR EACH ROW EXECUTE FUNCTION public.guard_lab_accession_update();

GRANT SELECT, INSERT, UPDATE ON public.lab_accession TO aurevia_app;
REVOKE DELETE, TRUNCATE ON public.lab_accession FROM aurevia_app;
GRANT SELECT, INSERT ON public.lab_accession_event TO aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.lab_accession_event FROM aurevia_app;

REVOKE ALL ON FUNCTION public.set_lab_accession_created_by_from_actor() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.set_lab_accession_event_actor_from_actor() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_lab_accession_update() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.set_lab_accession_created_by_from_actor() TO aurevia_app;
GRANT EXECUTE ON FUNCTION public.set_lab_accession_event_actor_from_actor() TO aurevia_app;
GRANT EXECUTE ON FUNCTION public.guard_lab_accession_update() TO aurevia_app;
