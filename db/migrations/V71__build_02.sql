-- Requirement IDs: IAM-02, ARC-02, OPS-02
-- Change: expand
-- Append-only role-change ledger (IAM-02). Every role grant, replacement and
-- revocation is captured as a separate immutable row carrying the acting user
-- (who), the database time (when) and the effective-date window (from/to).
--
-- Population is database-side: V4 owns the authoritative membership and
-- role_assignment inserts, so AFTER INSERT triggers here record each event in the
-- same transaction. No application path can update or delete history: UPDATE,
-- DELETE and TRUNCATE are rejected by V4's immutable-mutation trigger for every
-- role including the migration owner, and the runtime role has no such grants.
--
-- Forced RLS from V2 does not automatically cover new tables: this table enables
-- and forces its own policy using the transaction-local app.current_org GUC.
-- No existing object is altered; existing rows are not backfilled.

CREATE TABLE public.role_change_history (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    subject         text NOT NULL,
    role_key        text NOT NULL,
    change_kind     text NOT NULL CHECK (change_kind IN ('GRANT', 'CHANGE', 'REVOKE')),
    actor           text,
    recorded_at     timestamptz NOT NULL DEFAULT now(),
    effective_from  timestamptz NOT NULL,
    effective_to    timestamptz,
    PRIMARY KEY (organization_id, id),
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CHECK (change_kind <> 'REVOKE' OR effective_to IS NOT NULL),
    CHECK (btrim(subject) <> '' AND btrim(role_key) <> '')
);

ALTER TABLE public.role_change_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.role_change_history FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.role_change_history
    USING (organization_id = public.app_org())
    WITH CHECK (organization_id = public.app_org());

CREATE INDEX role_change_history_subject_idx
    ON public.role_change_history (organization_id, subject, effective_from DESC, id DESC);

-- Reuse V4's owner-proof immutability trigger: a role grant or revocation may
-- append a row, but no role (migration owner included) can rewrite one.
CREATE TRIGGER role_change_history_no_update_delete
    BEFORE UPDATE OR DELETE ON public.role_change_history
    FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation();
CREATE TRIGGER role_change_history_no_truncate
    BEFORE TRUNCATE ON public.role_change_history
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation();

-- Resolve the actor subject from the tenant-local GUC and record one ledger row.
-- The function is SECURITY DEFINER so the ledger is written even when the caller
-- is the non-owner runtime role. The tenant GUC is saved and restored around the
-- insert so a trigger never leaks organization context into the caller's session.
CREATE OR REPLACE FUNCTION public.capture_role_change()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog
AS $$
DECLARE
    v_org       uuid;
    v_subject   text;
    v_actor     text;
    v_kind      text;
    v_prev_org  text;
BEGIN
    v_prev_org := current_setting('app.current_org', true);
    v_actor := NULLIF(current_setting('app.actor', true), '');

    IF TG_TABLE_NAME = 'membership' THEN
        IF NEW.status IS DISTINCT FROM 'revoked' THEN
            RETURN NULL;
        END IF;
        v_org := NEW.organization_id;
        SELECT u.subject INTO v_subject
          FROM public.user_identity u
         WHERE u.organization_id = v_org AND u.id = NEW.user_id;
        IF v_subject IS NULL THEN
            RETURN NULL;
        END IF;
        PERFORM set_config('app.current_org', v_org::text, true);
        -- The revoked row is new; the roles being revoked belong to the user's
        -- previous active membership, so close those effective windows instead.
        INSERT INTO public.role_change_history
            (organization_id, subject, role_key, change_kind, actor,
             effective_from, effective_to)
        SELECT v_org, v_subject, ra.role_key, 'REVOKE', v_actor,
               coalesce(NEW.valid_from, now()), coalesce(NEW.valid_to, now())
          FROM public.membership m
          JOIN public.role_assignment ra
            ON ra.organization_id = m.organization_id AND ra.membership_id = m.id
         WHERE m.organization_id = v_org
           AND m.user_id = NEW.user_id
           AND m.id <> NEW.id
           AND m.status = 'active'
           AND (m.valid_to IS NULL OR m.valid_to > now())
           AND ra.valid_from <= now()
           AND (ra.valid_to IS NULL OR ra.valid_to > now());
        PERFORM set_config('app.current_org', coalesce(v_prev_org, ''), true);
        RETURN NULL;
    END IF;

    -- role_assignment insert: the grant belongs to the membership's subject.
    v_org := NEW.organization_id;
    SELECT u.subject INTO v_subject
      FROM public.membership m
      JOIN public.user_identity u
        ON u.organization_id = m.organization_id AND u.id = m.user_id
     WHERE m.organization_id = v_org AND m.id = NEW.membership_id;
    IF v_subject IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT CASE WHEN EXISTS (
               SELECT 1 FROM public.role_assignment ra
                WHERE ra.organization_id = v_org
                  AND ra.membership_id = NEW.membership_id
                  AND ra.role_key <> NEW.role_key
           ) THEN 'CHANGE' ELSE 'GRANT' END INTO v_kind;
    IF v_actor IS NULL AND NEW.granted_by IS NOT NULL THEN
        SELECT u.subject INTO v_actor
          FROM public.user_identity u
         WHERE u.organization_id = v_org AND u.id = NEW.granted_by;
    END IF;
    PERFORM set_config('app.current_org', v_org::text, true);
    INSERT INTO public.role_change_history
        (organization_id, subject, role_key, change_kind, actor,
         effective_from, effective_to)
    VALUES (v_org, v_subject, NEW.role_key, v_kind, v_actor,
            coalesce(NEW.valid_from, now()), NEW.valid_to);
    PERFORM set_config('app.current_org', coalesce(v_prev_org, ''), true);
    RETURN NULL;
END $$;

CREATE TRIGGER role_change_capture_grant
    AFTER INSERT ON public.role_assignment
    FOR EACH ROW EXECUTE FUNCTION public.capture_role_change();
CREATE TRIGGER role_change_capture_revoke
    AFTER INSERT ON public.membership
    FOR EACH ROW EXECUTE FUNCTION public.capture_role_change();

REVOKE ALL ON FUNCTION public.capture_role_change() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.capture_role_change() TO orevia_migrate;

-- Reads only: the ledger can be appended to solely by the triggers above.
GRANT SELECT ON public.role_change_history TO orevia_app;
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON public.role_change_history FROM orevia_app;
