-- Requirement IDs: CLN-03, ARC-01, ARC-02, OPS-02
-- Change: expand
-- Longitudinal chart entries (problems, allergies, medications, vitals) carry
-- source, clinical assertion, verification, effective dates, units and
-- measurement time, and separately recorded clinical author and data-entry
-- actor. The submitted code/text is retained verbatim alongside a write-time
-- snapshot of any versioned diagnosis-catalog mapping. No V1-V12 object is
-- altered.
--
-- Reserved version V13 for this schema. It was previously defined only by a
-- test-scoped copy; this migration is the single authoritative definition that
-- production Flyway applies. Tenant-owned table with ENABLE + FORCE RLS, its own
-- tenant policy and explicit grants (the V2 static list does not cover it).

CREATE TABLE public.chart_entry (
    organization_id   uuid NOT NULL REFERENCES public.organization(organization_id),
    id                uuid NOT NULL DEFAULT gen_random_uuid(),
    patient_id        uuid NOT NULL,
    encounter_id      text,
    type              text NOT NULL
        CHECK (type IN ('PROBLEM', 'ALLERGY', 'MEDICATION', 'VITAL')),
    original_text     text NOT NULL CHECK (length(original_text) BETWEEN 1 AND 2000),
    original_code     text,
    code_system       text,
    mapped_code       text,
    mapped_text       text,
    catalog_version   integer,
    source            text NOT NULL
        CHECK (source IN ('PATIENT_REPORTED', 'CLINICIAN_OBSERVED', 'EXTERNAL_RECORD', 'DEVICE')),
    presence          text NOT NULL
        CHECK (presence IN ('PRESENT', 'ABSENT', 'UNKNOWN')),
    unknown_reason    text,
    verification      text NOT NULL
        CHECK (verification IN ('UNVERIFIED', 'VERIFIED')),
    verified_by       text,
    verified_at       timestamptz,
    effective_from    timestamptz,
    effective_to      timestamptz,
    measured_at       timestamptz,
    value             text,
    unit              text,
    clinical_author   text,
    data_entry_actor  text NOT NULL,
    version           integer NOT NULL DEFAULT 1 CHECK (version > 0),
    recorded_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from),
    CHECK (catalog_version IS NULL OR catalog_version > 0),
    CHECK (unknown_reason IS NULL OR presence = 'UNKNOWN'),
    CHECK ((verification = 'VERIFIED') = (verified_by IS NOT NULL AND verified_at IS NOT NULL)),
    CHECK (type <> 'VITAL' OR (measured_at IS NOT NULL AND value IS NOT NULL AND unit IS NOT NULL)),
    FOREIGN KEY (organization_id, patient_id)
        REFERENCES public.patient_record(organization_id, id)
);

ALTER TABLE public.chart_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.chart_entry FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON public.chart_entry
    USING (organization_id = public.app_org())
    WITH CHECK (organization_id = public.app_org());

CREATE INDEX chart_entry_patient_idx ON public.chart_entry
    (organization_id, patient_id, type, effective_from DESC);

REVOKE ALL ON public.chart_entry FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON public.chart_entry TO aurevia_app;
