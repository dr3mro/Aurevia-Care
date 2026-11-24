-- Requirement IDs: PT-01, ARC-01, ARC-02, OPS-02
-- Change: expand
-- Physical-therapy episode foundation for PT-01. A referral is accepted into a
-- tenant-owned episode; an evaluation records problems, baseline measurements
-- with explicit units, goals and a planned frequency/duration; versioned outcome
-- scales carry their unit and permitted values; readings bind the exact approved
-- scale revision and unit; interventions and session/progress notes are recorded;
-- a discharge note can be signed only after a signed session note; an approved
-- summary is sent to the verified referrer and a revision-bound acknowledgment is
-- recorded. Every clinical fact is append-only once signed or approved, so a new
-- scale version can never rewrite an old score.
--
-- All tables are tenant-owned: organization_id is part of every key and of every
-- foreign key, so identical local identifiers in two tenants stay isolated. Each
-- table enables and forces row-level security with the public.app_org() policy;
-- new tables are not covered by V2's static list, so policy, grants and triggers
-- are declared here. The migration is additive only.

CREATE TABLE public.therapy_episode (
    organization_id   uuid NOT NULL REFERENCES public.organization(organization_id),
    id                uuid NOT NULL DEFAULT gen_random_uuid(),
    version           integer NOT NULL DEFAULT 1 CHECK (version > 0),
    patient_id        uuid NOT NULL,
    referral_id       text NOT NULL CHECK (char_length(referral_id) BETWEEN 1 AND 128),
    referrer_org      text NOT NULL CHECK (char_length(referrer_org) BETWEEN 1 AND 128),
    referrer_subject  text NOT NULL CHECK (char_length(referrer_subject) BETWEEN 1 AND 128),
    state             text NOT NULL
        CHECK (state IN ('active', 'in_treatment', 'discharged')),
    created_at        timestamptz NOT NULL DEFAULT now(),
    received_at       text NOT NULL CHECK (char_length(received_at) BETWEEN 1 AND 64),
    clinical_question text NOT NULL CHECK (char_length(clinical_question) BETWEEN 1 AND 500),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, referral_id),
    FOREIGN KEY (organization_id, patient_id)
        REFERENCES public.patient_record(organization_id, id)
);

CREATE TABLE public.therapy_evaluation (
    organization_id   uuid NOT NULL,
    episode_id        uuid NOT NULL,
    version           integer NOT NULL DEFAULT 1 CHECK (version > 0),
    author            text NOT NULL,
    recorded_at       timestamptz NOT NULL DEFAULT now(),
    frequency_per_week integer NOT NULL CHECK (frequency_per_week > 0),
    duration_weeks    integer NOT NULL CHECK (duration_weeks > 0),
    created_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, episode_id),
    FOREIGN KEY (organization_id, episode_id)
        REFERENCES public.therapy_episode(organization_id, id)
);

-- Baseline measurements keep their unit beside the value; a missing unit is not
-- representable. One row per measurement code in the evaluation.
CREATE TABLE public.therapy_baseline_measurement (
    organization_id uuid NOT NULL,
    episode_id      uuid NOT NULL,
    code            text NOT NULL CHECK (char_length(code) BETWEEN 1 AND 128),
    value           text NOT NULL CHECK (char_length(value) BETWEEN 1 AND 64),
    unit            text NOT NULL CHECK (char_length(unit) BETWEEN 1 AND 32),
    observed_at     text NOT NULL CHECK (char_length(observed_at) BETWEEN 1 AND 64),
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, episode_id, code),
    FOREIGN KEY (organization_id, episode_id)
        REFERENCES public.therapy_evaluation(organization_id, episode_id)
);

CREATE TABLE public.therapy_goal (
    organization_id uuid NOT NULL,
    episode_id      uuid NOT NULL,
    ordinal         integer NOT NULL CHECK (ordinal > 0),
    description     text NOT NULL CHECK (char_length(description) BETWEEN 1 AND 500),
    target          text NOT NULL,
    target_date     text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, episode_id, ordinal),
    FOREIGN KEY (organization_id, episode_id)
        REFERENCES public.therapy_evaluation(organization_id, episode_id)
);

-- A scale version is immutable: its key, version, unit and permitted values are
-- a fixed definition. Approval flips approved from false to true once. Defining a
-- new version inserts a new row and never rewrites an existing score's definition.
CREATE TABLE public.therapy_outcome_scale (
    organization_id  uuid NOT NULL REFERENCES public.organization(organization_id),
    key              text NOT NULL CHECK (char_length(key) BETWEEN 1 AND 64),
    version          integer NOT NULL CHECK (version > 0),
    description      text NOT NULL CHECK (char_length(description) BETWEEN 1 AND 200),
    unit             text NOT NULL CHECK (char_length(unit) BETWEEN 1 AND 32),
    permitted_values jsonb NOT NULL,
    approved         boolean NOT NULL DEFAULT false,
    approved_by      text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, key, version),
    CHECK (jsonb_array_length(permitted_values) > 0),
    CHECK (approved OR approved_by IS NULL)
);

-- A reading carries the scale version and unit it actually used. The foreign key
-- and trigger bind it to that exact approved definition so a later version cannot
-- reinterpret the stored score.
CREATE TABLE public.therapy_outcome_reading (
    organization_id uuid NOT NULL,
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    episode_id      uuid NOT NULL,
    scale_key       text NOT NULL,
    scale_version   integer NOT NULL CHECK (scale_version > 0),
    value           text NOT NULL CHECK (char_length(value) BETWEEN 1 AND 64),
    unit            text NOT NULL CHECK (char_length(unit) BETWEEN 1 AND 32),
    observed_at     text NOT NULL CHECK (char_length(observed_at) BETWEEN 1 AND 64),
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    FOREIGN KEY (organization_id, episode_id)
        REFERENCES public.therapy_episode(organization_id, id),
    FOREIGN KEY (organization_id, scale_key, scale_version)
        REFERENCES public.therapy_outcome_scale(organization_id, key, version)
);

CREATE TABLE public.therapy_intervention (
    organization_id uuid NOT NULL,
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    episode_id      uuid NOT NULL,
    description     text NOT NULL CHECK (char_length(description) BETWEEN 1 AND 500),
    performed_at    text NOT NULL CHECK (char_length(performed_at) BETWEEN 1 AND 64),
    author          text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    FOREIGN KEY (organization_id, episode_id)
        REFERENCES public.therapy_episode(organization_id, id)
);

-- Session, progress and discharge notes share one immutable revision chain per
-- kind. A signed revision cannot be changed; signing requires the full attestation
-- set and a SHA-256 content checksum.
CREATE TABLE public.therapy_clinical_note (
    organization_id  uuid NOT NULL,
    episode_id       uuid NOT NULL,
    kind             text NOT NULL CHECK (kind IN ('session', 'progress', 'discharge')),
    version          integer NOT NULL CHECK (version > 0),
    patient_id       uuid NOT NULL,
    content          text NOT NULL,
    author           text NOT NULL,
    effective_at     text NOT NULL CHECK (char_length(effective_at) BETWEEN 1 AND 64),
    template_version integer,
    signed           boolean NOT NULL DEFAULT false,
    signed_by        text,
    signed_at        timestamptz,
    attestation      text,
    content_checksum text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, episode_id, kind, version),
    FOREIGN KEY (organization_id, episode_id)
        REFERENCES public.therapy_episode(organization_id, id),
    CHECK (NOT signed OR (signed_by IS NOT NULL AND signed_at IS NOT NULL
                          AND attestation IS NOT NULL AND content_checksum IS NOT NULL))
);

CREATE TABLE public.therapy_summary (
    organization_id uuid NOT NULL,
    episode_id      uuid NOT NULL,
    version         integer NOT NULL CHECK (version > 0),
    content         text NOT NULL,
    referrer_org    text NOT NULL,
    referrer_subject text NOT NULL,
    approved        boolean NOT NULL,
    approved_by     text NOT NULL,
    sent_at         timestamptz NOT NULL DEFAULT now(),
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, episode_id, version),
    FOREIGN KEY (organization_id, episode_id)
        REFERENCES public.therapy_episode(organization_id, id)
);

-- One acknowledgment per exact summary revision. A replacement summary is a new
-- version, so the old acknowledgment cannot vouch for new content.
CREATE TABLE public.therapy_return_acknowledgment (
    organization_id uuid NOT NULL,
    episode_id      uuid NOT NULL,
    summary_version integer NOT NULL CHECK (summary_version > 0),
    received_at     text NOT NULL CHECK (char_length(received_at) BETWEEN 1 AND 64),
    received_by     text NOT NULL,
    note            text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, episode_id, summary_version),
    FOREIGN KEY (organization_id, episode_id, summary_version)
        REFERENCES public.therapy_summary(organization_id, episode_id, version)
);

CREATE TABLE public.therapy_audit_event (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    seq             bigint GENERATED BY DEFAULT AS IDENTITY,
    actor_subject   text NOT NULL,
    action          text NOT NULL,
    resource_id     text NOT NULL,
    outcome         text NOT NULL,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, seq)
);

-- Force RLS and the tenant policy on every new table. Policies are per table,
-- so a future rename cannot silently drop one (ARC-02).
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'therapy_episode', 'therapy_evaluation', 'therapy_baseline_measurement',
        'therapy_goal', 'therapy_outcome_scale', 'therapy_outcome_reading',
        'therapy_intervention', 'therapy_clinical_note', 'therapy_summary',
        'therapy_return_acknowledgment', 'therapy_audit_event']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON public.%I
             USING (organization_id = public.app_org())
             WITH CHECK (organization_id = public.app_org())$p$, t);
    END LOOP;
END $$;

-- A scale definition never changes; only a pending version may be approved once.
CREATE OR REPLACE FUNCTION public.guard_therapy_outcome_scale()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog AS $fn$
BEGIN
    IF NEW.organization_id <> OLD.organization_id
       OR NEW.key <> OLD.key
       OR NEW.version <> OLD.version
       OR NEW.description <> OLD.description
       OR NEW.unit <> OLD.unit
       OR NEW.permitted_values <> OLD.permitted_values THEN
        RAISE EXCEPTION 'an outcome scale definition is immutable'
            USING ERRCODE = '42501';
    END IF;
    IF OLD.approved AND (NEW.approved IS DISTINCT FROM OLD.approved
                         OR NEW.approved_by IS DISTINCT FROM OLD.approved_by) THEN
        RAISE EXCEPTION 'an approved outcome scale cannot change approval'
            USING ERRCODE = '42501';
    END IF;
    IF NEW.approved AND NOT OLD.approved AND NEW.approved_by IS NULL THEN
        RAISE EXCEPTION 'an approved outcome scale needs an approver'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $fn$;

CREATE TRIGGER therapy_outcome_scale_guard
    BEFORE UPDATE ON public.therapy_outcome_scale
    FOR EACH ROW EXECUTE FUNCTION public.guard_therapy_outcome_scale();

-- A reading must bind the exact approved scale revision and its unit.
CREATE OR REPLACE FUNCTION public.guard_therapy_reading_scale()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog AS $fn$
DECLARE v_unit text; v_approved boolean;
BEGIN
    SELECT unit, approved INTO v_unit, v_approved
      FROM public.therapy_outcome_scale
     WHERE organization_id = NEW.organization_id
       AND key = NEW.scale_key AND version = NEW.scale_version;
    IF v_unit IS NULL OR NOT v_approved THEN
        RAISE EXCEPTION 'a reading must bind an approved outcome scale version'
            USING ERRCODE = '23514';
    END IF;
    IF v_unit <> NEW.unit THEN
        RAISE EXCEPTION 'a reading unit must match its scale version unit'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $fn$;

CREATE TRIGGER therapy_outcome_reading_scale_guard
    BEFORE INSERT ON public.therapy_outcome_reading
    FOR EACH ROW EXECUTE FUNCTION public.guard_therapy_reading_scale();

-- A signed note is immutable. Draft notes may still be updated or removed.
CREATE OR REPLACE FUNCTION public.guard_therapy_signed_note()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog AS $fn$
BEGIN
    IF OLD.signed THEN
        RAISE EXCEPTION 'a signed therapy note is immutable'
            USING ERRCODE = '42501';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END $fn$;

CREATE TRIGGER therapy_clinical_note_signed_guard
    BEFORE UPDATE OR DELETE ON public.therapy_clinical_note
    FOR EACH ROW EXECUTE FUNCTION public.guard_therapy_signed_note();

-- A discharge note may be signed only after a signed session note exists.
CREATE OR REPLACE FUNCTION public.guard_therapy_discharge_signature()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog AS $fn$
BEGIN
    IF NEW.kind = 'discharge' AND NEW.signed AND NOT OLD.signed THEN
        IF NOT EXISTS (
            SELECT 1 FROM public.therapy_clinical_note n
             WHERE n.organization_id = NEW.organization_id
               AND n.episode_id = NEW.episode_id
               AND n.kind <> 'discharge'
               AND n.signed) THEN
            RAISE EXCEPTION 'discharge requires a signed session note'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END $fn$;

CREATE TRIGGER therapy_clinical_note_discharge_guard
    BEFORE UPDATE ON public.therapy_clinical_note
    FOR EACH ROW EXECUTE FUNCTION public.guard_therapy_discharge_signature();

-- Append-only clinical facts: no UPDATE, DELETE or TRUNCATE for any role.
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'therapy_baseline_measurement', 'therapy_goal', 'therapy_outcome_reading',
        'therapy_intervention', 'therapy_summary', 'therapy_return_acknowledgment',
        'therapy_audit_event']
    LOOP
        EXECUTE format('CREATE TRIGGER %I_no_update_delete BEFORE UPDATE OR DELETE ON public.%I '
                       'FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()', t, t);
        EXECUTE format('CREATE TRIGGER %I_no_truncate BEFORE TRUNCATE ON public.%I '
                       'FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()', t, t);
    END LOOP;
END $$;

-- The runtime role reads clinical state and appends facts. Episodes, evaluations
-- and draft notes advance by UPDATE; a signed note is frozen by the trigger above.
GRANT SELECT, INSERT, UPDATE ON public.therapy_episode TO aurevia_app;
GRANT SELECT, INSERT, UPDATE ON public.therapy_evaluation TO aurevia_app;
GRANT SELECT, INSERT, UPDATE ON public.therapy_outcome_scale TO aurevia_app;
GRANT SELECT, INSERT, UPDATE ON public.therapy_clinical_note TO aurevia_app;
GRANT SELECT, INSERT ON public.therapy_baseline_measurement TO aurevia_app;
GRANT SELECT, INSERT ON public.therapy_goal TO aurevia_app;
GRANT SELECT, INSERT ON public.therapy_outcome_reading TO aurevia_app;
GRANT SELECT, INSERT ON public.therapy_intervention TO aurevia_app;
GRANT SELECT, INSERT ON public.therapy_summary TO aurevia_app;
GRANT SELECT, INSERT ON public.therapy_return_acknowledgment TO aurevia_app;
GRANT SELECT, INSERT ON public.therapy_audit_event TO aurevia_app;

REVOKE DELETE, TRUNCATE ON public.therapy_episode FROM aurevia_app;
REVOKE DELETE, TRUNCATE ON public.therapy_evaluation FROM aurevia_app;
REVOKE DELETE, TRUNCATE ON public.therapy_outcome_scale FROM aurevia_app;
REVOKE DELETE, TRUNCATE ON public.therapy_clinical_note FROM aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.therapy_baseline_measurement FROM aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.therapy_goal FROM aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.therapy_outcome_reading FROM aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.therapy_intervention FROM aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.therapy_summary FROM aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.therapy_return_acknowledgment FROM aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.therapy_audit_event FROM aurevia_app;

REVOKE ALL ON FUNCTION public.guard_therapy_outcome_scale() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_therapy_reading_scale() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_therapy_signed_note() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.guard_therapy_discharge_signature() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.guard_therapy_outcome_scale() TO aurevia_app;
GRANT EXECUTE ON FUNCTION public.guard_therapy_reading_scale() TO aurevia_app;
GRANT EXECUTE ON FUNCTION public.guard_therapy_signed_note() TO aurevia_app;
GRANT EXECUTE ON FUNCTION public.guard_therapy_discharge_signature() TO aurevia_app;
