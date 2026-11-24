-- Requirement IDs: CLN-07, ARC-02, ARC-06, OPS-02
-- Change: expand
--
-- Verified download of a retained original (CLN-07). The SHA-256 was derived
-- from the exact uploaded bytes on insert by V17; here the digest is recomputed
-- from the bytes actually held in the association and reported alongside those
-- bytes, so the caller can refuse an original whose retained bytes no longer
-- match the digest captured on upload.
--
-- The function deliberately does NOT raise a SQL error on a mismatch. An error
-- would abort the caller's transaction, preventing the required OPS-02 audit of
-- the tamper and turning the intended 409 into a 500. Returning an `intact`
-- flag keeps the transaction usable while never placing tampered bytes in a
-- verifiable result. Forced RLS bounds the read to the active tenant and an
-- absent or foreign row yields no row at all.
--
-- The function is additive: it creates no table and changes no existing row.

CREATE OR REPLACE FUNCTION public.document_association_verified_content(p_id uuid)
RETURNS TABLE (content bytea, intact boolean)
LANGUAGE plpgsql STABLE SET search_path = pg_catalog
AS $$
BEGIN
    RETURN QUERY
    SELECT da.content,
           encode(sha256(da.content), 'hex') IS NOT DISTINCT FROM da.sha256
      FROM public.document_association da
     WHERE da.id = p_id;
END $$;

REVOKE ALL ON FUNCTION public.document_association_verified_content(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.document_association_verified_content(uuid) TO orevia_app;
