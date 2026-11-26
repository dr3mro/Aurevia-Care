-- Requirement IDs: XCH-04, ARC-02, OPS-02
-- Change: expand
--
-- Purpose: durable, append-only resolution facts for the XCH-04 reconciliation queue. V22 keeps
--   xch04_reconciliation insert-only, so a decision is stored in this separate fact table: its presence
--   closes the entry on the read side without rewriting the diversion ledger.
-- Invariant: one decision per (organization_id, entry_id). The author and both timestamps are
--   server-owned, the decision content is immutable, and every read and write is tenant-scoped.
-- RLS: ENABLE + FORCE ROW LEVEL SECURITY and a tenant_isolation policy are declared here because V2's
--   static table list does not cover tables introduced by later migrations.
-- Lock/volume: one new table with two composite foreign keys and three triggers; no backfill, no
--   rewrite of V22 rows and no lock on an existing large table.
-- Recovery / forward-fix: additive and idempotent on a fresh database; no data repair is needed. No
--   capability is removed, so no contract forward-fix is required.

CREATE TABLE public.reconciliation_resolution (
    organization_id  uuid NOT NULL REFERENCES public.organization(organization_id),
    id               uuid NOT NULL DEFAULT gen_random_uuid(),
    entry_id         uuid NOT NULL,
    action           text NOT NULL CHECK (action IN ('match', 'reject', 'reassign')),
    reason           text NOT NULL CHECK (length(btrim(reason)) > 0 AND length(reason) <= 128),
    assigned_owner   text CHECK (assigned_owner IS NULL OR length(btrim(assigned_owner)) > 0)
                          CHECK (length(assigned_owner) <= 128),
    resolved_at      timestamptz NOT NULL DEFAULT now(),
    created_by       uuid NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    -- A retry or a second worker cannot append a second decision for the same queue entry.
    UNIQUE (organization_id, entry_id),
    CHECK ((action = 'reassign') = (assigned_owner IS NOT NULL)),
    FOREIGN KEY (organization_id, entry_id)
        REFERENCES public.xch04_reconciliation (organization_id, id),
    FOREIGN KEY (organization_id, created_by)
        REFERENCES public.user_identity (organization_id, id)
);

ALTER TABLE public.reconciliation_resolution ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.reconciliation_resolution FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.reconciliation_resolution
    USING (organization_id = public.app_org())
    WITH CHECK (organization_id = public.app_org());

-- The identity of the author is derived from the transaction-local actor in the active tenant.
-- A caller-supplied author, timestamp or forged owner can never backdate or reattribute a decision.
CREATE FUNCTION public.set_reconciliation_resolution_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'resolution author must be an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.created_by := v_actor;
    NEW.resolved_at := now();
    NEW.created_at := now();
    RETURN NEW;
END $$;

CREATE TRIGGER reconciliation_resolution_from_actor
    BEFORE INSERT ON public.reconciliation_resolution
    FOR EACH ROW EXECUTE FUNCTION public.set_reconciliation_resolution_from_actor();
CREATE TRIGGER reconciliation_resolution_no_update_delete
    BEFORE UPDATE OR DELETE ON public.reconciliation_resolution
    FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation();
CREATE TRIGGER reconciliation_resolution_no_truncate
    BEFORE TRUNCATE ON public.reconciliation_resolution
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation();

GRANT SELECT, INSERT ON public.reconciliation_resolution TO aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.reconciliation_resolution FROM aurevia_app;
REVOKE ALL ON FUNCTION public.set_reconciliation_resolution_from_actor() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.set_reconciliation_resolution_from_actor() TO aurevia_app;
