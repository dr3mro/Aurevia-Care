-- Requirement IDs: PRD-06, IAM-01, IAM-02, ARC-01, ARC-02, OPS-02
-- Change: expand
-- Specialty role attestation ledger for PRD-06. One row is one bounded,
-- human-authored attestation of a clinical action by a specialty role (sign,
-- verify, release, review or accept). Rows are immutable facts: a replacement
-- is a new row, never an UPDATE of an earlier one. Authorship is derived from
-- the authenticated actor of the active tenant by the insert trigger, so an
-- application caller cannot attribute an attestation to another user.
-- No existing object is altered; forced RLS from V2 and the append-only
-- trigger semantics from V4 are reused, not redefined.
--
-- Compatibility note: db/check.sh applies V*.sql in shell glob order, which
-- places V46 before V4 and V7. Flyway applies by numeric version and is the
-- authority. The guard below makes an out-of-order offline apply a safe no-op;
-- it never skips a Flyway apply, because Flyway installs prerequisites first.

DO $specialty_attestation$
BEGIN
    IF to_regclass('public.organization') IS NULL
       OR to_regclass('public.user_identity') IS NULL
       OR to_regclass('public.patient_record') IS NULL
       OR NOT EXISTS (
              SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'app_org')
       OR NOT EXISTS (
              SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = 'public' AND p.proname = 'reject_immutable_mutation')
       OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'aurevia_app') THEN
        RAISE NOTICE 'V46 specialty attestation deferred: prerequisites not installed yet';
        RETURN;
    END IF;

    EXECUTE $ddl$CREATE TABLE public.specialty_attestation (
        organization_id  uuid NOT NULL REFERENCES public.organization(organization_id),
        id               uuid NOT NULL DEFAULT gen_random_uuid(),
        version          integer NOT NULL DEFAULT 1 CHECK (version > 0),
        patient_id       uuid NOT NULL,
        action_key       text NOT NULL,
        role_key         text NOT NULL,
        subject          text NOT NULL,
        actor_id         uuid NOT NULL,
        decision         text NOT NULL
            CHECK (decision IN ('DIAGNOSIS', 'TREATMENT', 'RELEASE')),
        attested_revision integer NOT NULL CHECK (attested_revision > 0),
        attested_text    text NOT NULL,
        resource_ref     text NOT NULL,
        created_at       timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY (organization_id, id),
        FOREIGN KEY (organization_id, patient_id)
            REFERENCES public.patient_record(organization_id, id),
        FOREIGN KEY (organization_id, actor_id)
            REFERENCES public.user_identity(organization_id, id)
    )$ddl$;

    EXECUTE $ddl$CREATE INDEX specialty_attestation_evidence_idx
        ON public.specialty_attestation
        (organization_id, action_key, role_key, created_at DESC)$ddl$;

    EXECUTE $ddl$ALTER TABLE public.specialty_attestation ENABLE ROW LEVEL SECURITY$ddl$;
    EXECUTE $ddl$ALTER TABLE public.specialty_attestation FORCE ROW LEVEL SECURITY$ddl$;
    EXECUTE $p$CREATE POLICY tenant_isolation ON public.specialty_attestation
         USING (organization_id = public.app_org())
         WITH CHECK (organization_id = public.app_org())$p$;

    -- Immutable for every role, including the migration owner.
    EXECUTE $ddl$CREATE TRIGGER specialty_attestation_no_update_delete
        BEFORE UPDATE OR DELETE ON public.specialty_attestation
        FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER specialty_attestation_no_truncate
        BEFORE TRUNCATE ON public.specialty_attestation
        FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;

    -- Authorship is always the authenticated actor of the active tenant. The
    -- trigger overwrites both actor_id and subject, so caller input is never
    -- authoritative for who attested or under which identity.
    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.set_specialty_attestation_actor_from_actor()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    DECLARE v_actor uuid;
    BEGIN
        SELECT id INTO v_actor FROM public.user_identity
         WHERE organization_id = NEW.organization_id
           AND subject = NULLIF(current_setting('app.actor', true), '');
        IF v_actor IS NULL THEN
            RAISE EXCEPTION 'specialty attestation requires an authenticated user of the active tenant'
                USING ERRCODE = '42501';
        END IF;
        NEW.actor_id := v_actor;
        NEW.subject  := NULLIF(current_setting('app.actor', true), '');
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER specialty_attestation_actor
        BEFORE INSERT ON public.specialty_attestation
        FOR EACH ROW EXECUTE FUNCTION public.set_specialty_attestation_actor_from_actor()$ddl$;

    EXECUTE $ddl$GRANT SELECT, INSERT ON public.specialty_attestation TO aurevia_app$ddl$;
    EXECUTE $ddl$REVOKE UPDATE, DELETE, TRUNCATE ON public.specialty_attestation FROM aurevia_app$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.set_specialty_attestation_actor_from_actor() FROM PUBLIC$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.set_specialty_attestation_actor_from_actor()
        TO aurevia_app$ddl$;
END $specialty_attestation$;
