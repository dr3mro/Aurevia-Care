-- Requirement IDs: ADM-01, PRD-05, ARC-02, OPS-02
-- Catalog entries reuse the append-only config tables from V5 with keys of the
-- form catalog.<family>.<code>. This fold returns the single effective approved
-- version per code in a family. No V1-V5 object is altered; forced RLS, the
-- actor-derived author/approver triggers and the V3 audit chain are unchanged.

CREATE OR REPLACE FUNCTION public.effective_catalog_family(p_prefix text)
RETURNS TABLE (organization_id uuid, id uuid, key text, version integer, value text,
               effective_from timestamptz, created_by uuid, created_at timestamptz,
               approved_by uuid, approved_at timestamptz, note text)
LANGUAGE sql STABLE SET search_path = pg_catalog
AS $$
    SELECT DISTINCT ON (e.key) e.organization_id, e.id, e.key, e.version, e.value,
           e.effective_from, e.created_by, e.created_at,
           a.approved_by, a.approved_at, a.note
      FROM public.config_entry e
      JOIN public.config_approval a
        ON a.organization_id = e.organization_id AND a.entry_id = e.id
     WHERE e.key LIKE p_prefix || '%' AND e.effective_from <= now()
     ORDER BY e.key, e.effective_from DESC, e.created_at DESC, e.id DESC
$$;

REVOKE ALL ON FUNCTION public.effective_catalog_family(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.effective_catalog_family(text) TO orevia_app;
