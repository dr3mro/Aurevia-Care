-- V102: durable client message ids for conversation replies.
--
-- A retry after an outage must return the message that was already accepted
-- instead of appending a second patient message. The key is tenant-scoped and
-- conversation-scoped; the unique constraint is the concurrent-retry backstop.
-- The table keeps the V33 RLS policy, FORCE RLS and append-only triggers, so no
-- cross-tenant read or write path is introduced by this additive change.
--
-- A column default supplies a stable value for legacy rows. An UPDATE backfill
-- would be invisible under forced RLS and is rejected by V33's immutable-row
-- trigger for every role, so it cannot be used here. New writes always bind the
-- client key explicitly. This is a full-table rewrite and a unique-index build:
-- expect a brief SHARE lock and table bloat proportional to existing messages.
-- Forward fix after a client rollback: DROP CONSTRAINT then DROP COLUMN; no
-- data is read or written by the application once the column is gone.
ALTER TABLE public.conversation_message
    ADD COLUMN client_message_id text NOT NULL DEFAULT gen_random_uuid()::text;

ALTER TABLE public.conversation_message
    ADD CONSTRAINT conversation_message_client_key_length
        CHECK (char_length(client_message_id) BETWEEN 1 AND 255),
    ADD CONSTRAINT conversation_message_client_key_unique
        UNIQUE (organization_id, conversation_id, client_message_id);

-- Legacy rows now have a stable key. New writes must bind their own client key;
-- leaving a generated default would let a future caller silently opt out of
-- durable deduplication.
ALTER TABLE public.conversation_message
    ALTER COLUMN client_message_id DROP DEFAULT;
