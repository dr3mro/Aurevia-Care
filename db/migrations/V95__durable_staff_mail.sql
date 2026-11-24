-- Requirement IDs: MSG-02, ARC-02, ARC-07
-- Change: expand
-- Durable state for email-like staff messages. V91 already owns the immutable
-- message ledger (staff_message), its metadata (staff_mail_metadata) and the
-- per-person folders (staff_mail_folder); this forward migration adds the two
-- gaps it cannot hold: a mutable, versioned draft and a send retry ledger. Two
-- columns extend the V91 metadata so a published message can be reconstructed
-- exactly as the sender confirmed it. Additive only; no V91 object is altered
-- destructively. New tables are tenant-owned with forced RLS and a tenant policy.

-- A private draft is the only mutable mail state. The version is advanced by the
-- service under a caller-supplied If-Match precondition; the database keeps the
-- invariant that a version is positive. Recipients keep their kind so a role is
-- still distinguishable from a person when the draft is later sent.
CREATE TABLE public.staff_mail_draft (
    organization_id  uuid NOT NULL REFERENCES public.organization(organization_id),
    id               uuid NOT NULL DEFAULT gen_random_uuid(),
    facility_id      uuid NOT NULL,
    owner_subject    text NOT NULL,
    version          integer NOT NULL CHECK (version > 0),
    subject          text,
    body             text,
    to_recipients    text[] NOT NULL DEFAULT '{}',
    cc_recipients    text[] NOT NULL DEFAULT '{}',
    attachment_ids   text[] NOT NULL DEFAULT '{}',
    thread_id        uuid,
    updated_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, id),
    FOREIGN KEY (organization_id, facility_id)
        REFERENCES public.facility(organization_id, id),
    FOREIGN KEY (organization_id, owner_subject)
        REFERENCES public.user_identity(organization_id, subject)
);

CREATE INDEX staff_mail_draft_owner_idx ON public.staff_mail_draft
    (organization_id, owner_subject, updated_at);

-- One row per (tenant, sender, client key) freezes the first publication. The
-- primary key is the durable idempotency backstop: a concurrent retry either
-- loses the insert and reads the winning message, or becomes the winner. A
-- rolled back send leaves no claim, so the key can be retried after a failure.
CREATE TABLE public.staff_mail_idempotency (
    organization_id     uuid NOT NULL,
    actor_subject       text NOT NULL,
    client_message_id   text NOT NULL CHECK (length(client_message_id) BETWEEN 1 AND 200),
    message_id          uuid NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, actor_subject, client_message_id),
    FOREIGN KEY (organization_id, actor_subject)
        REFERENCES public.user_identity(organization_id, subject),
    -- Deferred so the retry claim can be written first and still win a race:
    -- the message it names is inserted later in the same transaction.
    FOREIGN KEY (organization_id, message_id)
        REFERENCES public.staff_message(organization_id, id)
        DEFERRABLE INITIALLY DEFERRED
);

-- The V91 ledger stores body, author, role and thread but not the frozen
-- resolution of a role recipient nor the linked documents. Both are recorded at
-- send time so a later role change or attachment edit cannot rewrite the mail.
ALTER TABLE public.staff_mail_metadata
    ADD COLUMN resolved_recipients text[] NOT NULL DEFAULT '{}';
ALTER TABLE public.staff_mail_metadata
    ADD COLUMN attachment_ids text[] NOT NULL DEFAULT '{}';

-- Every new tenant table has forced RLS and a tenant policy; db/check.sh derives
-- the expected set from the catalog, so a new table cannot be forgotten here.
DO $rls$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['staff_mail_draft', 'staff_mail_idempotency']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            $p$CREATE POLICY tenant_isolation ON public.%I
                 USING (organization_id = public.app_org())
                 WITH CHECK (organization_id = public.app_org())$p$, t);
    END LOOP;
END $rls$;

-- The draft is mutable but never truncated; the retry ledger is append-only.
-- The message ledger and folders keep the V91 grant set and are reused unchanged.
GRANT SELECT, INSERT, UPDATE, DELETE ON public.staff_mail_draft TO aurevia_app;
GRANT SELECT, INSERT ON public.staff_mail_idempotency TO aurevia_app;
REVOKE TRUNCATE ON public.staff_mail_draft FROM aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.staff_mail_idempotency FROM aurevia_app;
