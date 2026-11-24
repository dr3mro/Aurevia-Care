-- Requirement IDs: IAM-02, ARC-01, ARC-02, OPS-02
-- Change: expand
-- Single-use, expiring staff invitations. Only the SHA-256 hash of the raw token is
-- stored; the raw token is returned once at issue time. Acceptance is one atomic
-- database function that consumes the token, creates the membership and records
-- the inviter as grantor with an effective date. History tables from V4 stay
-- append-only, and forced RLS confines every statement to the active tenant.

CREATE TABLE public.staff_invitation (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    subject         text NOT NULL,
    display_name    text NOT NULL,
    facility_id     uuid NOT NULL,
    roles           text[] NOT NULL,
    token_hash      text NOT NULL,
    invited_by      text NOT NULL,
    expires_at      timestamptz NOT NULL,
    used_at         timestamptz,
    revoked_at      timestamptz,
    created_by      text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, token_hash),
    CHECK (expires_at > created_at),
    CHECK (cardinality(roles) > 0
           AND array_position(roles, NULL) IS NULL
           AND roles <@ ARRAY['tenant_admin', 'reception', 'privacy_auditor']::text[]),
    FOREIGN KEY (organization_id, facility_id)
        REFERENCES public.facility(organization_id, id)
);

DO $$
BEGIN
    EXECUTE 'ALTER TABLE public.staff_invitation ENABLE ROW LEVEL SECURITY';
    EXECUTE 'ALTER TABLE public.staff_invitation FORCE ROW LEVEL SECURITY';
    EXECUTE $p$CREATE POLICY tenant_isolation ON public.staff_invitation
         USING (organization_id = public.app_org())
         WITH CHECK (organization_id = public.app_org())$p$;
END $$;

-- Only a tenant administrator may issue or revoke an invitation, and the actor
-- provenance is derived from the authenticated context, never from caller input.
CREATE OR REPLACE FUNCTION public.require_staff_invitation_admin()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor text;
BEGIN
    v_actor := NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.active_roles(v_actor) WHERE role_key = 'tenant_admin') THEN
        RAISE EXCEPTION 'staff invitation requires an active tenant administrator'
            USING ERRCODE = '42501';
    END IF;
    NEW.invited_by := v_actor;
    NEW.created_by := v_actor;
    RETURN NEW;
END $$;

CREATE TRIGGER staff_invitation_require_admin
    BEFORE INSERT ON public.staff_invitation
    FOR EACH ROW EXECUTE FUNCTION public.require_staff_invitation_admin();

-- Distinct role keys are enforced by a trigger because PostgreSQL forbids a
-- subquery inside a CHECK constraint.
CREATE OR REPLACE FUNCTION public.require_distinct_invitation_roles()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    IF cardinality(NEW.roles) <> cardinality(ARRAY(SELECT DISTINCT unnest(NEW.roles))) THEN
        RAISE EXCEPTION 'staff invitation roles must be distinct'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER staff_invitation_distinct_roles
    BEFORE INSERT ON public.staff_invitation
    FOR EACH ROW EXECUTE FUNCTION public.require_distinct_invitation_roles();

CREATE OR REPLACE FUNCTION public.revoke_staff_invitation(p_id uuid)
RETURNS boolean LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor text; v_count integer;
BEGIN
    v_actor := NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.active_roles(v_actor) WHERE role_key = 'tenant_admin') THEN
        RAISE EXCEPTION 'staff invitation revocation requires an active tenant administrator'
            USING ERRCODE = '42501';
    END IF;
    UPDATE public.staff_invitation
       SET revoked_at = now()
     WHERE id = p_id AND revoked_at IS NULL AND used_at IS NULL AND expires_at > now();
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count = 1;
END $$;

-- The grantor of a role is normally the authenticated actor. Acceptance is the
-- one command where the invitee is the actor but the inviter is the grantor, so
-- a dedicated transaction-local GUC carries the grantor without contaminating
-- the actor used by the V3 audit trigger.
CREATE OR REPLACE FUNCTION public.set_granted_by_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid; v_subject text;
BEGIN
    v_subject := NULLIF(current_setting('app.grantor_subject', true), '');
    IF v_subject IS NULL THEN
        v_subject := NULLIF(current_setting('app.actor', true), '');
    END IF;
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id AND subject = v_subject;
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'role grantor must be an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.granted_by := v_actor;
    RETURN NEW;
END $$;

-- Accept is a single guarded transaction: consume the token, create the identity
-- and membership, then append one role assignment per bounded role. Any failure
-- rolls the whole command back, including the consumption, so acceptance is
-- atomic. Every unusable token (unknown, other tenant, expired, used, revoked,
-- already-staff) returns the same NULL outcome: no existence oracle is exposed.
CREATE OR REPLACE FUNCTION public.accept_staff_invitation(p_token text)
RETURNS text LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE
    v_inv      public.staff_invitation%ROWTYPE;
    v_user_id  uuid;
    v_membership_id uuid;
    v_role     text;
    v_previous_grantor text;
BEGIN
    -- RLS confines this lookup to the active organization; a token issued by a
    -- different tenant is indistinguishable from an unknown token.
    SELECT * INTO v_inv
      FROM public.staff_invitation
     WHERE token_hash = encode(sha256(convert_to(p_token, 'UTF8')), 'hex');
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    IF EXISTS (SELECT 1 FROM public.user_identity
                WHERE organization_id = v_inv.organization_id AND subject = v_inv.subject) THEN
        RETURN NULL;
    END IF;

    -- Consumption is a single conditional UPDATE: only one concurrent caller can
    -- flip used_at, and the runtime role needs no table-wide UPDATE privilege.
    UPDATE public.staff_invitation
       SET used_at = now()
     WHERE id = v_inv.id
       AND used_at IS NULL
       AND revoked_at IS NULL
       AND expires_at > now();
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    INSERT INTO public.user_identity (organization_id, subject, display_name)
    VALUES (v_inv.organization_id, v_inv.subject, v_inv.display_name)
    RETURNING id INTO v_user_id;

    INSERT INTO public.membership (organization_id, user_id, status)
    VALUES (v_inv.organization_id, v_user_id, 'active')
    RETURNING id INTO v_membership_id;

    v_previous_grantor := current_setting('app.grantor_subject', true);
    PERFORM set_config('app.grantor_subject', v_inv.invited_by, true);
    BEGIN
        FOREACH v_role IN ARRAY v_inv.roles
        LOOP
            INSERT INTO public.role_assignment
                (organization_id, membership_id, role_key, granted_by, valid_from)
            VALUES (v_inv.organization_id, v_membership_id, v_role, NULL, now());
        END LOOP;
    EXCEPTION WHEN OTHERS THEN
        PERFORM set_config('app.grantor_subject', coalesce(v_previous_grantor, ''), true);
        RAISE;
    END;
    PERFORM set_config('app.grantor_subject', coalesce(v_previous_grantor, ''), true);
    RETURN v_inv.subject;
END $$;

REVOKE ALL ON FUNCTION public.require_staff_invitation_admin() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.require_distinct_invitation_roles() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.revoke_staff_invitation(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.accept_staff_invitation(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.revoke_staff_invitation(uuid) TO orevia_app;
GRANT EXECUTE ON FUNCTION public.accept_staff_invitation(text) TO orevia_app;

-- The runtime role may only advance the two lifecycle timestamps; it cannot
-- rewrite the token, roles, subject, expiry or provenance of an invitation.
REVOKE ALL ON public.staff_invitation FROM orevia_app;
GRANT SELECT, INSERT ON public.staff_invitation TO orevia_app;
-- Column-scoped UPDATE must be granted after the table-level REVOKE: a later
-- REVOKE UPDATE would also withdraw column privileges in PostgreSQL.
GRANT UPDATE (used_at, revoked_at) ON public.staff_invitation TO orevia_app;
