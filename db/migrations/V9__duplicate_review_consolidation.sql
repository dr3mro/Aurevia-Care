-- Requirement IDs: IAM-04, ARC-01, ARC-02, ARC-03, OPS-02
-- Change: expand
-- Same-tenant duplicate review and all-or-nothing chart consolidation.
-- Every table below is append-only and tenant-isolated with forced RLS, matching
-- the V5/V7 pattern. Chart visibility moves through a versioned association; the
-- original patient rows, identifiers, signatures and source references remain in
-- the append-only patient table and are snapshotted on the association. A second
-- authorized reviewer is recorded on the event. Ambiguous orders, patient-account
-- links and grants are represented as dependencies that block consolidation.

CREATE TABLE public.duplicate_review (
    organization_id   uuid NOT NULL REFERENCES public.organization(organization_id),
    id                uuid NOT NULL,
    survivor_id       uuid NOT NULL,
    duplicate_id      uuid NOT NULL,
    opened_by_subject text NOT NULL,
    version           integer NOT NULL DEFAULT 1 CHECK (version = 1),
    created_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, survivor_id, duplicate_id),
    FOREIGN KEY (organization_id, survivor_id)
        REFERENCES public.patient_record(organization_id, id),
    FOREIGN KEY (organization_id, duplicate_id)
        REFERENCES public.patient_record(organization_id, id)
);

CREATE TABLE public.chart_association (
    organization_id       uuid NOT NULL REFERENCES public.organization(organization_id),
    id                    uuid NOT NULL,
    alias_id              uuid NOT NULL,
    alias_version         integer NOT NULL CHECK (alias_version > 0),
    survivor_id           uuid NOT NULL,
    version               integer NOT NULL CHECK (version > 0),
    status                text NOT NULL CHECK (status IN ('ACTIVE', 'REVERSED')),
    preserved_mrn         text NOT NULL,
    preserved_issuer      text NOT NULL,
    preserved_identifiers jsonb NOT NULL DEFAULT '[]'::jsonb,
    preserved_consents    jsonb NOT NULL DEFAULT '[]'::jsonb,
    reason                text NOT NULL,
    created_by            uuid NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, alias_id, version),
    FOREIGN KEY (organization_id, alias_id)
        REFERENCES public.patient_record(organization_id, id),
    FOREIGN KEY (organization_id, survivor_id)
        REFERENCES public.patient_record(organization_id, id),
    FOREIGN KEY (organization_id, created_by)
        REFERENCES public.user_identity(organization_id, id)
);

CREATE TABLE public.consolidation (
    organization_id   uuid NOT NULL REFERENCES public.organization(organization_id),
    id                uuid NOT NULL,
    review_id         uuid NOT NULL,
    association_id    uuid NOT NULL,
    survivor_id       uuid NOT NULL,
    duplicate_id      uuid NOT NULL,
    version           integer NOT NULL CHECK (version > 0),
    status            text NOT NULL CHECK (status IN ('CONSOLIDATED', 'CORRECTED')),
    approver_subject  text NOT NULL,
    opened_by_subject text NOT NULL,
    reason            text NOT NULL,
    correction_reason text,
    created_by        uuid NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, review_id, version),
    FOREIGN KEY (organization_id, review_id)
        REFERENCES public.duplicate_review(organization_id, id),
    FOREIGN KEY (organization_id, association_id)
        REFERENCES public.chart_association(organization_id, id),
    FOREIGN KEY (organization_id, survivor_id)
        REFERENCES public.patient_record(organization_id, id),
    FOREIGN KEY (organization_id, duplicate_id)
        REFERENCES public.patient_record(organization_id, id),
    FOREIGN KEY (organization_id, created_by)
        REFERENCES public.user_identity(organization_id, id)
);

CREATE TABLE public.consolidation_dependency (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    patient_id      uuid NOT NULL,
    kind            text NOT NULL CHECK (kind IN ('ORDER', 'PATIENT_LINK', 'GRANT')),
    reference       text NOT NULL,
    ambiguous       boolean NOT NULL,
    resolved        boolean NOT NULL DEFAULT false,
    note            text,
    created_by      uuid NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    FOREIGN KEY (organization_id, patient_id)
        REFERENCES public.patient_record(organization_id, id),
    FOREIGN KEY (organization_id, created_by)
        REFERENCES public.user_identity(organization_id, id)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['duplicate_review', 'chart_association',
                              'consolidation', 'consolidation_dependency']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            $p$CREATE POLICY tenant_isolation ON public.%I
                 USING (organization_id = public.app_org())
                 WITH CHECK (organization_id = public.app_org())$p$, t);
        EXECUTE format($d$CREATE TRIGGER %I_no_update_delete
            BEFORE UPDATE OR DELETE ON public.%I
            FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
        EXECUTE format($d$CREATE TRIGGER %I_no_truncate
            BEFORE TRUNCATE ON public.%I
            FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
    END LOOP;
END $$;

-- The author is the authenticated actor of the active tenant, never the payload.
CREATE OR REPLACE FUNCTION public.set_duplicate_created_by_from_actor()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_actor uuid;
BEGIN
    SELECT id INTO v_actor FROM public.user_identity
     WHERE organization_id = NEW.organization_id
       AND subject = NULLIF(current_setting('app.actor', true), '');
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'identity administration requires an authenticated user of the active tenant'
            USING ERRCODE = '42501';
    END IF;
    NEW.created_by := v_actor;
    RETURN NEW;
END $$;
CREATE TRIGGER chart_association_created_by_actor
    BEFORE INSERT ON public.chart_association
    FOR EACH ROW EXECUTE FUNCTION public.set_duplicate_created_by_from_actor();
CREATE TRIGGER consolidation_created_by_actor
    BEFORE INSERT ON public.consolidation
    FOR EACH ROW EXECUTE FUNCTION public.set_duplicate_created_by_from_actor();
CREATE TRIGGER consolidation_dependency_created_by_actor
    BEFORE INSERT ON public.consolidation_dependency
    FOR EACH ROW EXECUTE FUNCTION public.set_duplicate_created_by_from_actor();

-- Version allocation is trigger-free but database-owned and serialized per alias
-- or review so two concurrent approvals cannot reuse a version.
CREATE OR REPLACE FUNCTION public.next_chart_association_version(p_alias uuid)
RETURNS integer LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_org uuid := public.app_org();
BEGIN
    IF v_org IS NULL THEN
        RAISE EXCEPTION 'tenant context required' USING ERRCODE = '42501';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(v_org::text || ':assoc:' || p_alias::text, 0));
    RETURN coalesce(max(version), 0) + 1 FROM public.chart_association WHERE alias_id = p_alias;
END $$;

CREATE OR REPLACE FUNCTION public.next_consolidation_version(p_review uuid)
RETURNS integer LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
DECLARE v_org uuid := public.app_org();
BEGIN
    IF v_org IS NULL THEN
        RAISE EXCEPTION 'tenant context required' USING ERRCODE = '42501';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(v_org::text || ':consolidation:' || p_review::text, 0));
    RETURN coalesce(max(version), 0) + 1 FROM public.consolidation WHERE review_id = p_review;
END $$;

-- Lock both patient records inside the caller's transaction. The advisory key is
-- ordered so concurrent approvals of the same pair cannot deadlock.
-- SECURITY DEFINER is required because SELECT ... FOR UPDATE needs UPDATE
-- privilege, which the runtime role deliberately does not have on patient_record.
-- FORCE RLS still applies to the owner, and app.current_org is bound by the caller.
CREATE OR REPLACE FUNCTION public.lock_patient_pair(p_survivor uuid, p_duplicate uuid)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog
AS $$
DECLARE v_org uuid := public.app_org();
        v_first uuid;
        v_second uuid;
        v_locked integer;
BEGIN
    IF v_org IS NULL THEN
        RAISE EXCEPTION 'tenant context required' USING ERRCODE = '42501';
    END IF;
    v_first := least(p_survivor, p_duplicate);
    v_second := greatest(p_survivor, p_duplicate);
    PERFORM pg_advisory_xact_lock(
        hashtextextended(v_org::text || ':pair:' || v_first::text || ':' || v_second::text, 0));
    WITH locked_rows AS (
        SELECT id FROM public.patient_record
         WHERE id IN (p_survivor, p_duplicate)
         FOR UPDATE
    )
    SELECT count(DISTINCT id) INTO v_locked FROM locked_rows;
    RETURN v_locked;
END $$;

-- The latest association version decides visibility. A reversal restores the
-- alias id; original rows are never updated or removed.
CREATE OR REPLACE FUNCTION public.effective_patient_id(p_id uuid)
RETURNS uuid LANGUAGE sql STABLE SET search_path = pg_catalog
AS $$
    SELECT coalesce(
        (SELECT CASE WHEN a.status = 'ACTIVE' THEN a.survivor_id ELSE p_id END
           FROM public.chart_association a
          WHERE a.alias_id = p_id
          ORDER BY a.version DESC
          LIMIT 1),
        p_id)
$$;

-- Latest effective version per alias/review, used by reads and the worklist.
CREATE OR REPLACE FUNCTION public.current_chart_associations()
RETURNS TABLE (organization_id uuid, id uuid, alias_id uuid, alias_version integer,
               survivor_id uuid, version integer, status text, preserved_mrn text,
               preserved_issuer text, preserved_identifiers jsonb, preserved_consents jsonb,
               reason text, created_by uuid, created_at timestamptz)
LANGUAGE sql STABLE SET search_path = pg_catalog
AS $$
    SELECT DISTINCT ON (a.organization_id, a.alias_id)
           a.organization_id, a.id, a.alias_id, a.alias_version, a.survivor_id, a.version,
           a.status, a.preserved_mrn, a.preserved_issuer, a.preserved_identifiers,
           a.preserved_consents, a.reason, a.created_by, a.created_at
      FROM public.chart_association a
     ORDER BY a.organization_id, a.alias_id, a.version DESC
$$;

CREATE OR REPLACE FUNCTION public.current_consolidations()
RETURNS TABLE (organization_id uuid, id uuid, review_id uuid, association_id uuid,
               survivor_id uuid, duplicate_id uuid, version integer, status text,
               approver_subject text, opened_by_subject text, reason text,
               correction_reason text, created_by uuid, created_at timestamptz)
LANGUAGE sql STABLE SET search_path = pg_catalog
AS $$
    SELECT DISTINCT ON (c.organization_id, c.review_id)
           c.organization_id, c.id, c.review_id, c.association_id, c.survivor_id, c.duplicate_id,
           c.version, c.status, c.approver_subject, c.opened_by_subject, c.reason,
           c.correction_reason, c.created_by, c.created_at
      FROM public.consolidation c
     ORDER BY c.organization_id, c.review_id, c.version DESC
$$;

REVOKE ALL ON FUNCTION public.set_duplicate_created_by_from_actor() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.next_chart_association_version(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.next_consolidation_version(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.lock_patient_pair(uuid, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.effective_patient_id(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.current_chart_associations() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.current_consolidations() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.next_chart_association_version(uuid) TO orevia_app;
GRANT EXECUTE ON FUNCTION public.next_consolidation_version(uuid) TO orevia_app;
GRANT EXECUTE ON FUNCTION public.lock_patient_pair(uuid, uuid) TO orevia_app;
GRANT EXECUTE ON FUNCTION public.effective_patient_id(uuid) TO orevia_app;
GRANT EXECUTE ON FUNCTION public.current_chart_associations() TO orevia_app;
GRANT EXECUTE ON FUNCTION public.current_consolidations() TO orevia_app;

GRANT SELECT, INSERT ON public.duplicate_review, public.chart_association,
                       public.consolidation, public.consolidation_dependency TO orevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.duplicate_review, public.chart_association,
                                 public.consolidation, public.consolidation_dependency FROM orevia_app;
