-- Requirement IDs: OPS-02, ARC-02
-- Change: expand
-- Purpose: V3's audit_event_before_insert derived actor_id from user_identity by
--   subject alone. A caller whose session context does not match the row being
--   written (for example a SECURITY DEFINER worker or a BYPASSRLS maintenance
--   role) can resolve a shared subject to a user of another tenant and stamp a
--   foreign actor on the audit event. This migration replaces the function with
--   the same signature and contract but binds the lookup to NEW.organization_id,
--   matching the actor-derivation pattern already used by later migrations.
-- RLS invariants: audit_event and user_identity keep ENABLE + FORCE ROW LEVEL
--   SECURITY and their tenant_isolation policy (organization_id = app_org()).
--   This migration changes no policy, GRANT or REVOKE. The added predicate is
--   defence in depth that holds even when a caller's session context does not
--   match the row it writes; RLS alone must never be the only guard.
-- Lock/volume impact: CREATE OR REPLACE FUNCTION takes a brief lock on the
--   function only. No table is rewritten, no row is backfilled and no index is
--   built; existing audit rows and their V3 hash chain are untouched.
-- Recovery / forward-fix: the change is additive and idempotent; re-applying
--   the version is safe. If the predicate must be reverted, a later expand
--   migration restores the prior body. No data repair is required.

-- The advisory lock, trigger-owned sequence, hash material and return contract
-- are unchanged from V3, so existing chains still verify. The only edit is the
-- tenant predicate in the actor lookup.
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
        -- A subject in another tenant must never be stamped on this tenant's row.
        SELECT id INTO NEW.actor_id FROM public.user_identity
         WHERE organization_id = NEW.organization_id
           AND subject = NULLIF(current_setting('app.actor', true), '');
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
