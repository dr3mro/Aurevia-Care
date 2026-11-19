-- Requirement IDs: IAM-01, ARC-02, OPS-02
-- Separate migration/runtime roles; forced RLS on every tenant table.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'smartclinic_migrate') THEN
        EXECUTE 'CREATE ROLE smartclinic_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'smartclinic_app') THEN
        EXECUTE 'CREATE ROLE smartclinic_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE';
    END IF;
END $$;

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
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO smartclinic_app', current_database());
END $$;
GRANT USAGE ON SCHEMA public TO smartclinic_app;
GRANT EXECUTE ON FUNCTION public.app_org() TO smartclinic_app;
GRANT SELECT ON public.organization, public.user_identity, public.facility,
               public.membership, public.role_assignment, public.audit_event
    TO smartclinic_app;
GRANT INSERT, UPDATE ON public.facility, public.membership, public.role_assignment
    TO smartclinic_app;
GRANT INSERT ON public.audit_event TO smartclinic_app;
REVOKE UPDATE, DELETE ON public.audit_event FROM smartclinic_app;
