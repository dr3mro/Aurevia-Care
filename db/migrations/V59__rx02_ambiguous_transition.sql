-- Requirement IDs: RX-02, ARC-01, ARC-02, ARC-03
-- Change: expand
-- V44's prescription update guard omitted the documented ambiguous first-send
-- outcome from the legal transitions out of 'attested'. The state CHECK, the
-- contract enum, RxInputs.transmissionOutcome and RxService.transmit all treat
-- 'ambiguous' as a valid result of an electronic transmission, and RX-02
-- requires the partner to be queried before a retry. Without this transition an
-- ambiguous send is rejected by the database with SQLSTATE 23514 and the API
-- answers 500. This migration only widens the guard; it does not remove any
-- invariant, alter applied history or change the append-only ledgers.
-- The trigger created in V44 calls this function by name, so replacing the
-- function updates the existing trigger in place.

CREATE OR REPLACE FUNCTION public.guard_rx02_prescription_update()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
AS $fn$
BEGIN
    IF NEW.id <> OLD.id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.patient_id <> OLD.patient_id
       OR NEW.medication <> OLD.medication
       OR NEW.strength <> OLD.strength
       OR NEW.dose <> OLD.dose
       OR NEW.route <> OLD.route
       OR NEW.frequency <> OLD.frequency
       OR NEW.quantity <> OLD.quantity
       OR NEW.prescriber_subject <> OLD.prescriber_subject
       OR NEW.destination_pharmacy IS DISTINCT FROM OLD.destination_pharmacy
       OR NEW.created_by <> OLD.created_by
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'prescription identity and clinical columns are immutable'
            USING ERRCODE = '42501';
    END IF;
    -- A channel and a submission id are stamped once and never rewritten.
    -- Reusing the same id on a retry is therefore the only legal path.
    IF OLD.channel IS NOT NULL AND NEW.channel IS DISTINCT FROM OLD.channel THEN
        RAISE EXCEPTION 'prescription routing channel is immutable'
            USING ERRCODE = '42501';
    END IF;
    IF OLD.submission_id IS NOT NULL AND NEW.submission_id IS DISTINCT FROM OLD.submission_id THEN
        RAISE EXCEPTION 'prescription submission id is immutable'
            USING ERRCODE = '42501';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'prescription version must advance by exactly one'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.state <> OLD.state AND NOT (
            (OLD.state = 'draft' AND NEW.state IN ('attested', 'cancelled'))
         OR (OLD.state = 'attested' AND NEW.state IN ('submitted', 'ambiguous', 'printed', 'cancelled'))
         OR (OLD.state = 'submitted' AND NEW.state IN ('ambiguous', 'delivery_confirmed', 'cancelled'))
         OR (OLD.state = 'ambiguous' AND NEW.state IN ('submitted', 'delivery_confirmed', 'cancelled'))
         OR (OLD.state = 'delivery_confirmed' AND NEW.state IN ('accepted', 'rejected', 'clarification', 'cancelled'))
         OR (OLD.state = 'clarification' AND NEW.state IN ('accepted', 'rejected', 'cancelled'))
         OR (OLD.state = 'accepted' AND NEW.state IN ('dispensed', 'cancelled'))) THEN
        RAISE EXCEPTION 'illegal prescription transition % -> %', OLD.state, NEW.state
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $fn$;

REVOKE ALL ON FUNCTION public.guard_rx02_prescription_update() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.guard_rx02_prescription_update() TO orevia_app;
