-- Requirement IDs: CLN-07, ARC-02, ARC-06, OPS-02
-- Change: expand
--
-- Verified download of a retained original (CLN-07). The SHA-256 was derived
-- from the exact uploaded bytes on insert by V17; here the digest is recomputed
-- from the bytes actually held in the association and compared with the stored
-- digest before any byte is returned. A mismatch means the retained bytes were
-- altered in storage and the download is refused, so a tampered original can
-- never be served as the verified artifact.
--
-- The function is additive: it creates no table and changes no existing row.
-- It runs as the invoker, so forced RLS on document_association still applies
-- and a tenant cannot reach another tenant's association through this path.

CREATE OR REPLACE FUNCTION public.document_association_verified_content(p_id uuid)
RETURNS bytea
LANGUAGE plpgsql STABLE SET search_path = pg_catalog
AS $$
DECLARE
    v_content bytea;
    v_digest  text;
BEGIN
    SELECT content, sha256 INTO v_content, v_digest
      FROM public.document_association
     WHERE id = p_id;
    IF NOT FOUND THEN
        -- Conceal an absent or foreign row exactly like a direct SELECT would.
        RETURN NULL;
    END IF;
    IF encode(sha256(v_content), 'hex') IS DISTINCT FROM v_digest THEN
        RAISE EXCEPTION 'retained original bytes do not match the stored SHA-256'
            USING ERRCODE = '23514';
    END IF;
    RETURN v_content;
END $$;

REVOKE ALL ON FUNCTION public.document_association_verified_content(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.document_association_verified_content(uuid) TO smartclinic_app;
