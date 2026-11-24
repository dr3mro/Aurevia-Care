-- Requirement IDs: CLN-01, ARC-01, ARC-02, OPS-02
-- Change: expand
--
-- Durable recurrence templates and the series index for CLN-01. A template
-- records the civil pattern (interval, count or local end date, local clock time
-- and IANA zone) that produced a series; occurrences keep the original planned
-- start even when an appointment is later edited, so the change history stays
-- reviewable. Nothing is dropped or rewritten: later migrations may add columns.
--
-- Every table carries organization_id and gets ENABLE + FORCE RLS with an
-- app_org() policy, exactly like the V8 scheduling tables. The rows are
-- append-only for every role, including the migration owner: edit history lives
-- in appointment_event (V8), not by mutating these rows. Grants are to the
-- non-owner runtime role only.
--
-- Lock and volume impact: three small new tables, no backfill, no update of
-- existing rows; the brief ACCESS EXCLUSIVE locks are bounded by table creation.

CREATE TABLE public.recurrence_template (
    organization_id  uuid NOT NULL REFERENCES public.organization(organization_id),
    id               uuid NOT NULL DEFAULT gen_random_uuid(),
    interval_weeks   smallint NOT NULL CHECK (interval_weeks BETWEEN 1 AND 4),
    occurrence_count integer CHECK (occurrence_count BETWEEN 1 AND 52),
    end_local_date   date,
    local_start_time time NOT NULL,
    time_zone        text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    -- Exactly one termination: a fixed count or an inclusive local end date.
    CHECK ((occurrence_count IS NULL) <> (end_local_date IS NULL))
);

CREATE TABLE public.appointment_series (
    organization_id       uuid NOT NULL REFERENCES public.organization(organization_id),
    id                    uuid NOT NULL DEFAULT gen_random_uuid(),
    template_id           uuid NOT NULL,
    patient_id            uuid NOT NULL,
    facility_id           uuid NOT NULL,
    clinician_resource_id uuid NOT NULL,
    room_resource_id      uuid,
    visit_type            text NOT NULL,
    first_starts_at       timestamptz NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, template_id),
    FOREIGN KEY (organization_id, template_id)
        REFERENCES public.recurrence_template(organization_id, id),
    FOREIGN KEY (organization_id, patient_id)
        REFERENCES public.patient_record(organization_id, id),
    FOREIGN KEY (organization_id, facility_id)
        REFERENCES public.facility(organization_id, id),
    FOREIGN KEY (organization_id, clinician_resource_id)
        REFERENCES public.scheduling_resource(organization_id, id),
    FOREIGN KEY (organization_id, room_resource_id)
        REFERENCES public.scheduling_resource(organization_id, id),
    FOREIGN KEY (organization_id, visit_type)
        REFERENCES public.visit_type(organization_id, code)
);

CREATE TABLE public.series_occurrence (
    organization_id   uuid NOT NULL REFERENCES public.organization(organization_id),
    id                uuid NOT NULL DEFAULT gen_random_uuid(),
    series_id         uuid NOT NULL,
    appointment_id    uuid NOT NULL,
    occurrence_index  integer NOT NULL CHECK (occurrence_index BETWEEN 1 AND 52),
    planned_starts_at timestamptz NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, series_id, occurrence_index),
    UNIQUE (organization_id, appointment_id),
    FOREIGN KEY (organization_id, series_id)
        REFERENCES public.appointment_series(organization_id, id),
    FOREIGN KEY (organization_id, appointment_id)
        REFERENCES public.appointment(organization_id, id)
);

CREATE INDEX series_occurrence_series_idx
    ON public.series_occurrence (organization_id, series_id, occurrence_index);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['recurrence_template', 'appointment_series', 'series_occurrence']
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            $p$CREATE POLICY tenant_isolation ON public.%I
                 USING (organization_id = public.app_org())
                 WITH CHECK (organization_id = public.app_org())$p$, t);
    END LOOP;
END $$;

-- Append-only for every role, including the migration owner: a stray UPDATE or
-- DELETE must fail even for a role that could bypass the GRANT model.
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['recurrence_template', 'appointment_series', 'series_occurrence']
    LOOP
        EXECUTE format($d$CREATE TRIGGER %I_no_update_delete
            BEFORE UPDATE OR DELETE ON public.%I
            FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
        EXECUTE format($d$CREATE TRIGGER %I_no_truncate
            BEFORE TRUNCATE ON public.%I
            FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
    END LOOP;
END $$;

GRANT SELECT, INSERT ON public.recurrence_template, public.appointment_series,
                        public.series_occurrence TO orevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.recurrence_template, public.appointment_series,
       public.series_occurrence FROM orevia_app;
