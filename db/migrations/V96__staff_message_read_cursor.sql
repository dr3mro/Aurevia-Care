-- Requirement IDs: MSG-02, ARC-02, ARC-07
-- Change: expand
-- Durable state for instant-message read cursors. V91 persists the immutable
-- staff_message ledger and V95 persists staff mail state, but neither records
-- how far an individual participant has read a conversation; the Java store has
-- kept that projection in process memory. This forward migration adds one
-- tenant-owned, mutable table keyed by tenant, conversation and participant so
-- an unread count survives a restart. Additive only; no existing object is
-- altered. The table has enabled and forced RLS with a tenant_isolation policy,
-- and the monotonic invariant is enforced on every write path: a cursor is only
-- ever raised, and it is clamped to the last sequence actually appended to the
-- immutable message ledger so it can never mark a future message as read.

-- One row per (tenant, conversation, participant). The composite key keeps two
-- participants' cursors independent and keeps a foreign tenant's key out of the
-- active tenant's rows. The composite foreign keys bind both the conversation
-- and the reader to the active organization; last_read_sequence is never
-- negative, matching the service's rejection of a negative acknowledgement.
CREATE TABLE public.staff_message_read_cursor (
    organization_id      uuid NOT NULL REFERENCES public.organization(organization_id),
    conversation_id      uuid NOT NULL,
    participant_subject  text NOT NULL,
    last_read_sequence   bigint NOT NULL CHECK (last_read_sequence >= 0),
    updated_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, conversation_id, participant_subject),
    FOREIGN KEY (organization_id, conversation_id)
        REFERENCES public.staff_conversation(organization_id, id),
    FOREIGN KEY (organization_id, participant_subject)
        REFERENCES public.user_identity(organization_id, subject)
);

-- Every new tenant table has forced RLS and a tenant policy; db/check.sh derives
-- the expected set from the catalog, so a new table cannot be forgotten here.
DO $rls$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['staff_message_read_cursor']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            $p$CREATE POLICY tenant_isolation ON public.%I
                 USING (organization_id = public.app_org())
                 WITH CHECK (organization_id = public.app_org())$p$, t);
    END LOOP;
END $rls$;

-- A cursor is per-person read state, not a clinical record: it may be advanced
-- but never deleted or truncated by the application. Reads and writes stay in
-- the tenant-guarded transaction so app_org() matches the named organization.
GRANT SELECT, INSERT, UPDATE ON public.staff_message_read_cursor TO aurevia_app;
REVOKE DELETE, TRUNCATE ON public.staff_message_read_cursor FROM aurevia_app;
