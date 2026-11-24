-- Requirement IDs: MSG-03, MSG-07, ARC-07
-- Change: expand
-- Staff-to-staff messaging data model (SMSG-02). Additive only: six new
-- tenant-owned tables, no existing object is altered. Messages are an
-- append-only ledger: content is immutable for every role and a correction is a
-- later message that points at the original. Sequence numbers are allocated in
-- the inserting transaction under a per-conversation advisory lock, so they are
-- gap-free without relying on the caller. Mail folders and flags are per
-- participant. Cross-organization messaging is not enabled by this migration.

-- A conversation is scoped to the active tenant and one facility of it.
CREATE TABLE public.staff_conversation (
    organization_id     uuid NOT NULL REFERENCES public.organization(organization_id),
    id                  uuid NOT NULL DEFAULT gen_random_uuid(),
    facility_id         uuid NOT NULL,
    kind                text NOT NULL
        CHECK (kind IN ('direct', 'group', 'mail_thread')),
    topic               text,
    created_by_subject  text NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, id),
    FOREIGN KEY (organization_id, facility_id)
        REFERENCES public.facility(organization_id, id),
    FOREIGN KEY (organization_id, created_by_subject)
        REFERENCES public.user_identity(organization_id, subject)
);

CREATE INDEX staff_conversation_facility_idx ON public.staff_conversation
    (organization_id, facility_id, created_at);

-- Membership history: a participant who leaves is not deleted, so history stays
-- visible to everybody who was a member at the time.
CREATE TABLE public.staff_participant (
    organization_id     uuid NOT NULL,
    conversation_id     uuid NOT NULL,
    staff_subject       text NOT NULL,
    role                text NOT NULL CHECK (role IN ('owner', 'member')),
    joined_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
    left_at             timestamptz,
    muted               boolean NOT NULL DEFAULT false,
    PRIMARY KEY (organization_id, conversation_id, staff_subject),
    FOREIGN KEY (organization_id, conversation_id)
        REFERENCES public.staff_conversation(organization_id, id),
    FOREIGN KEY (organization_id, staff_subject)
        REFERENCES public.user_identity(organization_id, subject)
);

CREATE INDEX staff_participant_subject_idx ON public.staff_participant
    (organization_id, staff_subject, left_at);

-- Immutable message ledger. The author is set from the authenticated actor and
-- the sequence from the database, never from the caller. client_message_id is a
-- durable idempotency key (ARC-07); corrects_message_id makes a correction a new
-- row rather than an edit of the original.
CREATE TABLE public.staff_message (
    organization_id      uuid NOT NULL,
    id                   uuid NOT NULL DEFAULT gen_random_uuid(),
    conversation_id      uuid NOT NULL,
    sequence             bigint NOT NULL CHECK (sequence > 0),
    client_message_id    text NOT NULL
        CHECK (length(client_message_id) BETWEEN 1 AND 200),
    sender_subject       text NOT NULL,
    sender_role          text NOT NULL,
    body                 text NOT NULL,
    sent_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    corrects_message_id  uuid,
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, conversation_id, sequence),
    UNIQUE (organization_id, conversation_id, client_message_id),
    FOREIGN KEY (organization_id, conversation_id)
        REFERENCES public.staff_conversation(organization_id, id),
    FOREIGN KEY (organization_id, corrects_message_id)
        REFERENCES public.staff_message(organization_id, id)
);

CREATE INDEX staff_message_thread_idx ON public.staff_message
    (organization_id, conversation_id, sequence);

-- Allocate the next sequence while holding a transaction-scoped lock keyed by
-- tenant and conversation. Concurrent sends of one conversation serialize, so
-- no sequence can be skipped; a rolled back send leaves no gap because the row
-- never committed.
CREATE OR REPLACE FUNCTION public.next_staff_message_sequence(
    p_organization_id uuid, p_conversation_id uuid)
RETURNS bigint LANGUAGE plpgsql SET search_path = pg_catalog
AS $fn$
BEGIN
    PERFORM pg_advisory_xact_lock(
        hashtextextended(p_organization_id::text || ':' || p_conversation_id::text, 0));
    RETURN COALESCE(max(sequence), 0) + 1
      FROM public.staff_message
     WHERE organization_id = p_organization_id
       AND conversation_id = p_conversation_id;
END $fn$;

CREATE OR REPLACE FUNCTION public.assign_staff_message_sequence()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $fn$
BEGIN
    NEW.sequence := public.next_staff_message_sequence(
        NEW.organization_id, NEW.conversation_id);
    RETURN NEW;
END $fn$;

CREATE TRIGGER staff_message_assign_sequence
    BEFORE INSERT ON public.staff_message
    FOR EACH ROW EXECUTE FUNCTION public.assign_staff_message_sequence();

-- Authorship and conversation opening are always the authenticated actor of the
-- active tenant, matching the patient-messaging guard in V33.
CREATE OR REPLACE FUNCTION public.set_staff_actor_subject()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $fn$
BEGIN
    NEW.sender_subject := NULLIF(current_setting('app.actor', true), '');
    IF NEW.sender_subject IS NULL THEN
        RAISE EXCEPTION 'staff message sender must be an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END $fn$;

CREATE OR REPLACE FUNCTION public.set_staff_conversation_creator()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $fn$
BEGIN
    NEW.created_by_subject := NULLIF(current_setting('app.actor', true), '');
    IF NEW.created_by_subject IS NULL THEN
        RAISE EXCEPTION 'staff conversation creator must be an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END $fn$;

CREATE TRIGGER staff_message_sender_actor
    BEFORE INSERT ON public.staff_message
    FOR EACH ROW EXECUTE FUNCTION public.set_staff_actor_subject();
CREATE TRIGGER staff_conversation_creator_actor
    BEFORE INSERT ON public.staff_conversation
    FOR EACH ROW EXECUTE FUNCTION public.set_staff_conversation_creator();

-- The message ledger is immutable for every role, including the migration
-- owner. Reuse the single helper from V4 rather than defining a second one.
CREATE TRIGGER staff_message_no_update_delete
    BEFORE UPDATE OR DELETE ON public.staff_message
    FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation();
CREATE TRIGGER staff_message_no_truncate
    BEFORE TRUNCATE ON public.staff_message
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation();

-- Mail metadata: subject, recipients, flags and draft state. Flags and the
-- draft state advance with the mail workflow, so these rows are updatable.
CREATE TABLE public.staff_mail_metadata (
    organization_id     uuid NOT NULL,
    message_id          uuid NOT NULL,
    subject             text NOT NULL,
    to_recipients       text[] NOT NULL DEFAULT '{}',
    cc_recipients       text[] NOT NULL DEFAULT '{}',
    flags               text[] NOT NULL DEFAULT '{}',
    draft_state         text NOT NULL DEFAULT 'sent'
        CHECK (draft_state IN ('draft', 'sent')),
    PRIMARY KEY (organization_id, message_id),
    FOREIGN KEY (organization_id, message_id)
        REFERENCES public.staff_message(organization_id, id)
);

-- Folder placement belongs to the participant, not to the message: the same
-- mail can be in the inbox of one recipient and the archive of another.
CREATE TABLE public.staff_mail_folder (
    organization_id     uuid NOT NULL,
    message_id          uuid NOT NULL,
    staff_subject       text NOT NULL,
    folder              text NOT NULL
        CHECK (folder IN ('inbox', 'sent', 'drafts', 'archive', 'trash')),
    PRIMARY KEY (organization_id, message_id, staff_subject),
    FOREIGN KEY (organization_id, message_id)
        REFERENCES public.staff_mail_metadata(organization_id, message_id),
    FOREIGN KEY (organization_id, staff_subject)
        REFERENCES public.user_identity(organization_id, subject)
);

-- Link from a message to a document in the object store. The checksum and scan
-- verdict are carried so a reader can block a quarantined file before download
-- without a second store lookup. document_id is owned by the attachment slice
-- (SMSG-07); this migration deliberately does not couple staff messages to the
-- patient-shaped public.attachment table.
CREATE TABLE public.staff_attachment_link (
    organization_id     uuid NOT NULL,
    id                  uuid NOT NULL DEFAULT gen_random_uuid(),
    message_id          uuid NOT NULL,
    document_id         uuid NOT NULL,
    checksum            text NOT NULL CHECK (length(checksum) BETWEEN 1 AND 200),
    scan_state          text NOT NULL DEFAULT 'pending'
        CHECK (scan_state IN ('pending', 'clean', 'quarantined')),
    created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, message_id, document_id),
    FOREIGN KEY (organization_id, message_id)
        REFERENCES public.staff_message(organization_id, id)
);

-- Every tenant table has forced RLS and a tenant policy; db/check.sh derives
-- this set from the catalog, so a new table cannot be forgotten here.
DO $rls$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'staff_conversation', 'staff_participant', 'staff_message',
        'staff_mail_metadata', 'staff_mail_folder', 'staff_attachment_link']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            $p$CREATE POLICY tenant_isolation ON public.%I
                 USING (organization_id = public.app_org())
                 WITH CHECK (organization_id = public.app_org())$p$, t);
    END LOOP;
END $rls$;

-- Runtime role permissions. The message ledger is insert/select only; the
-- mutable projections may be updated but never deleted or truncated by the
-- application. Triggers bind the migration owner as well as the app role.
GRANT SELECT, INSERT, UPDATE ON public.staff_conversation TO aurevia_app;
GRANT SELECT, INSERT, UPDATE ON public.staff_participant TO aurevia_app;
GRANT SELECT, INSERT ON public.staff_message TO aurevia_app;
GRANT SELECT, INSERT, UPDATE ON public.staff_mail_metadata TO aurevia_app;
GRANT SELECT, INSERT, UPDATE ON public.staff_mail_folder TO aurevia_app;
GRANT SELECT, INSERT, UPDATE ON public.staff_attachment_link TO aurevia_app;

REVOKE DELETE, TRUNCATE ON public.staff_conversation FROM aurevia_app;
REVOKE DELETE, TRUNCATE ON public.staff_participant FROM aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.staff_message FROM aurevia_app;
REVOKE DELETE, TRUNCATE ON public.staff_mail_metadata FROM aurevia_app;
REVOKE DELETE, TRUNCATE ON public.staff_mail_folder FROM aurevia_app;
REVOKE DELETE, TRUNCATE ON public.staff_attachment_link FROM aurevia_app;

REVOKE ALL ON FUNCTION public.next_staff_message_sequence(uuid, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.assign_staff_message_sequence() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.set_staff_actor_subject() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.set_staff_conversation_creator() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.next_staff_message_sequence(uuid, uuid) TO aurevia_app;
GRANT EXECUTE ON FUNCTION public.assign_staff_message_sequence() TO aurevia_app;
GRANT EXECUTE ON FUNCTION public.set_staff_actor_subject() TO aurevia_app;
GRANT EXECUTE ON FUNCTION public.set_staff_conversation_creator() TO aurevia_app;
