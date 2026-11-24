-- Requirement IDs: RX-01, ARC-01, ARC-02, ARC-03, OPS-02
-- Change: expand
-- Prescription-draft foundation for RX-01: a market-approved, versioned drug
-- catalog, synthetic verified-allergy facts, a version-guarded draft and an
-- append-only override ledger that preserves the reason, the DB-derived author
-- and the matched data snapshot. No destructive DDL is used.
--
-- Every tenant table carries (organization_id, id) keys, every foreign key
-- includes the organization, and forced RLS from V2 confines each row to the
-- active organization. Authors are always derived from app.actor, never from a
-- request body. Append-only tables reject UPDATE/DELETE/TRUNCATE for every role.
--
-- Compatibility note: the offline db/check.sh runner applies V*.sql in shell
-- glob order, which places V43 before V4 and V7. Flyway applies by numeric
-- version and is the authority, so this migration is self-contained: it defines
-- its own immutability helper and omits the patient foreign key when the chart
-- table is not installed yet. It never skips a Flyway apply.

DO $rx$
BEGIN
    IF to_regclass('public.organization') IS NULL
       OR to_regclass('public.user_identity') IS NULL
       OR NOT EXISTS (
              SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'app_org')
       OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'aurevia_app') THEN
        RAISE NOTICE 'V43 prescription drafts deferred: prerequisites not installed yet';
        RETURN;
    END IF;

    EXECUTE $ddl$CREATE TABLE public.drug_catalog_entry (
        organization_id       uuid NOT NULL REFERENCES public.organization(organization_id),
        id                    uuid NOT NULL DEFAULT gen_random_uuid(),
        entry_key             text NOT NULL,
        version               integer NOT NULL DEFAULT 1 CHECK (version > 0),
        generic_name          text NOT NULL,
        trade_name            text NOT NULL,
        formulation           text NOT NULL,
        strength_value        text NOT NULL,
        strength_unit         text NOT NULL,
        substance_ids         text[] NOT NULL DEFAULT '{}'::text[],
        substance_groups                text[] NOT NULL DEFAULT '{}'::text[],
        group_versions        integer[] NOT NULL DEFAULT '{}'::integer[],
        approved              boolean NOT NULL DEFAULT false,
        effective_from        timestamptz NOT NULL DEFAULT now(),
        effective_to          timestamptz,
        approval_provenance   text,
        recorded_by           uuid NOT NULL,
        created_at            timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY (organization_id, id),
        UNIQUE (organization_id, entry_key, version),
        CHECK (cardinality(substance_ids) = cardinality(substance_groups)
               AND cardinality(substance_groups) = cardinality(group_versions)),
        CHECK (NOT approved OR approval_provenance IS NOT NULL),
        FOREIGN KEY (organization_id, recorded_by)
            REFERENCES public.user_identity(organization_id, id)
    )$ddl$;
    EXECUTE $ddl$CREATE INDEX drug_catalog_entry_search_idx ON public.drug_catalog_entry
        (organization_id, entry_key, version DESC, generic_name)$ddl$;

    EXECUTE $ddl$CREATE TABLE public.patient_allergy_status (
        organization_id       uuid NOT NULL REFERENCES public.organization(organization_id),
        id                    uuid NOT NULL DEFAULT gen_random_uuid(),
        patient_id            uuid NOT NULL,
        status                text NOT NULL
            CHECK (status IN ('verified_list', 'verified_none', 'unknown', 'unverified')),
        source                text NOT NULL,
        recorded_by           uuid NOT NULL,
        recorded_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
        PRIMARY KEY (organization_id, id),
        FOREIGN KEY (organization_id, recorded_by)
            REFERENCES public.user_identity(organization_id, id)
    )$ddl$;
    EXECUTE $ddl$CREATE INDEX patient_allergy_status_patient_idx ON public.patient_allergy_status
        (organization_id, patient_id, recorded_at DESC, id DESC)$ddl$;

    EXECUTE $ddl$CREATE TABLE public.patient_allergy (
        organization_id       uuid NOT NULL REFERENCES public.organization(organization_id),
        id                    uuid NOT NULL DEFAULT gen_random_uuid(),
        patient_id            uuid NOT NULL,
        substance_id          text NOT NULL,
        group_code            text,
        group_version         integer NOT NULL DEFAULT 0 CHECK (group_version >= 0),
        verification_state    text NOT NULL
            CHECK (verification_state IN ('verified', 'unverified', 'unknown')),
        source                text NOT NULL,
        effective_from        timestamptz NOT NULL DEFAULT now(),
        recorded_by           uuid NOT NULL,
        created_at            timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY (organization_id, id),
        CHECK (group_code IS NULL OR group_version > 0),
        FOREIGN KEY (organization_id, recorded_by)
            REFERENCES public.user_identity(organization_id, id)
    )$ddl$;
    EXECUTE $ddl$CREATE INDEX patient_allergy_patient_idx ON public.patient_allergy
        (organization_id, patient_id, verification_state)$ddl$;

    EXECUTE $ddl$CREATE TABLE public.prescription_draft (
        organization_id       uuid NOT NULL REFERENCES public.organization(organization_id),
        id                    uuid NOT NULL DEFAULT gen_random_uuid(),
        version               integer NOT NULL DEFAULT 1 CHECK (version > 0),
        patient_id            uuid NOT NULL,
        state                 text NOT NULL DEFAULT 'draft' CHECK (state = 'draft'),
        encounter_reference   text,
        destination           text,
        selected_entry_key    text,
        selected_entry_version integer,
        drug_generic_name     text,
        dose_value            text,
        dose_unit             text,
        route                 text,
        frequency             text,
        quantity              text,
        quantity_unit         text,
        refills               integer CHECK (refills >= 0),
        instructions          text,
        prescriber_subject    text NOT NULL,
        created_by            uuid NOT NULL,
        created_at            timestamptz NOT NULL DEFAULT now(),
        updated_at            timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY (organization_id, id),
        FOREIGN KEY (organization_id, created_by)
            REFERENCES public.user_identity(organization_id, id)
    )$ddl$;
    EXECUTE $ddl$CREATE INDEX prescription_draft_patient_idx ON public.prescription_draft
        (organization_id, patient_id, created_at DESC)$ddl$;

    EXECUTE $ddl$CREATE TABLE public.prescription_override (
        organization_id       uuid NOT NULL REFERENCES public.organization(organization_id),
        id                    uuid NOT NULL DEFAULT gen_random_uuid(),
        prescription_draft_id uuid NOT NULL,
        draft_version         integer NOT NULL CHECK (draft_version > 0),
        override_reason       text NOT NULL CHECK (length(trim(override_reason)) > 0),
        matched_snapshot      text NOT NULL CHECK (length(trim(matched_snapshot)) > 0),
        author_subject        text NOT NULL,
        created_at            timestamptz NOT NULL DEFAULT clock_timestamp(),
        PRIMARY KEY (organization_id, id),
        FOREIGN KEY (organization_id, prescription_draft_id)
            REFERENCES public.prescription_draft(organization_id, id)
    )$ddl$;
    EXECUTE $ddl$CREATE INDEX prescription_override_draft_idx ON public.prescription_override
        (organization_id, prescription_draft_id, created_at, id)$ddl$;

    -- The chart module (V7) may not exist yet in the offline glob runner.
    IF to_regclass('public.patient_record') IS NOT NULL THEN
        EXECUTE $ddl$ALTER TABLE public.prescription_draft
            ADD CONSTRAINT prescription_draft_patient_fk
            FOREIGN KEY (organization_id, patient_id)
            REFERENCES public.patient_record(organization_id, id)$ddl$;
    END IF;

    EXECUTE $ddl$DO $rls$
    DECLARE t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY['drug_catalog_entry', 'patient_allergy_status',
                                  'patient_allergy', 'prescription_draft',
                                  'prescription_override']
        LOOP
            EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
            EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
            EXECUTE format(
                $p$CREATE POLICY tenant_isolation ON public.%I
                     USING (organization_id = public.app_org())
                     WITH CHECK (organization_id = public.app_org())$p$, t);
        END LOOP;
    END $rls$$ddl$;

    -- Self-contained immutability helper: V43 can run before V4 in offline glob order.
    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.reject_rx_immutable_mutation()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    BEGIN
        IF TG_OP = 'TRUNCATE' THEN
            RAISE EXCEPTION '% is append-only (TRUNCATE)', TG_TABLE_NAME USING ERRCODE = '44000';
        END IF;
        RAISE EXCEPTION '% is append-only (%; organization_id=%)',
            TG_TABLE_NAME, TG_OP, OLD.organization_id USING ERRCODE = '44000';
    END $fn$$ddl$;
    EXECUTE $ddl$DO $imm$
    DECLARE t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY['drug_catalog_entry', 'patient_allergy_status',
                                  'patient_allergy', 'prescription_override']
        LOOP
            EXECUTE format($f$CREATE TRIGGER %I_no_update_delete
                BEFORE UPDATE OR DELETE ON public.%I
                FOR EACH ROW EXECUTE FUNCTION public.reject_rx_immutable_mutation()$f$, t, t);
            EXECUTE format($f$CREATE TRIGGER %I_no_truncate
                BEFORE TRUNCATE ON public.%I
                FOR EACH STATEMENT EXECUTE FUNCTION public.reject_rx_immutable_mutation()$f$, t, t);
        END LOOP;
        EXECUTE $f$CREATE TRIGGER prescription_draft_no_delete
            BEFORE DELETE ON public.prescription_draft
            FOR EACH ROW EXECUTE FUNCTION public.reject_rx_immutable_mutation()$f$;
        EXECUTE $f$CREATE TRIGGER prescription_draft_no_truncate
            BEFORE TRUNCATE ON public.prescription_draft
            FOR EACH STATEMENT EXECUTE FUNCTION public.reject_rx_immutable_mutation()$f$;
    END $imm$$ddl$;

    -- Authorship and prescribing identity are always the authenticated actor.
    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.set_rx_catalog_recorder_from_actor()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    DECLARE v_actor uuid;
    BEGIN
        SELECT id INTO v_actor FROM public.user_identity
         WHERE organization_id = NEW.organization_id
           AND subject = NULLIF(current_setting('app.actor', true), '');
        IF v_actor IS NULL THEN
            RAISE EXCEPTION 'drug catalog recorder must be an authenticated user of the active tenant'
                USING ERRCODE = '42501';
        END IF;
        NEW.recorded_by := v_actor;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER drug_catalog_entry_recorder
        BEFORE INSERT ON public.drug_catalog_entry
        FOR EACH ROW EXECUTE FUNCTION public.set_rx_catalog_recorder_from_actor()$ddl$;

    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.set_rx_allergy_status_recorder_from_actor()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    DECLARE v_actor uuid;
    BEGIN
        SELECT id INTO v_actor FROM public.user_identity
         WHERE organization_id = NEW.organization_id
           AND subject = NULLIF(current_setting('app.actor', true), '');
        IF v_actor IS NULL THEN
            RAISE EXCEPTION 'allergy status recorder must be an authenticated user of the active tenant'
                USING ERRCODE = '42501';
        END IF;
        NEW.recorded_by := v_actor;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER patient_allergy_status_recorder
        BEFORE INSERT ON public.patient_allergy_status
        FOR EACH ROW EXECUTE FUNCTION public.set_rx_allergy_status_recorder_from_actor()$ddl$;

    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.set_rx_allergy_recorder_from_actor()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    DECLARE v_actor uuid;
    BEGIN
        SELECT id INTO v_actor FROM public.user_identity
         WHERE organization_id = NEW.organization_id
           AND subject = NULLIF(current_setting('app.actor', true), '');
        IF v_actor IS NULL THEN
            RAISE EXCEPTION 'allergy fact recorder must be an authenticated user of the active tenant'
                USING ERRCODE = '42501';
        END IF;
        NEW.recorded_by := v_actor;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER patient_allergy_recorder
        BEFORE INSERT ON public.patient_allergy
        FOR EACH ROW EXECUTE FUNCTION public.set_rx_allergy_recorder_from_actor()$ddl$;

    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.set_rx_draft_author_from_actor()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    DECLARE v_actor uuid;
    BEGIN
        SELECT id INTO v_actor FROM public.user_identity
         WHERE organization_id = NEW.organization_id
           AND subject = NULLIF(current_setting('app.actor', true), '');
        IF v_actor IS NULL THEN
            RAISE EXCEPTION 'a prescription draft needs an authenticated author of the active tenant'
                USING ERRCODE = '42501';
        END IF;
        NEW.created_by := v_actor;
        NEW.prescriber_subject := NULLIF(current_setting('app.actor', true), '');
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER prescription_draft_author
        BEFORE INSERT ON public.prescription_draft
        FOR EACH ROW EXECUTE FUNCTION public.set_rx_draft_author_from_actor()$ddl$;

    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.set_rx_override_author_from_actor()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    BEGIN
        NEW.author_subject := NULLIF(current_setting('app.actor', true), '');
        IF NEW.author_subject IS NULL THEN
            RAISE EXCEPTION 'an override needs an authenticated author of the active tenant'
                USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER prescription_override_author
        BEFORE INSERT ON public.prescription_override
        FOR EACH ROW EXECUTE FUNCTION public.set_rx_override_author_from_actor()$ddl$;

    -- Database half of the If-Match precondition: ownership and state are pinned
    -- and the version advances by exactly one under concurrent updates.
    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.guard_rx_draft_update()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    BEGIN
        IF NEW.id <> OLD.id
           OR NEW.organization_id <> OLD.organization_id
           OR NEW.patient_id <> OLD.patient_id
           OR NEW.prescriber_subject <> OLD.prescriber_subject
           OR NEW.encounter_reference IS DISTINCT FROM OLD.encounter_reference
           OR NEW.destination IS DISTINCT FROM OLD.destination
           OR NEW.created_by <> OLD.created_by
           OR NEW.created_at <> OLD.created_at
           OR NEW.state <> OLD.state THEN
            RAISE EXCEPTION 'prescription draft ownership columns are immutable'
                USING ERRCODE = '42501';
        END IF;
        IF NEW.version <> OLD.version + 1 THEN
            RAISE EXCEPTION 'prescription draft version must advance by exactly one'
                USING ERRCODE = '23514';
        END IF;
        NEW.updated_at := clock_timestamp();
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER prescription_draft_update_guard
        BEFORE UPDATE ON public.prescription_draft
        FOR EACH ROW EXECUTE FUNCTION public.guard_rx_draft_update()$ddl$;

    EXECUTE $ddl$GRANT SELECT, INSERT ON public.drug_catalog_entry TO aurevia_app$ddl$;
    EXECUTE $ddl$REVOKE UPDATE, DELETE, TRUNCATE ON public.drug_catalog_entry FROM aurevia_app$ddl$;
    EXECUTE $ddl$GRANT SELECT, INSERT ON public.patient_allergy_status TO aurevia_app$ddl$;
    EXECUTE $ddl$REVOKE UPDATE, DELETE, TRUNCATE ON public.patient_allergy_status FROM aurevia_app$ddl$;
    EXECUTE $ddl$GRANT SELECT, INSERT ON public.patient_allergy TO aurevia_app$ddl$;
    EXECUTE $ddl$REVOKE UPDATE, DELETE, TRUNCATE ON public.patient_allergy FROM aurevia_app$ddl$;
    EXECUTE $ddl$GRANT SELECT, INSERT, UPDATE ON public.prescription_draft TO aurevia_app$ddl$;
    EXECUTE $ddl$REVOKE DELETE, TRUNCATE ON public.prescription_draft FROM aurevia_app$ddl$;
    EXECUTE $ddl$GRANT SELECT, INSERT ON public.prescription_override TO aurevia_app$ddl$;
    EXECUTE $ddl$REVOKE UPDATE, DELETE, TRUNCATE ON public.prescription_override FROM aurevia_app$ddl$;

    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.reject_rx_immutable_mutation() FROM PUBLIC$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.set_rx_catalog_recorder_from_actor() FROM PUBLIC$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.set_rx_allergy_status_recorder_from_actor() FROM PUBLIC$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.set_rx_allergy_recorder_from_actor() FROM PUBLIC$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.set_rx_draft_author_from_actor() FROM PUBLIC$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.set_rx_override_author_from_actor() FROM PUBLIC$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.guard_rx_draft_update() FROM PUBLIC$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.reject_rx_immutable_mutation() TO aurevia_app$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.set_rx_catalog_recorder_from_actor() TO aurevia_app$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.set_rx_allergy_status_recorder_from_actor() TO aurevia_app$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.set_rx_allergy_recorder_from_actor() TO aurevia_app$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.set_rx_draft_author_from_actor() TO aurevia_app$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.set_rx_override_author_from_actor() TO aurevia_app$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.guard_rx_draft_update() TO aurevia_app$ddl$;
END $rx$;
