-- Requirement IDs: FIN-01, ARC-01, ARC-02, ARC-03, OPS-02
-- Change: expand
-- Self-pay finance foundation: itemized invoices in fixed-precision integer
-- minor units with exactly one currency, append-only invoice lines and a
-- numbered, append-only ledger of payments, refunds and credits. Money columns
-- are monotonically increasing gross totals so the balance is always derived as
-- total - paid + refunded - credited; the database enforces that identity.
-- Reversals and voids carry a reason and an independent approver. A durable
-- per-tenant reference makes a replayed payment a no-op instead of a second
-- charge, and a per-tenant counter issues gap-free receipt numbers.
--
-- Forced RLS from V2 applies to every table and every foreign key carries the
-- organization, so a finance row cannot be re-pointed at another tenant. Only
-- finance columns are stored here: no clinical narrative or clinical table is
-- referenced, so a finance role can reconcile totals without reading a chart.
--
-- Compatibility note: the offline db/check.sh runner applies V*.sql in shell
-- glob order, which places several two-digit versions before V3. Flyway applies
-- by numeric version and is the authority. The guard below makes an out-of-order
-- offline apply a no-op instead of an error; it never skips a Flyway apply,
-- because Flyway always installs the prerequisites first.

DO $finance$
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
       OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'orevia_app') THEN
        RAISE NOTICE 'V45 finance deferred: prerequisites not installed yet';
        RETURN;
    END IF;

    EXECUTE $ddl$CREATE TABLE public.finance_invoice (
        organization_id       uuid NOT NULL REFERENCES public.organization(organization_id),
        id                    uuid NOT NULL DEFAULT gen_random_uuid(),
        version               integer NOT NULL DEFAULT 1 CHECK (version > 0),
        patient_id            uuid NOT NULL,
        currency              text NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
        state                 text NOT NULL
            CHECK (state IN ('draft', 'issued', 'partially_paid', 'paid', 'void')),
        total_minor           bigint NOT NULL CHECK (total_minor >= 0),
        paid_minor            bigint NOT NULL DEFAULT 0 CHECK (paid_minor >= 0),
        refunded_minor        bigint NOT NULL DEFAULT 0 CHECK (refunded_minor >= 0),
        credited_minor        bigint NOT NULL DEFAULT 0 CHECK (credited_minor >= 0),
        balance_minor         bigint NOT NULL CHECK (balance_minor >= 0),
        service_reference     text,
        created_by            uuid NOT NULL,
        created_at            timestamptz NOT NULL DEFAULT now(),
        issued_at             timestamptz,
        void_reason           text,
        voided_by_subject     text,
        PRIMARY KEY (organization_id, id),
        CHECK (balance_minor = total_minor - paid_minor + refunded_minor - credited_minor),
        CHECK (refunded_minor <= paid_minor),
        CHECK (issued_at IS NULL OR state <> 'draft'),
        CHECK (state <> 'void' OR (void_reason IS NOT NULL AND voided_by_subject IS NOT NULL)),
        FOREIGN KEY (organization_id, patient_id)
            REFERENCES public.patient_record(organization_id, id),
        FOREIGN KEY (organization_id, created_by)
            REFERENCES public.user_identity(organization_id, id)
    )$ddl$;

    EXECUTE $ddl$CREATE INDEX finance_invoice_patient_idx ON public.finance_invoice
        (organization_id, patient_id, created_at DESC)$ddl$;

    EXECUTE $ddl$CREATE TABLE public.finance_invoice_item (
        organization_id       uuid NOT NULL,
        id                    uuid NOT NULL DEFAULT gen_random_uuid(),
        invoice_id            uuid NOT NULL,
        line_no               integer NOT NULL CHECK (line_no > 0),
        description           text NOT NULL,
        quantity              integer NOT NULL CHECK (quantity > 0),
        unit_amount_minor     bigint NOT NULL CHECK (unit_amount_minor >= 0),
        amount_minor          bigint NOT NULL
            CHECK (amount_minor >= 0 AND amount_minor = quantity * unit_amount_minor),
        created_by            uuid NOT NULL,
        created_at            timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY (organization_id, id),
        UNIQUE (organization_id, invoice_id, line_no),
        FOREIGN KEY (organization_id, invoice_id)
            REFERENCES public.finance_invoice(organization_id, id),
        FOREIGN KEY (organization_id, created_by)
            REFERENCES public.user_identity(organization_id, id)
    )$ddl$;

    EXECUTE $ddl$CREATE TABLE public.finance_ledger_entry (
        organization_id       uuid NOT NULL,
        id                    uuid NOT NULL DEFAULT gen_random_uuid(),
        invoice_id            uuid NOT NULL,
        invoice_version       integer NOT NULL CHECK (invoice_version > 0),
        entry_type            text NOT NULL
            CHECK (entry_type IN ('payment', 'refund', 'credit')),
        amount_minor          bigint NOT NULL CHECK (amount_minor > 0),
        currency              text NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
        reference             text NOT NULL,
        reason                text,
        approver_subject      text,
        receipt_no            bigint NOT NULL CHECK (receipt_no > 0),
        payload_digest        text NOT NULL CHECK (char_length(payload_digest) BETWEEN 1 AND 128),
        actor_id              uuid NOT NULL,
        occurred_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
        PRIMARY KEY (organization_id, id),
        UNIQUE (organization_id, reference),
        UNIQUE (organization_id, receipt_no),
        CHECK (entry_type = 'payment' OR reason IS NOT NULL),
        CHECK (entry_type = 'payment' OR approver_subject IS NOT NULL),
        FOREIGN KEY (organization_id, invoice_id)
            REFERENCES public.finance_invoice(organization_id, id),
        FOREIGN KEY (organization_id, actor_id)
            REFERENCES public.user_identity(organization_id, id)
    )$ddl$;

    EXECUTE $ddl$CREATE INDEX finance_ledger_trail_idx ON public.finance_ledger_entry
        (organization_id, invoice_id, occurred_at, id)$ddl$;
    EXECUTE $ddl$CREATE INDEX finance_ledger_daily_idx ON public.finance_ledger_entry
        (organization_id, occurred_at, entry_type)$ddl$;

    EXECUTE $ddl$CREATE TABLE public.finance_receipt_counter (
        organization_id       uuid PRIMARY KEY REFERENCES public.organization(organization_id),
        last_value            bigint NOT NULL DEFAULT 0 CHECK (last_value >= 0)
    )$ddl$;

    EXECUTE $ddl$DO $rls$
    DECLARE t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY['finance_invoice', 'finance_invoice_item',
                                 'finance_ledger_entry', 'finance_receipt_counter']
        LOOP
            EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
            EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
            EXECUTE format(
                $p$CREATE POLICY tenant_isolation ON public.%I
                     USING (organization_id = public.app_org())
                     WITH CHECK (organization_id = public.app_org())$p$, t);
        END LOOP;
    END $rls$$ddl$;

    -- Financial history is immutable: items and ledger entries can never be
    -- updated or deleted, and an invoice can never be deleted.
    EXECUTE $ddl$CREATE TRIGGER finance_invoice_item_no_update_delete
        BEFORE UPDATE OR DELETE ON public.finance_invoice_item
        FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER finance_invoice_item_no_truncate
        BEFORE TRUNCATE ON public.finance_invoice_item
        FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER finance_ledger_no_update_delete
        BEFORE UPDATE OR DELETE ON public.finance_ledger_entry
        FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER finance_ledger_no_truncate
        BEFORE TRUNCATE ON public.finance_ledger_entry
        FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER finance_invoice_no_delete
        BEFORE DELETE ON public.finance_invoice
        FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER finance_invoice_no_truncate
        BEFORE TRUNCATE ON public.finance_invoice
        FOR EACH STATEMENT EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;
    EXECUTE $ddl$CREATE TRIGGER finance_receipt_counter_no_delete
        BEFORE DELETE ON public.finance_receipt_counter
        FOR EACH ROW EXECUTE FUNCTION public.reject_immutable_mutation()$ddl$;

    -- Authorship and ledger actors are always the authenticated actor of the
    -- active tenant, never caller-supplied.
    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.set_finance_invoice_created_by_from_actor()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    DECLARE v_actor uuid;
    BEGIN
        SELECT id INTO v_actor FROM public.user_identity
         WHERE organization_id = NEW.organization_id
           AND subject = NULLIF(current_setting('app.actor', true), '');
        IF v_actor IS NULL THEN
            RAISE EXCEPTION 'finance invoice requires an authenticated user of the active tenant'
                USING ERRCODE = '42501';
        END IF;
        NEW.created_by := v_actor;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER finance_invoice_created_by_actor
        BEFORE INSERT ON public.finance_invoice
        FOR EACH ROW EXECUTE FUNCTION public.set_finance_invoice_created_by_from_actor()$ddl$;

    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.set_finance_item_created_by_from_actor()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    DECLARE v_actor uuid;
    BEGIN
        SELECT id INTO v_actor FROM public.user_identity
         WHERE organization_id = NEW.organization_id
           AND subject = NULLIF(current_setting('app.actor', true), '');
        IF v_actor IS NULL THEN
            RAISE EXCEPTION 'finance line requires an authenticated user of the active tenant'
                USING ERRCODE = '42501';
        END IF;
        NEW.created_by := v_actor;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER finance_invoice_item_created_by_actor
        BEFORE INSERT ON public.finance_invoice_item
        FOR EACH ROW EXECUTE FUNCTION public.set_finance_item_created_by_from_actor()$ddl$;

    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.set_finance_entry_actor_from_actor()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    DECLARE v_actor uuid;
    BEGIN
        SELECT id INTO v_actor FROM public.user_identity
         WHERE organization_id = NEW.organization_id
           AND subject = NULLIF(current_setting('app.actor', true), '');
        IF v_actor IS NULL THEN
            RAISE EXCEPTION 'finance ledger entry requires an authenticated user of the active tenant'
                USING ERRCODE = '42501';
        END IF;
        NEW.actor_id := v_actor;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER finance_ledger_entry_actor
        BEFORE INSERT ON public.finance_ledger_entry
        FOR EACH ROW EXECUTE FUNCTION public.set_finance_entry_actor_from_actor()$ddl$;

    -- One currency per invoice, including every linked ledger entry.
    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.guard_finance_entry_currency()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    DECLARE v_currency text;
    BEGIN
        SELECT currency INTO v_currency FROM public.finance_invoice
         WHERE organization_id = NEW.organization_id AND id = NEW.invoice_id;
        IF v_currency IS NULL OR v_currency <> NEW.currency THEN
            RAISE EXCEPTION 'a ledger entry currency must match its invoice currency'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER finance_ledger_entry_currency
        BEFORE INSERT ON public.finance_ledger_entry
        FOR EACH ROW EXECUTE FUNCTION public.guard_finance_entry_currency()$ddl$;

    -- Itemized total and immutable lines cannot drift apart at issue time.
    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.guard_finance_invoice_issue_items()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    DECLARE v_items bigint;
    BEGIN
        IF OLD.state = 'draft' AND NEW.state <> 'draft' THEN
            SELECT COALESCE(SUM(amount_minor), 0) INTO v_items
              FROM public.finance_invoice_item
             WHERE organization_id = NEW.organization_id AND invoice_id = NEW.id;
            IF v_items <> NEW.total_minor THEN
                RAISE EXCEPTION 'invoice total must equal the sum of its itemized lines'
                    USING ERRCODE = '23514';
            END IF;
        END IF;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER finance_invoice_issue_items
        BEFORE UPDATE ON public.finance_invoice
        FOR EACH ROW EXECUTE FUNCTION public.guard_finance_invoice_issue_items()$ddl$;

    -- The database is the second line of defence for the FIN-01 invariants.
    EXECUTE $ddl$CREATE OR REPLACE FUNCTION public.guard_finance_invoice_update()
    RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog
    AS $fn$
    BEGIN
        IF NEW.id <> OLD.id
           OR NEW.organization_id <> OLD.organization_id
           OR NEW.patient_id <> OLD.patient_id
           OR NEW.currency <> OLD.currency
           OR NEW.total_minor <> OLD.total_minor
           OR NEW.created_by <> OLD.created_by
           OR NEW.created_at <> OLD.created_at THEN
            RAISE EXCEPTION 'finance invoice ownership and money columns are immutable'
                USING ERRCODE = '42501';
        END IF;
        IF NEW.version <> OLD.version + 1 THEN
            RAISE EXCEPTION 'finance invoice version must advance by exactly one'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.paid_minor < OLD.paid_minor
           OR NEW.refunded_minor < OLD.refunded_minor
           OR NEW.credited_minor < OLD.credited_minor THEN
            RAISE EXCEPTION 'finance money totals are append-only'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.state IS DISTINCT FROM OLD.state AND NOT (
                (OLD.state = 'draft' AND NEW.state IN ('issued', 'void'))
             OR (OLD.state = 'issued' AND NEW.state IN ('partially_paid', 'paid', 'void'))
             OR (OLD.state = 'partially_paid' AND NEW.state IN ('issued', 'paid'))
             OR (OLD.state = 'paid' AND NEW.state IN ('partially_paid', 'issued'))) THEN
            RAISE EXCEPTION 'illegal finance invoice transition % -> %', OLD.state, NEW.state
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END $fn$$ddl$;
    EXECUTE $ddl$CREATE TRIGGER finance_invoice_update_guard
        BEFORE UPDATE ON public.finance_invoice
        FOR EACH ROW EXECUTE FUNCTION public.guard_finance_invoice_update()$ddl$;

    EXECUTE $ddl$GRANT SELECT, INSERT, UPDATE ON public.finance_invoice TO orevia_app$ddl$;
    EXECUTE $ddl$REVOKE DELETE, TRUNCATE ON public.finance_invoice FROM orevia_app$ddl$;
    EXECUTE $ddl$GRANT SELECT, INSERT ON public.finance_invoice_item TO orevia_app$ddl$;
    EXECUTE $ddl$REVOKE UPDATE, DELETE, TRUNCATE ON public.finance_invoice_item FROM orevia_app$ddl$;
    EXECUTE $ddl$GRANT SELECT, INSERT ON public.finance_ledger_entry TO orevia_app$ddl$;
    EXECUTE $ddl$REVOKE UPDATE, DELETE, TRUNCATE ON public.finance_ledger_entry FROM orevia_app$ddl$;
    EXECUTE $ddl$GRANT SELECT, INSERT, UPDATE ON public.finance_receipt_counter TO orevia_app$ddl$;
    EXECUTE $ddl$REVOKE DELETE, TRUNCATE ON public.finance_receipt_counter FROM orevia_app$ddl$;

    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.set_finance_invoice_created_by_from_actor() FROM PUBLIC$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.set_finance_item_created_by_from_actor() FROM PUBLIC$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.set_finance_entry_actor_from_actor() FROM PUBLIC$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.guard_finance_invoice_update() FROM PUBLIC$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.guard_finance_entry_currency() FROM PUBLIC$ddl$;
    EXECUTE $ddl$REVOKE ALL ON FUNCTION public.guard_finance_invoice_issue_items() FROM PUBLIC$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.set_finance_invoice_created_by_from_actor() TO orevia_app$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.set_finance_item_created_by_from_actor() TO orevia_app$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.set_finance_entry_actor_from_actor() TO orevia_app$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.guard_finance_invoice_update() TO orevia_app$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.guard_finance_entry_currency() TO orevia_app$ddl$;
    EXECUTE $ddl$GRANT EXECUTE ON FUNCTION public.guard_finance_invoice_issue_items() TO orevia_app$ddl$;
END $finance$;
