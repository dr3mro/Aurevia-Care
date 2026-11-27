-- Requirement IDs: IAM-01, ARC-02, OPS-02
-- Change: expand
--
-- Purpose: give scheduled jobs one authoritative source of active organization
--   identifiers. A scheduler has no request context and therefore no
--   app.current_org GUC, so an ordinary tenant-scoped read cannot enumerate
--   tenants. "Active" is an explicit, server-owned property of the tenant:
--   staff membership churn or an expired membership never changes it.
-- Invariant: public.active_organization_ids() returns every active
--   organization_id and nothing else, independent of the caller's GUCs. A
--   direct SELECT by aurevia_app stays limited to its own tenant.
-- RLS: organization keeps ENABLE + FORCE ROW LEVEL SECURITY and V2's
--   tenant_isolation policy. This migration adds one owner-only permissive
--   policy so the SECURITY DEFINER function, which runs as the NOBYPASSRLS
--   owner under forced RLS, can see all rows. Permissive policies combine with
--   OR: the added policy applies only to aurevia_migrate, never to aurevia_app.
--   No table grant is widened and no BYPASSRLS privilege is granted.
-- Lock/volume: ADD COLUMN with a constant default is metadata-only in modern
--   PostgreSQL; no row rewrite, backfill or index build is needed.
-- Recovery / forward-fix: additive only; V1 and V2 are untouched and no row is
--   changed by this migration. Disabling the capability is a REVOKE EXECUTE
--   from aurevia_app; a later contract migration may drop the function, policy
--   and column with Requires-expand: V101.

ALTER TABLE public.organization ADD COLUMN active boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN public.organization.active IS
    'Whether scheduled background work runs for this tenant; independent of staff membership.';

-- The migration/runtime role split (V2) gives the owner no BYPASSRLS. Forced
-- RLS therefore also constrains the owner, so a definer function would see
-- zero rows on a scheduler thread. A permissive policy restricted to the owner
-- makes the definer's effective predicate true while leaving every other role
-- on the tenant_isolation predicate. The function's WHERE active remains the
-- single authoritative filter for which tenants are scheduled.
CREATE POLICY active_organization_enumerator ON public.organization
    TO aurevia_migrate
    USING (true);

-- Identifier-only result: the enumerator cannot expose display names, staff or
-- patient data. search_path is pinned so no caller-controlled object can be
-- resolved, and every table reference is schema-qualified for the same reason.
CREATE FUNCTION public.active_organization_ids()
RETURNS TABLE (organization_id uuid)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog
AS $$
    SELECT o.organization_id
      FROM public.organization AS o
     WHERE o.active
     ORDER BY o.organization_id
$$;

-- State the ownership contract explicitly even if Flyway is ever run by a
-- different administrator.
ALTER FUNCTION public.active_organization_ids() OWNER TO aurevia_migrate;

-- Function EXECUTE defaults to PUBLIC; remove it before granting the runtime
-- role alone. No table SELECT grant changes and no role gains BYPASSRLS.
REVOKE ALL ON FUNCTION public.active_organization_ids() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.active_organization_ids() TO aurevia_app;
