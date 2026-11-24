-- Requirement IDs: IAM-01, IAM-02, ARC-02, OPS-02
-- Append-only staff lifecycle. Grants (user_identity, membership, role_assignment)
-- are immutable rows; deactivation is a new 'revoked' membership event row.
-- Current authorization is folded on read at now(), so a deactivation is visible
-- on the very next protected request. No V1/V2/V3 object is altered: forced RLS
-- from V2 applies to every table below and the V3 audit chain is unchanged.

-- History is immutable for every role, including the migration owner.
CREATE OR REPLACE FUNCTION public.reject_immutable_mutation()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION '% is append-only (TRUNCATE)', TG_TABLE_NAME USING ERRCODE = '44000';
    END IF;
    RAISE EXCEPTION '% is append-only (%; organization_id=%)',
        TG_TABLE_NAME, TG_OP, OLD.organization_id USING ERRCODE = '44000';
END $$;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['user_identity', 'membership', 'role_assignment']
    LOOP
        EXECUTE format($d$CREATE TRIGGER %I_no_update_delete
            BEFORE UPDATE OR DELETE ON public.%I
            FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
        EXECUTE format($d$CREATE TRIGGER %I_no_truncate
            BEFORE TRUNCATE ON public.%I
            FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
    END LOOP;
END $$;

-- now() is transaction time; clock_timestamp() orders events inserted in one transaction.
CREATE OR REPLACE FUNCTION public.stamp_membership_created_at()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$ BEGIN
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END $$;
CREATE TRIGGER membership_stamp_created_at
    BEFORE INSERT ON public.membership
    FOR EACH ROW EXECUTE FUNCTION public.stamp_membership_created_at();

-- The grantor is always the authenticated actor of the active tenant, never caller input.
CREATE OR REPLACE FUNCTION public.set_granted_by_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'role grantor must be an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.granted_by := v_actor;
    RETURN NEW;
END $$;
CREATE TRIGGER role_assignment_granted_by_actor
    BEFORE INSERT ON public.role_assignment
    FOR EACH ROW EXECUTE FUNCTION public.set_granted_by_from_actor();

-- The current membership is the latest event whose effective window has opened.
-- SECURITY INVOKER with RLS: an unset app.current_org yields no rows and fails closed.
CREATE OR REPLACE FUNCTION public.current_membership()
RETURNS TABLE (organization_id uuid, membership_id uuid, user_id uuid,
               status text, valid_from timestamptz, valid_to timestamptz)
LANGUAGE sql STABLE SET search_path = pg_catalog
AS $$
    SELECT DISTINCT ON (m.organization_id, m.user_id)
           m.organization_id, m.id, m.user_id, m.status, m.valid_from, m.valid_to
      FROM public.membership m
     WHERE m.valid_from <= now()
     ORDER BY m.organization_id, m.user_id, m.valid_from DESC,
              m.created_at DESC, m.id DESC
$$;

CREATE OR REPLACE FUNCTION public.has_active_membership(p_subject text)
RETURNS boolean LANGUAGE sql STABLE SET search_path = pg_catalog
AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.current_membership() cm
        JOIN public.user_identity u
          ON u.organization_id = cm.organization_id AND u.id = cm.user_id
        WHERE u.subject = p_subject
          AND u.status = 'active'
          AND cm.status = 'active'
          AND (cm.valid_to IS NULL OR cm.valid_to > now())
    )
$$;

CREATE OR REPLACE FUNCTION public.active_roles(p_subject text)
RETURNS TABLE (role_key text) LANGUAGE sql STABLE SET search_path = pg_catalog
AS $$
    SELECT DISTINCT ra.role_key
      FROM public.role_assignment ra
      JOIN public.current_membership() cm
        ON cm.organization_id = ra.organization_id AND cm.membership_id = ra.membership_id
      JOIN public.user_identity u
        ON u.organization_id = cm.organization_id AND u.id = cm.user_id
     WHERE u.subject = p_subject
       AND u.status = 'active'
       AND cm.status = 'active'
       AND (cm.valid_to IS NULL OR cm.valid_to > now())
       AND ra.valid_from <= now()
       AND (ra.valid_to IS NULL OR ra.valid_to > now())
$$;

REVOKE ALL ON FUNCTION public.current_membership() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.has_active_membership(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.active_roles(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.current_membership() TO orevia_app;
GRANT EXECUTE ON FUNCTION public.has_active_membership(text) TO orevia_app;
GRANT EXECUTE ON FUNCTION public.active_roles(text) TO orevia_app;

-- Invite/deactivate need INSERT; UPDATE/DELETE/TRUNCATE stay impossible.
REVOKE UPDATE, DELETE, TRUNCATE ON public.user_identity, public.membership,
       public.role_assignment FROM orevia_app;
GRANT INSERT ON public.user_identity, public.membership, public.role_assignment
    TO orevia_app;
