-- Requirement IDs: ARC-03, OPS-06
-- Change: expand
--
-- Purpose: historical task IDs XCH-03 and RX-02 were used as PostgreSQL
-- identifier prefixes in V21, V44 and V59. The domain names are stable business
-- concepts, so this forward migration renames the physical relations, indexes,
-- triggers and functions in place. It never edits an applied migration: those
-- files remain the immutable record of what was originally executed.
--
-- Invariants kept: table OIDs, rows, foreign keys, RLS policies, FORCE RLS,
-- grants and trigger bindings are unchanged. PostgreSQL resolves triggers and
-- policies by OID, so renaming the referenced functions and tables preserves
-- every security invariant; only the catalog names change. Constraint names on
-- unique indexes are renamed with their index by ALTER INDEX ... RENAME TO.
--
-- Rollback/forward-fix: reverting this version renames each object back with
-- the inverse mapping. No data migration is needed and no application downtime
-- is required if callers are deployed with the matching identifier change.
DO $$
DECLARE
    r record;
BEGIN
    -- Tables first: triggers and indexes are then addressed by their new names.
    FOR r IN
        SELECT tablename FROM pg_tables
        WHERE schemaname = 'public'
          AND (tablename LIKE 'xch03\_%' OR tablename LIKE 'rx02\_%')
    LOOP
        EXECUTE format('ALTER TABLE public.%I RENAME TO %I', r.tablename,
            replace(replace(replace(r.tablename,
                'rx02_prescription', 'prescription_transmission'),
                'rx02_', 'prescription_'), 'xch03_', 'exchange_response_'));
    END LOOP;

    -- Indexes, including the backing indexes of unique/primary-key constraints.
    FOR r IN
        SELECT indexname FROM pg_indexes
        WHERE schemaname = 'public'
          AND (indexname LIKE 'xch03\_%' OR indexname LIKE 'rx02\_%')
    LOOP
        EXECUTE format('ALTER INDEX public.%I RENAME TO %I', r.indexname,
            replace(replace(replace(r.indexname,
                'rx02_prescription', 'prescription_transmission'),
                'rx02_', 'prescription_'), 'xch03_', 'exchange_response_'));
    END LOOP;

    -- Triggers keep their function bindings through the function OID.
    FOR r IN
        SELECT t.tgname AS name, c.relname AS table_name
        FROM pg_trigger t
        JOIN pg_class c ON c.oid = t.tgrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE NOT t.tgisinternal AND n.nspname = 'public'
          AND (t.tgname LIKE 'xch03\_%' OR t.tgname LIKE 'rx02\_%')
    LOOP
        EXECUTE format('ALTER TRIGGER %I ON public.%I RENAME TO %I',
            r.name, r.table_name,
            replace(replace(replace(r.name,
                'rx02_prescription', 'prescription_transmission'),
                'rx02_', 'prescription_'), 'xch03_', 'exchange_response_'));
    END LOOP;

    -- All task-ID functions are parameterless, so the empty signature is exact.
    FOR r IN
        SELECT p.proname AS name
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public'
          AND (p.proname LIKE '%xch03%' OR p.proname LIKE '%rx02%')
    LOOP
        EXECUTE format('ALTER FUNCTION public.%I() RENAME TO %I',
            r.name,
            replace(replace(replace(replace(r.name,
                'rx02_prescription', 'prescription_transmission'),
                'rx02_', 'prescription_'),
                'xch03_', 'exchange_response_'),
                'xch03', 'exchange_response'));
    END LOOP;
END $$;
