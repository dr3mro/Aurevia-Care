-- Requirement IDs: OPS-02, ARC-05
-- Append-only, tamper-evident audit using core sha256(bytea) (PG14+; no extension).
CREATE OR REPLACE FUNCTION public.audit_row_material(
    p_org uuid, p_seq bigint, p_occurred timestamptz, p_actor uuid,
    p_action text, p_resource_type text, p_resource_id text, p_purpose text,
    p_scope text, p_correlation_id text, p_outcome text, p_payload jsonb)
RETURNS text LANGUAGE sql IMMUTABLE SET search_path = pg_catalog
AS $$
    SELECT p_org::text || '|' || p_seq::text || '|' ||
           coalesce(extract(epoch from p_occurred)::text, '') || '|' ||
           coalesce(p_actor::text, '') || '|' || p_action || '|' ||
           coalesce(p_resource_type, '') || '|' || coalesce(p_resource_id, '') || '|' ||
           coalesce(p_purpose, '') || '|' || coalesce(p_scope, '') || '|' ||
           coalesce(p_correlation_id, '') || '|' || p_outcome
$$;

CREATE OR REPLACE FUNCTION public.audit_event_before_insert()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_prev bytea;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text, 0));
    -- The sequence is owned by this trigger, never by the caller.
    NEW.seq := NULL;
    SELECT coalesce(max(seq), 0) + 1 INTO NEW.seq
      FROM public.audit_event WHERE organization_id = NEW.organization_id;
    IF NEW.actor_id IS NULL THEN
        SELECT id INTO NEW.actor_id FROM public.user_identity
         WHERE subject = NULLIF(current_setting('app.actor', true), '');
    END IF;
    SELECT row_hash INTO v_prev
      FROM public.audit_event
     WHERE organization_id = NEW.organization_id
     ORDER BY seq DESC LIMIT 1;
    NEW.previous_hash := coalesce(v_prev, decode(repeat('00', 32), 'hex'));
    NEW.row_hash := sha256(convert_to(
        encode(NEW.previous_hash, 'hex') || '|' ||
        public.audit_row_material(NEW.organization_id, NEW.seq, NEW.occurred_at,
            NEW.actor_id, NEW.action, NEW.resource_type, NEW.resource_id,
            NEW.purpose, NEW.scope, NEW.correlation_id, NEW.outcome, '{}'::jsonb), 'UTF8'));
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION public.audit_event_reject_mutation()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$ BEGIN
    RAISE EXCEPTION 'audit_event is append-only (%, organization_id=%)',
        TG_OP, OLD.organization_id USING ERRCODE = '44000';
END $$;

CREATE TRIGGER audit_event_chain_insert
    BEFORE INSERT ON public.audit_event
    FOR EACH ROW EXECUTE FUNCTION public.audit_event_before_insert();
CREATE TRIGGER audit_event_no_update_delete
    BEFORE UPDATE OR DELETE ON public.audit_event
    FOR EACH ROW EXECUTE FUNCTION public.audit_event_reject_mutation();

CREATE OR REPLACE FUNCTION public.audit_verify_chain(p_organization_id uuid)
RETURNS TABLE(check_seq bigint, ok boolean, note text)
LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE
    v_prev bytea := decode(repeat('00', 32), 'hex');
    v_expected_seq bigint := 1;
    r record;
    v_expected bytea;
BEGIN
    -- Never change the caller's tenant context; cross-tenant verification is denied.
    IF p_organization_id IS DISTINCT FROM public.app_org() THEN
        RAISE EXCEPTION 'audit verification requires the active tenant context'
            USING ERRCODE = '42501';
    END IF;
    FOR r IN SELECT * FROM public.audit_event
              WHERE organization_id = p_organization_id ORDER BY seq
    LOOP
        v_expected := sha256(convert_to(
            encode(v_prev, 'hex') || '|' ||
            public.audit_row_material(r.organization_id, r.seq, r.occurred_at,
                r.actor_id, r.action, r.resource_type, r.resource_id,
                r.purpose, r.scope, r.correlation_id, r.outcome, '{}'::jsonb), 'UTF8'));
        IF r.seq <> v_expected_seq THEN
            check_seq := r.seq; ok := false; note := 'seq gap';
        ELSIF r.previous_hash IS DISTINCT FROM v_prev THEN
            check_seq := r.seq; ok := false; note := 'previous_hash mismatch';
        ELSIF r.row_hash IS DISTINCT FROM v_expected THEN
            check_seq := r.seq; ok := false; note := 'row_hash mismatch';
        ELSE
            check_seq := r.seq; ok := true; note := 'ok';
        END IF;
        RETURN NEXT;
        v_prev := r.row_hash;
        v_expected_seq := v_expected_seq + 1;
    END LOOP;
END $$;

REVOKE ALL ON FUNCTION public.audit_verify_chain(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.audit_verify_chain(uuid) TO orevia_app;
