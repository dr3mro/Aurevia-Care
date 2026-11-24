-- Requirement IDs: RX-01, RX-02, ARC-02, OPS-02
-- Change: expand
-- Durable RX-01/RX-02 pharmacy substitution decisions. The proposal is written
-- once by pharmacy staff and closed exactly once by its named prescriber; the
-- signed revision and the idempotency ledger are immutable append-only facts.
-- The immutable catalog and allergy snapshots are carried as JSONB so a decision
-- can be replayed to both parties without re-reading a mutable catalog. Every
-- table is tenant-owned and uses forced row-level security with its own policy;
-- V2's static table list does not cover tables introduced here.
--
-- Local identifiers are unique only inside a tenant, so every primary key and
-- every business key includes organization_id. Identical local ids in two
-- tenants therefore stay isolated. The unique revision and idempotency keys
-- make the decision sequence and the once-only retry durable rather than
-- process-local, so a new service instance observes the same outcome.

CREATE TABLE public.pharmacy_substitution_proposal (
    organization_id  uuid NOT NULL REFERENCES public.organization(organization_id),
    id               uuid NOT NULL,
    prescription_id  uuid NOT NULL,
    patient_id       text NOT NULL,
    prescriber_subject text NOT NULL,
    proposed_by      text NOT NULL,
    reason           text NOT NULL CHECK (reason IN ('stock', 'brand', 'formulation')),
    state            text NOT NULL CHECK (state IN ('pending', 'approved', 'declined')),
    version          integer NOT NULL CHECK (version >= 1),
    proposed_at      timestamptz NOT NULL,
    decided_at       timestamptz,
    snapshot         jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    alternatives     jsonb NOT NULL CHECK (jsonb_typeof(alternatives) = 'array'),
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    CHECK ((state = 'pending' AND decided_at IS NULL)
        OR (state IN ('approved', 'declined') AND decided_at IS NOT NULL))
);

CREATE TABLE public.pharmacy_substitution_revision (
    organization_id   uuid NOT NULL REFERENCES public.organization(organization_id),
    id                uuid NOT NULL,
    prescription_id   uuid NOT NULL,
    proposal_id       uuid NOT NULL,
    revision_number   integer NOT NULL CHECK (revision_number >= 1),
    approved_by       text NOT NULL,
    signed_at         timestamptz NOT NULL,
    snapshot          jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    created_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, proposal_id),
    UNIQUE (organization_id, prescription_id, revision_number)
);

CREATE TABLE public.pharmacy_substitution_decision (
    organization_id   uuid NOT NULL REFERENCES public.organization(organization_id),
    command           text NOT NULL CHECK (command IN ('propose', 'approve', 'decline')),
    idempotency_key   text NOT NULL CHECK (btrim(idempotency_key) <> ''),
    proposal_id       uuid NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, command, idempotency_key)
);

-- A proposal may move only from pending to a single decision. The content
-- snapshot, identity and provenance are frozen; a bypassing owner cannot alter
-- them or reopen a closed decision.
CREATE OR REPLACE FUNCTION public.pharmacy_substitution_close_transition()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $$
BEGIN
    IF OLD.state <> 'pending' OR NEW.state NOT IN ('approved', 'declined')
       OR NEW.state = OLD.state
       OR NEW.version <> OLD.version + 1
       OR NEW.decided_at IS NULL
       OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
       OR NEW.id IS DISTINCT FROM OLD.id
       OR NEW.prescription_id IS DISTINCT FROM OLD.prescription_id
       OR NEW.patient_id IS DISTINCT FROM OLD.patient_id
       OR NEW.prescriber_subject IS DISTINCT FROM OLD.prescriber_subject
       OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
       OR NEW.reason IS DISTINCT FROM OLD.reason
       OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
       OR NEW.alternatives IS DISTINCT FROM OLD.alternatives
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'a substitution proposal closes exactly once'
            USING ERRCODE = '44000';
    END IF;
    RETURN NEW;
END $$;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'pharmacy_substitution_proposal', 'pharmacy_substitution_revision',
        'pharmacy_substitution_decision'
    ]
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON public.%I
             USING (organization_id = public.app_org())
             WITH CHECK (organization_id = public.app_org())$p$, t);
    END LOOP;

    FOREACH t IN ARRAY ARRAY[
        'pharmacy_substitution_revision', 'pharmacy_substitution_decision'
    ]
    LOOP
        EXECUTE format($d$CREATE TRIGGER %I_no_update_delete
            BEFORE UPDATE OR DELETE ON public.%I
            FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
        EXECUTE format($d$CREATE TRIGGER %I_no_truncate
            BEFORE TRUNCATE ON public.%I
            FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$d$, t, t);
    END LOOP;

    EXECUTE $d$CREATE TRIGGER pharmacy_substitution_proposal_no_delete
        BEFORE DELETE ON public.pharmacy_substitution_proposal
        FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$d$;
    EXECUTE $d$CREATE TRIGGER pharmacy_substitution_proposal_no_truncate
        BEFORE TRUNCATE ON public.pharmacy_substitution_proposal
        FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$d$;
    EXECUTE $d$CREATE TRIGGER pharmacy_substitution_proposal_close_only
        BEFORE UPDATE ON public.pharmacy_substitution_proposal
        FOR EACH ROW EXECUTE FUNCTION public.pharmacy_substitution_close_transition()$d$;
END $$;

GRANT SELECT, INSERT ON public.pharmacy_substitution_proposal TO aurevia_app;
GRANT SELECT, INSERT ON public.pharmacy_substitution_revision TO aurevia_app;
GRANT SELECT, INSERT ON public.pharmacy_substitution_decision TO aurevia_app;
GRANT UPDATE (state, version, decided_at, snapshot) ON public.pharmacy_substitution_proposal TO aurevia_app;
REVOKE DELETE, TRUNCATE ON public.pharmacy_substitution_proposal FROM aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.pharmacy_substitution_revision FROM aurevia_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.pharmacy_substitution_decision FROM aurevia_app;
