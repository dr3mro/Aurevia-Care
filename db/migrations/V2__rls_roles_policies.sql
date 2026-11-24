-- Requirement IDs: IAM-01, ARC-02, OPS-02
-- Separate migration/runtime roles; forced RLS on every tenant table.
-- Roles aurevia_migrate and aurevia_app are provisioned out-of-band
-- (IaC/DBA) before migrations; Flyway must not need CREATEROLE.

CREATE OR REPLACE FUNCTION public.app_org() RETURNS uuid
LANGUAGE sql STABLE SET search_path = pg_catalog
AS $$ SELECT NULLIF(current_setting('app.current_org', true), '')::uuid $$;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['organization', 'user_identity', 'facility',
                              'membership', 'role_assignment', 'audit_event']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            $p$CREATE POLICY tenant_isolation ON public.%I
                 USING (organization_id = public.app_org())
                 WITH CHECK (organization_id = public.app_org())$p$, t);
    END LOOP;
END $$;

DO $$ BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO aurevia_app', current_database());
END $$;
GRANT USAGE ON SCHEMA public TO aurevia_app;
GRANT EXECUTE ON FUNCTION public.app_org() TO aurevia_app;
GRANT SELECT ON public.organization, public.user_identity, public.facility,
               public.membership, public.role_assignment, public.audit_event
    TO aurevia_app;
GRANT INSERT ON public.facility TO aurevia_app;
GRANT INSERT ON public.audit_event TO aurevia_app;
REVOKE UPDATE, DELETE ON public.audit_event FROM aurevia_app;
-- Membership and role_assignment are authorization inputs only: no app write path.
