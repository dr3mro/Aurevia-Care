-- Requirement IDs: PAT-02, ARC-02
-- Change: expand
--
-- Purpose: durable, tenant-owned projections behind /api/v1/patient/appointments.
--   patient_appointment_projection backs the patient appointment store (list, find and
--   versioned confirmation), device_token backs the device registry, and
--   device_token_invalid_log is the token-free append-only disposition ledger.
--   None of these reuses V8's clinical public.appointment: these are opaque-string
--   patient-facing projections, not scheduling rows with clinician and room FKs.
-- Invariant: every row carries organization_id = public.app_org() under FORCE RLS, so
--   a pooled connection cannot expose another tenant's facts. Confirmation is one
--   atomic version-preconditioned update. The provider token lives only in
--   device_token.token and is never copied into a log or an audit row. Dispositions
--   cannot be rewritten after they are appended.
-- RLS: ENABLE + FORCE ROW LEVEL SECURITY and a local tenant_isolation policy are
--   declared here because V2's static table list does not cover later tables.
-- Lock/volume: three empty tables and two append-only triggers; no backfill, no row
--   rewrite and no lock taken on an existing table.
-- Recovery / forward-fix: additive and idempotent on a fresh database; no capability
--   is removed, so no contract forward-fix is required. A dropped deployment can
--   re-apply the file before the next Flyway version.

CREATE TABLE public.patient_appointment_projection (
    organization_id     uuid NOT NULL REFERENCES public.organization(organization_id),
    id                  text NOT NULL CHECK (char_length(id) BETWEEN 1 AND 255),
    version             integer NOT NULL DEFAULT 1 CHECK (version > 0),
    patient_id          text NOT NULL CHECK (char_length(patient_id) BETWEEN 1 AND 255),
    facility_code       text NOT NULL CHECK (char_length(facility_code) BETWEEN 1 AND 128),
    facility_time_zone  text NOT NULL CHECK (char_length(facility_time_zone) BETWEEN 1 AND 64),
    starts_at           timestamptz NOT NULL,
    status              text NOT NULL CHECK (status IN
                            ('booked', 'checked_in', 'in_progress', 'completed',
                             'cancelled', 'no_show')),
    confirmation_enabled boolean NOT NULL,
    confirmed_at        timestamptz,
    PRIMARY KEY (organization_id, id)
);

CREATE TABLE public.device_token (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    token_id        text NOT NULL CHECK (char_length(token_id) BETWEEN 1 AND 255),
    token           text NOT NULL CHECK (char_length(token) BETWEEN 1 AND 4096),
    subject         text NOT NULL CHECK (char_length(subject) BETWEEN 1 AND 255),
    patient_id      text NOT NULL CHECK (char_length(patient_id) BETWEEN 1 AND 255),
    state           text NOT NULL DEFAULT 'active'
                        CHECK (state IN ('active', 'revoked', 'invalid')),
    PRIMARY KEY (organization_id, token_id)
);

CREATE TABLE public.device_token_invalid_log (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    token_id        text NOT NULL CHECK (char_length(token_id) BETWEEN 1 AND 255),
    patient_id      text NOT NULL CHECK (char_length(patient_id) BETWEEN 1 AND 255),
    reason          text NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 128
                        AND reason IN ('INVALID_TOKEN', 'UNREGISTERED', 'EXPIRED',
                                       'REVOKED', 'MALFORMED', 'UNSPECIFIED')),
    occurred_at     timestamptz NOT NULL,
    -- Deliberately no token column and no free-text detail: only the opaque id
    -- and a canonical disposition code are durable. The service normalizes any
    -- other client-supplied text to UNSPECIFIED before it reaches this ledger,
    -- so a caller cannot turn a fact table into a PHI or credential sink.
    PRIMARY KEY (organization_id, id)
);

DO $patient_devices$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'patient_appointment_projection', 'device_token', 'device_token_invalid_log'
    ]
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($policy$CREATE POLICY tenant_isolation ON public.%I
             USING (organization_id = public.app_org())
             WITH CHECK (organization_id = public.app_org())$policy$, t);
    END LOOP;
END $patient_devices$;

-- Append-only dispositions for every role, including the migration owner: a
-- GRANT/REVOKE pair cannot stop the owner, but the trigger can.
CREATE TRIGGER device_token_invalid_log_no_update_delete
    BEFORE UPDATE OR DELETE ON public.device_token_invalid_log
    FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation();
CREATE TRIGGER device_token_invalid_log_no_truncate
    BEFORE TRUNCATE ON public.device_token_invalid_log
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation();

GRANT SELECT, INSERT ON public.patient_appointment_projection TO aurevia_app;
GRANT UPDATE (version, confirmed_at) ON public.patient_appointment_projection TO aurevia_app;
GRANT SELECT, INSERT, UPDATE ON public.device_token TO aurevia_app;
GRANT SELECT, INSERT ON public.device_token_invalid_log TO aurevia_app;
REVOKE DELETE, TRUNCATE ON public.patient_appointment_projection,
                          public.device_token,
                          public.device_token_invalid_log FROM aurevia_app;
