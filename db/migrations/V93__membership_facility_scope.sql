-- Requirement IDs: IAM-01
-- Facility scope on membership (IAM-01 clause 4). A tenant-owned, forced-RLS
-- association records the facilities an accepted invitation assigned to the new
-- membership. Authorization reads that association to narrow facility-aware
-- access inside the active organization.
--
-- Scope: this is a new table plus a forward replacement of the invitation
-- acceptance function so it propagates staff_invitation.facility_id. No earlier
-- migration is edited and no existing row is updated or deleted (expand only).
-- Members with no recorded assignment keep their previous organization-wide
-- scope, so facility scope further narrows access without breaking legacy rows.

CREATE TABLE public.membership_facility (
    organization_id uuid NOT NULL,
    membership_id   uuid NOT NULL,
    facility_id     uuid NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, membership_id, facility_id),
    FOREIGN KEY (organization_id, membership_id)
        REFERENCES public.membership (organization_id, id),
    FOREIGN KEY (organization_id, facility_id)
        REFERENCES public.facility (organization_id, id)
);

-- New tenant tables are not covered by V2's static list: enable and force RLS
-- here, with an explicit tenant policy and explicit runtime grants.
ALTER TABLE public.membership_facility ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.membership_facility FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.membership_facility
    USING (organization_id = public.app_org())
    WITH CHECK (organization_id = public.app_org());

REVOKE ALL ON public.membership_facility FROM PUBLIC;
GRANT SELECT, INSERT ON public.membership_facility TO aurevia_app;

-- The association is append-only for every role, including the migration owner.
CREATE TRIGGER membership_facility_no_update_delete
    BEFORE UPDATE OR DELETE ON public.membership_facility
    FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation();
CREATE TRIGGER membership_facility_no_truncate
    BEFORE TRUNCATE ON public.membership_facility
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation();

-- Forward replacement of V70's acceptance: everything is identical except the
-- new INSERT that carries the invitation's facility onto the membership. The
-- token consumption, identity, membership, role grants and audit stay in the
-- same guarded transaction, so a failed scope write rolls the acceptance back.
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

    INSERT INTO public.membership_facility (organization_id, membership_id, facility_id)
    VALUES (v_inv.organization_id, v_membership_id, v_inv.facility_id);

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

REVOKE ALL ON FUNCTION public.accept_staff_invitation(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.accept_staff_invitation(text) TO aurevia_app;

-- A single authorization primitive answers one question: may the subject touch
-- this facility of the active organization? It fails closed when a subject has
-- assignments but not this one, stays unchanged for subjects with no assignment
-- (legacy members), and exempts the organization-wide oversight roles.
CREATE OR REPLACE FUNCTION public.is_facility_out_of_scope(p_subject text, p_facility_code text)
RETURNS boolean LANGUAGE sql STABLE SET search_path = pg_catalog
AS $$
    WITH assigned AS (
        SELECT f.code
          FROM public.current_membership() cm
          JOIN public.user_identity u
            ON u.organization_id = cm.organization_id AND u.id = cm.user_id
          JOIN public.membership_facility mf
            ON mf.organization_id = cm.organization_id AND mf.membership_id = cm.membership_id
          JOIN public.facility f
            ON f.organization_id = mf.organization_id AND f.id = mf.facility_id
         WHERE u.subject = p_subject
           AND cm.status = 'active'
           AND (cm.valid_to IS NULL OR cm.valid_to > now())
    )
    SELECT EXISTS (SELECT 1 FROM assigned)
       AND NOT EXISTS (SELECT 1 FROM assigned WHERE code = p_facility_code)
       AND NOT EXISTS (SELECT 1 FROM public.active_roles(p_subject) r
                        WHERE r.role_key IN ('tenant_admin', 'privacy_auditor'))
$$;

REVOKE ALL ON FUNCTION public.is_facility_out_of_scope(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.is_facility_out_of_scope(text, text) TO aurevia_app;
