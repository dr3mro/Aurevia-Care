-- Requirement IDs: MSG-07
-- Change: expand
-- Export provenance for conversation governance. A scoped export must keep a
-- non-sensitive pointer to the source material, its immutable revision and the
-- purpose of the copy, so a later authorized review can trace a copy back to
-- its origin after the source has left the retention window. Only the export
-- record is extended; no clinical payload is copied into the governance store.
--
-- Additive only: the existing messaging_export table already carries tenant
-- ownership, forced row-level security, an append-only reject-mutation trigger
-- and the runtime grants from V62, so those boundaries are inherited unchanged.
-- Backfilled rows receive empty provenance; only newly created exports carry
-- validated source references and revisions.

ALTER TABLE public.messaging_export
    ADD COLUMN source_reference text NOT NULL DEFAULT '',
    ADD COLUMN source_revision  text NOT NULL DEFAULT '',
    ADD COLUMN export_purpose   text NOT NULL DEFAULT '';
