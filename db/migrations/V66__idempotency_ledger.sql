-- Requirement IDs: ARC-03, ARC-04, ARC-02
-- Change: expand
-- Durable retry ledger for protected write commands. One row per tenant,
-- authenticated caller and idempotency key freezes the status and JSON body of
-- the first successful call for a bounded window. A retry with the same
-- server-computed request digest replays that response; a different digest
-- under the same key is a conflict decided by the caller. organization_id is
-- part of the primary key, so identical local keys in two tenants stay
-- isolated. This is a deduplication cache, not durable clinical business
-- uniqueness, which remains the owning module's transactional constraint.
--
-- No token, credential, header, correlation identifier or raw request body is
-- stored: only a lowercase SHA-256 digest and the successful response. A 2xx
-- body can itself carry clinical payloads, so this table is PHI-bearing and
-- must be read only under the caller's tenant GUC. Expired rows are purged by
-- the store on the next keyed read in that tenant; a scheduled sweep for tenants
-- with no further traffic remains a separate operational story. The non-owner
-- runtime role gets INSERT, SELECT and DELETE only, so a frozen response cannot
-- be rewritten in place.
--
-- Lock and volume: CREATE TABLE on a new relation takes no lock on existing
-- data. Volume is bounded by keyed commands inside one expiry window and the
-- (organization_id, expires_at) index supports expiry-scoped reads and cleanup.
-- Recovery: additive only; a later contract migration may drop the table and
-- its policy with Requires-expand: V66. No secret rotation is needed.

CREATE TABLE public.idempotency_record (
    organization_id uuid        NOT NULL REFERENCES public.organization(organization_id),
    subject         text        NOT NULL CHECK (btrim(subject) <> ''),
    key             text        NOT NULL CHECK (key ~ '^[A-Za-z0-9._:-]{1,255}$'),
    request_hash    text        NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    status_code     integer     NOT NULL CHECK (status_code BETWEEN 200 AND 299),
    response_body   jsonb       NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    PRIMARY KEY (organization_id, subject, key),
    CHECK (expires_at > created_at)
);

COMMENT ON TABLE public.idempotency_record IS
    'ARC-03 tenant-scoped first-success replay ledger; no tokens or patient identifiers.';
COMMENT ON COLUMN public.idempotency_record.request_hash IS
    'Server-computed lowercase SHA-256 of the canonical request; never a caller token.';
COMMENT ON COLUMN public.idempotency_record.response_body IS
    'Canonical JSON response of the first successful call; bounded by expires_at.';

CREATE INDEX idempotency_record_expiry_idx ON public.idempotency_record
    (organization_id, expires_at);

-- New tables are not covered by V2's static table list, so the tenant policy,
-- forced enforcement and grants are declared here. public.app_org() reads the
-- transaction-local app.current_org GUC bound by the tenant-guarded transaction;
-- an unbound session sees no rows and fails closed.
ALTER TABLE public.idempotency_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.idempotency_record FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON public.idempotency_record
    USING (organization_id = public.app_org())
    WITH CHECK (organization_id = public.app_org());

GRANT SELECT, INSERT, DELETE ON public.idempotency_record TO aurevia_app;
REVOKE UPDATE, TRUNCATE ON public.idempotency_record FROM aurevia_app;
