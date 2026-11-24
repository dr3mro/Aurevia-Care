-- Requirement IDs: ADM-01, ARC-02, OPS-02, OPS-06
-- Versioned tenant configuration foundation. Entries and approvals are immutable:
-- a change is a new version, never an UPDATE of an existing row. The effective
-- version is folded on read at now(), so an approved future-dated version does
-- not displace the current one early. Forced RLS from V2 applies to both tables.

CREATE TABLE public.config_entry (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    key             text NOT NULL,
    version         integer NOT NULL CHECK (version > 0),
    value           text NOT NULL,
    effective_from  timestamptz NOT NULL DEFAULT now(),
    created_by      uuid NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, key, version)
);

CREATE TABLE public.config_approval (
    organization_id uuid NOT NULL,
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    entry_id        uuid NOT NULL,
    approved_by     uuid NOT NULL,
    approved_at     timestamptz NOT NULL DEFAULT now(),
    note            text,
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, entry_id),
    FOREIGN KEY (organization_id, entry_id)
        REFERENCES public.config_entry(organization_id, id),
    FOREIGN KEY (organization_id, approved_by)
        REFERENCES public.user_identity(organization_id, id)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['config_entry', 'config_approval']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            $p$CREATE POLICY tenant_isolation ON public.%I
                 USING (organization_id = public.app_org())
                 WITH CHECK (organization_id = public.app_org())$p$, t);
        EXECUTE format($d$CREATE TRIGGER %I_no_update_delete
            BEFORE UPDATE OR DELETE ON public.%I
            FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
        EXECUTE format($d$CREATE TRIGGER %I_no_truncate
            BEFORE TRUNCATE ON public.%I
            FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
    END LOOP;
END $$;

-- Actor identity is trusted from the request GUC, never from the payload.
CREATE OR REPLACE FUNCTION public.set_config_created_by_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'configuration author must be an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.created_by := v_actor;
    RETURN NEW;
END $$;
CREATE TRIGGER config_entry_created_by_actor
    BEFORE INSERT ON public.config_entry
    FOR EACH ROW EXECUTE FUNCTION public.set_config_created_by_from_actor();

CREATE OR REPLACE FUNCTION public.set_config_approved_by_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'configuration approver must be an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.approved_by := v_actor;
    RETURN NEW;
END $$;
CREATE TRIGGER config_approval_approved_by_actor
    BEFORE INSERT ON public.config_approval
    FOR EACH ROW EXECUTE FUNCTION public.set_config_approved_by_from_actor();

-- Serialize version allocation per tenant and key; unset context fails closed.
CREATE OR REPLACE FUNCTION public.next_config_version(p_key text)
RETURNS integer LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_org uuid := public.app_org();
BEGIN
    IF v_org IS NULL THEN
        RAISE EXCEPTION 'tenant context required' USING ERRCODE = '42501';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(v_org::text || ':' || p_key, 0));
    RETURN coalesce(max(version), 0) + 1 FROM public.config_entry WHERE key = p_key;
END $$;

-- The effective version is the latest approved entry whose window has opened.
-- SECURITY INVOKER with RLS: an unset app.current_org yields no rows.
CREATE OR REPLACE FUNCTION public.effective_config(p_key text)
RETURNS TABLE (organization_id uuid, id uuid, key text, version integer, value text,
               effective_from timestamptz, created_by uuid, created_at timestamptz,
               approved_by uuid, approved_at timestamptz, note text)
LANGUAGE sql STABLE SET search_path = pg_catalog
AS $$
    SELECT e.organization_id, e.id, e.key, e.version, e.value, e.effective_from,
           e.created_by, e.created_at, a.approved_by, a.approved_at, a.note
      FROM public.config_entry e
      JOIN public.config_approval a
        ON a.organization_id = e.organization_id AND a.entry_id = e.id
     WHERE e.key = p_key AND e.effective_from <= now()
     ORDER BY e.effective_from DESC, e.created_at DESC, e.id DESC
     LIMIT 1
$$;

REVOKE ALL ON FUNCTION public.next_config_version(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.effective_config(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.next_config_version(text) TO aurevia_app;
GRANT EXECUTE ON FUNCTION public.effective_config(text) TO aurevia_app;

GRANT SELECT, INSERT ON public.config_entry, public.config_approval TO aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.config_entry, public.config_approval FROM aurevia_app;
