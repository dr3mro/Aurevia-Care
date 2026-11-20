-- Requirement IDs: IAM-04, IAM-05, ARC-01, ARC-02
-- Change: expand
-- Repair two defects found by the real-PostgreSQL integration suite. No applied
-- migration is edited: both fixes are forward-only and idempotent.
--
-- 1. V10 attached set_link_actor() to account_chart_link, whose append-only link
--    events carry verified_by but not created_by. The function assigned
--    NEW.created_by, so every link INSERT failed with SQLSTATE 42703
--    ("record "new" has no field "created_by""). Redefine it to assign only the
--    column that exists. The historical function name is retained because it has
--    one attachment and renaming a trigger function would require a destructive
--    DROP TRIGGER/DROP FUNCTION contract for no behavioral benefit.
--
-- 2. V9 declared duplicate_review.id NOT NULL without a default, but the review
--    repository deliberately omits the column and reads the new ticket back by
--    its (survivor, duplicate) pair. Give the table a database-owned default,
--    matching account_invitation, account_chart_link and consolidation_dependency.

CREATE OR REPLACE FUNCTION public.set_link_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    NEW.verified_by := NULLIF(current_setting('app.actor', true), '');
    IF NEW.verified_by IS NULL THEN
        RAISE EXCEPTION 'identity command requires an authenticated actor'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END $$;

-- Match V10's other actor functions: a trigger function needs no PUBLIC EXECUTE.
REVOKE ALL ON FUNCTION public.set_link_actor() FROM PUBLIC;

ALTER TABLE public.duplicate_review
    ALTER COLUMN id SET DEFAULT gen_random_uuid();
