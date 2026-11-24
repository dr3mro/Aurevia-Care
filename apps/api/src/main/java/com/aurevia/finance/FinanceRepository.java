package com.aurevia.finance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aurevia.finance.FinanceContract.DailyTotal;
import com.aurevia.finance.FinanceContract.EntryType;
import com.aurevia.finance.FinanceContract.Invoice;
import com.aurevia.finance.FinanceContract.InvoiceItem;
import com.aurevia.finance.FinanceContract.InvoiceState;
import com.aurevia.finance.FinanceContract.LedgerEntry;

/**
 * The single SQL boundary for FIN-01 finance records.
 *
 * <p>Every statement runs inside the tenant-guarded transaction, so forced row
 * level security confines it to the active organization. The ledger and item
 * tables are append-only for every role, and the actor and owning organization
 * are always derived from the transaction's {@code app.actor} and
 * {@code app.current_org} bindings, never from caller input.</p>
 */
@Repository
public class FinanceRepository {

    private static final String INVOICE_SELECT = """
            SELECT id::text AS id, version, patient_id::text AS patient_id, currency, state,
                   total_minor, paid_minor, refunded_minor, credited_minor, balance_minor,
                   service_reference, created_at, issued_at, void_reason, voided_by_subject
              FROM public.finance_invoice
            """;

    private static final String ITEM_SELECT = """
            SELECT id::text AS id, invoice_id::text AS invoice_id, line_no, description,
                   quantity, unit_amount_minor, amount_minor
              FROM public.finance_invoice_item
            """;

    private static final String ENTRY_SELECT = """
            SELECT id::text AS id, invoice_id::text AS invoice_id, invoice_version, entry_type,
                   amount_minor, currency, reference, reason, approver_subject, receipt_no,
                   actor_id::text AS actor_id, occurred_at
              FROM public.finance_ledger_entry
            """;

    private static final String ENTRY_SELECT_WITH_DIGEST = """
            SELECT id::text AS id, invoice_id::text AS invoice_id, invoice_version, entry_type,
                   amount_minor, currency, reference, reason, approver_subject, receipt_no,
                   actor_id::text AS actor_id, occurred_at, payload_digest
              FROM public.finance_ledger_entry
            """;

    private static final RowMapper<Invoice> INVOICE_MAPPER = FinanceRepository::mapInvoice;
    private static final RowMapper<InvoiceItem> ITEM_MAPPER = FinanceRepository::mapItem;
    private static final RowMapper<LedgerEntry> ENTRY_MAPPER = FinanceRepository::mapEntry;
    private static final RowMapper<DailyTotal> DAY_MAPPER = FinanceRepository::mapDay;
    private static final RowMapper<InvoiceRow> LIST_MAPPER = FinanceRepository::mapListRow;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public FinanceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A previously stored entry and the replay fingerprint used to deduplicate it.
     *
     * @param digest stored payload fingerprint
     * @param entry  the original immutable entry
     * @return the replay evidence
     */
    public record Replay(String digest, LedgerEntry entry) {
    }

    /**
     * One list row: the invoice projection plus the canonical creation instant
     * the database sorted on.
     *
     * <p>The instant is read from the row rather than reconstructed by the
     * service so the opaque cursor always names the exact value used for the
     * exclusive paging bound.</p>
     *
     * @param invoice   the current invoice projection
     * @param createdAt canonical UTC creation instant used as the cursor sort value
     */
    record InvoiceRow(Invoice invoice, String createdAt) {
    }

    /**
     * Check active membership with the authoritative database fold.
     *
     * @param subject authenticated subject
     * @return {@code true} when the latest membership is active
     */
    public boolean hasActiveMembership(String subject) {
        Boolean active = jdbc.queryForObject(
                "SELECT public.has_active_membership(:subject)", Map.of("subject", subject), Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    /**
     * List the roles currently in force for a subject.
     *
     * @param subject authenticated subject
     * @return distinct active role keys
     */
    public List<String> activeRoles(String subject) {
        return jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject)", Map.of("subject", subject), String.class);
    }

    /**
     * Check that a patient is visible in the active organization.
     *
     * @param patientId patient identifier
     * @return {@code true} when the patient exists in this tenant
     */
    public boolean patientExists(String patientId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.patient_record WHERE id = CAST(:patientId AS uuid))
                """, Map.of("patientId", patientId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Insert a new draft invoice and return its generated identifier.
     *
     * @param patientId        owning patient
     * @param currency         single currency of the invoice
     * @param serviceReference opaque non-clinical reference, or {@code null}
     * @param totalMinor       sum of the itemized lines
     * @return the generated invoice identifier
     */
    public String insertInvoice(String patientId, String currency, String serviceReference, long totalMinor) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO public.finance_invoice
                    (organization_id, id, version, patient_id, currency, state,
                     total_minor, paid_minor, refunded_minor, credited_minor, balance_minor,
                     service_reference)
                VALUES (public.app_org(), CAST(:id AS uuid), 1, CAST(:patientId AS uuid),
                        :currency, 'draft', :totalMinor, 0, 0, 0, :totalMinor, :serviceReference)
                RETURNING id::text
                """, new MapSqlParameterSource().addValue("id", FinanceInputs.newId())
                .addValue("patientId", patientId).addValue("currency", currency)
                .addValue("totalMinor", totalMinor).addValue("serviceReference", serviceReference),
                String.class));
    }

    /**
     * Append one immutable itemized line.
     *
     * @param invoiceId       owning invoice
     * @param lineNo          stable 1-based line number
     * @param description     non-clinical label
     * @param quantity        item quantity
     * @param unitAmountMinor unit amount in minor units
     * @param amountMinor     line amount in minor units
     */
    public void insertItem(String invoiceId, int lineNo, String description, int quantity,
                           long unitAmountMinor, long amountMinor) {
        jdbc.update("""
                INSERT INTO public.finance_invoice_item
                    (organization_id, id, invoice_id, line_no, description, quantity,
                     unit_amount_minor, amount_minor)
                VALUES (public.app_org(), CAST(:id AS uuid), CAST(:invoiceId AS uuid), :lineNo,
                        :description, :quantity, :unitAmountMinor, :amountMinor)
                """, new MapSqlParameterSource().addValue("id", FinanceInputs.newId())
                .addValue("invoiceId", invoiceId).addValue("lineNo", lineNo)
                .addValue("description", description).addValue("quantity", quantity)
                .addValue("unitAmountMinor", unitAmountMinor).addValue("amountMinor", amountMinor));
    }

    /**
     * Allocate the next tenant-local receipt number inside the current transaction.
     *
     * @return a gap-free positive receipt number for this tenant
     */
    public long nextReceiptNo() {
        jdbc.update("""
                INSERT INTO public.finance_receipt_counter (organization_id, last_value)
                VALUES (public.app_org(), 0)
                ON CONFLICT (organization_id) DO NOTHING
                """, Map.of());
        return Objects.requireNonNull(jdbc.queryForObject("""
                UPDATE public.finance_receipt_counter SET last_value = last_value + 1
                 WHERE organization_id = public.app_org()
                RETURNING last_value
                """, Map.of(), Long.class));
    }

    /**
     * Look up a previously posted entry by its durable reference.
     *
     * @param reference stable tenant-unique reference
     * @return the stored fingerprint and entry when the reference was used before
     */
    public Optional<Replay> findByReference(String reference) {
        return jdbc.query(ENTRY_SELECT_WITH_DIGEST + " WHERE reference = :reference",
                Map.of("reference", reference), (resultSet, rowNumber) ->
                        new Replay(resultSet.getString("payload_digest"), mapEntry(resultSet, rowNumber)))
                .stream().findFirst();
    }

    /**
     * Append one immutable, numbered ledger entry.
     *
     * @param invoiceId       owning invoice
     * @param invoiceVersion  invoice revision the entry was posted against
     * @param type            entry type
     * @param amountMinor     magnitude in minor units
     * @param currency        currency snapshot
     * @param reference       stable tenant-unique reference
     * @param reason          recorded reason, or {@code null}
     * @param approverSubject approving subject, or {@code null}
     * @param receiptNo       allocated receipt number
     * @param digest          replay fingerprint
     */
    public void insertEntry(String invoiceId, int invoiceVersion, EntryType type, long amountMinor,
                            String currency, String reference, String reason, String approverSubject,
                            long receiptNo, String digest) {
        jdbc.update("""
                INSERT INTO public.finance_ledger_entry
                    (organization_id, id, invoice_id, invoice_version, entry_type, amount_minor,
                     currency, reference, reason, approver_subject, receipt_no, payload_digest)
                VALUES (public.app_org(), CAST(:id AS uuid), CAST(:invoiceId AS uuid), :invoiceVersion,
                        :type, :amountMinor, :currency, :reference, :reason, :approverSubject,
                        :receiptNo, :digest)
                """, new MapSqlParameterSource().addValue("id", FinanceInputs.newId())
                .addValue("invoiceId", invoiceId).addValue("invoiceVersion", invoiceVersion)
                .addValue("type", type.wire()).addValue("amountMinor", amountMinor)
                .addValue("currency", currency).addValue("reference", reference)
                .addValue("reason", reason).addValue("approverSubject", approverSubject)
                .addValue("receiptNo", receiptNo).addValue("digest", digest));
    }

    /**
     * Read one invoice without locking it.
     *
     * @param id invoice identifier
     * @return the invoice when visible in this tenant
     */
    public Optional<Invoice> findInvoice(String id) {
        return jdbc.query(INVOICE_SELECT + " WHERE id = CAST(:id AS uuid)",
                Map.of("id", id), INVOICE_MAPPER).stream().findFirst();
    }

    /**
     * Read one invoice and lock its row for the current transaction.
     *
     * @param id invoice identifier
     * @return the locked invoice when visible in this tenant
     */
    public Optional<Invoice> loadForUpdate(String id) {
        return jdbc.query(INVOICE_SELECT + " WHERE id = CAST(:id AS uuid) FOR UPDATE",
                Map.of("id", id), INVOICE_MAPPER).stream().findFirst();
    }

    /**
     * Read one cursor-paged slice of the active tenant's invoices.
     *
     * <p>Forced RLS confines the query to the active organization, so no manual
     * organization predicate is added. The immutable id breaks a creation-time
     * tie, making descending paging stable. The caller fetches one extra row to
     * decide whether a next page exists.</p>
     *
     * @param cursorCreatedAt exclusive upper creation-time bound, or {@code null}
     * @param cursorId        immutable id that breaks a tie at the cursor instant
     * @param status          optional exact invoice state filter
     * @param patientId       optional exact owning patient filter
     * @param limit           maximum rows to fetch (one more than the page size)
     * @return invoices in creation-time descending order with their sort instant
     */
    public List<InvoiceRow> query(String cursorCreatedAt, String cursorId, String status,
                                  String patientId, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        StringBuilder sql = new StringBuilder(INVOICE_SELECT).append(" WHERE 1=1\n");
        if (cursorCreatedAt != null) {
            sql.append("""
                        AND (created_at < CAST(:cursorCreatedAt AS timestamptz)
                             OR (created_at = CAST(:cursorCreatedAt AS timestamptz)
                                 AND id < CAST(:cursorId AS uuid)))
                        """);
            params.addValue("cursorCreatedAt", cursorCreatedAt).addValue("cursorId", cursorId);
        }
        if (status != null) {
            sql.append(" AND state = :status");
            params.addValue("status", status);
        }
        if (patientId != null) {
            sql.append(" AND patient_id = CAST(:patientId AS uuid)");
            params.addValue("patientId", patientId);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        return jdbc.query(sql.toString(), params, LIST_MAPPER);
    }

    /**
     * List an invoice's immutable itemized lines in line-number order.
     *
     * @param invoiceId owning invoice
     * @return the itemized lines
     */
    public List<InvoiceItem> items(String invoiceId) {
        return jdbc.query(ITEM_SELECT + " WHERE invoice_id = CAST(:invoiceId AS uuid) ORDER BY line_no",
                Map.of("invoiceId", invoiceId), ITEM_MAPPER);
    }

    /**
     * List an invoice's immutable ledger entries in chronological order.
     *
     * @param invoiceId owning invoice
     * @return the posted entries
     */
    public List<LedgerEntry> entries(String invoiceId) {
        return jdbc.query(ENTRY_SELECT + " WHERE invoice_id = CAST(:invoiceId AS uuid) ORDER BY occurred_at, id",
                Map.of("invoiceId", invoiceId), ENTRY_MAPPER);
    }

    /**
     * Check whether any entry has been posted against an invoice.
     *
     * @param invoiceId owning invoice
     * @return {@code true} when at least one ledger entry exists
     */
    public boolean hasEntries(String invoiceId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.finance_ledger_entry
                                WHERE invoice_id = CAST(:invoiceId AS uuid))
                """, Map.of("invoiceId", invoiceId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Commit a compare-and-set balance and state update.
     *
     * @param id             invoice identifier
     * @param expectedVersion version read by the caller
     * @param paidMinor      gross payments
     * @param refundedMinor  gross refunds
     * @param creditedMinor  approved credits
     * @param balanceMinor   resulting outstanding balance
     * @param state          derived state
     * @return number of updated rows; zero means the version moved
     */
    public int updateInvoice(String id, int expectedVersion, long paidMinor, long refundedMinor,
                             long creditedMinor, long balanceMinor, InvoiceState state) {
        return jdbc.update("""
                UPDATE public.finance_invoice
                   SET paid_minor = :paidMinor, refunded_minor = :refundedMinor,
                       credited_minor = :creditedMinor, balance_minor = :balanceMinor,
                       state = :state, version = version + 1
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion).addValue("paidMinor", paidMinor)
                .addValue("refundedMinor", refundedMinor).addValue("creditedMinor", creditedMinor)
                .addValue("balanceMinor", balanceMinor).addValue("state", state.wire()));
    }

    /**
     * Issue a draft invoice so its balance becomes payable.
     *
     * @param id              invoice identifier
     * @param expectedVersion version read by the caller
     * @param totalMinor      issued face value, also the initial balance
     * @return number of updated rows; zero means the version moved
     */
    public int issueInvoice(String id, int expectedVersion, long totalMinor) {
        return jdbc.update("""
                UPDATE public.finance_invoice
                   SET state = 'issued', balance_minor = :totalMinor, issued_at = clock_timestamp(),
                       version = version + 1
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion).addValue("totalMinor", totalMinor));
    }

    /**
     * Void an invoice with a recorded reason and independent approver.
     *
     * @param id              invoice identifier
     * @param expectedVersion version read by the caller
     * @param reason          recorded reason
     * @param approverSubject approving subject
     * @return number of updated rows; zero means the version moved
     */
    public int voidInvoice(String id, int expectedVersion, String reason, String approverSubject) {
        return jdbc.update("""
                UPDATE public.finance_invoice
                   SET state = 'void', void_reason = :reason, voided_by_subject = :approverSubject,
                       version = version + 1
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion).addValue("reason", reason)
                .addValue("approverSubject", approverSubject));
    }

    /**
     * Build the finance-only daily reconciliation projection.
     *
     * @return one row per UTC day and currency
     */
    public List<DailyTotal> reconcile() {
        return jdbc.query("""
                SELECT day, currency,
                       SUM(issued_minor)::bigint AS issued_minor,
                       SUM(payment_minor)::bigint AS payment_minor,
                       SUM(refund_minor)::bigint AS refund_minor,
                       SUM(credit_minor)::bigint AS credit_minor,
                       SUM(payment_minor - refund_minor - credit_minor)::bigint AS net_minor,
                       SUM(entry_count)::bigint AS entry_count
                  FROM (
                    SELECT to_char(issued_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day, currency,
                           total_minor AS issued_minor, 0::bigint AS payment_minor,
                           0::bigint AS refund_minor, 0::bigint AS credit_minor, 0::int AS entry_count
                      FROM public.finance_invoice WHERE issued_at IS NOT NULL
                    UNION ALL
                    SELECT to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day, currency,
                           0::bigint,
                           CASE WHEN entry_type = 'payment' THEN amount_minor ELSE 0 END,
                           CASE WHEN entry_type = 'refund' THEN amount_minor ELSE 0 END,
                           CASE WHEN entry_type = 'credit' THEN amount_minor ELSE 0 END,
                           1
                      FROM public.finance_ledger_entry
                  ) finance_daily
                 GROUP BY day, currency
                 ORDER BY day, currency
                """, DAY_MAPPER);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static InvoiceRow mapListRow(ResultSet resultSet, int rowNumber) throws SQLException {
        String createdAt = resultSet.getObject("created_at", OffsetDateTime.class).toInstant().toString();
        return new InvoiceRow(mapInvoice(resultSet, rowNumber), createdAt);
    }

    private static Invoice mapInvoice(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Invoice(resultSet.getString("id"), resultSet.getInt("version"),
                resultSet.getString("patient_id"), resultSet.getString("currency"),
                InvoiceState.from(resultSet.getString("state")), resultSet.getLong("total_minor"),
                resultSet.getLong("paid_minor"), resultSet.getLong("refunded_minor"),
                resultSet.getLong("credited_minor"), resultSet.getLong("balance_minor"),
                resultSet.getString("service_reference"), instant(resultSet, "created_at"),
                instant(resultSet, "issued_at"), resultSet.getString("void_reason"),
                resultSet.getString("voided_by_subject"));
    }

    private static InvoiceItem mapItem(ResultSet resultSet, int rowNumber) throws SQLException {
        return new InvoiceItem(resultSet.getString("id"), resultSet.getString("invoice_id"),
                resultSet.getInt("line_no"), resultSet.getString("description"),
                resultSet.getInt("quantity"), resultSet.getLong("unit_amount_minor"),
                resultSet.getLong("amount_minor"));
    }

    private static LedgerEntry mapEntry(ResultSet resultSet, int rowNumber) throws SQLException {
        return new LedgerEntry(resultSet.getString("id"), resultSet.getString("invoice_id"),
                resultSet.getInt("invoice_version"),
                EntryType.from(resultSet.getString("entry_type")), resultSet.getLong("amount_minor"),
                resultSet.getString("currency"), resultSet.getString("reference"),
                resultSet.getString("reason"), resultSet.getString("approver_subject"),
                resultSet.getLong("receipt_no"), resultSet.getString("actor_id"),
                instant(resultSet, "occurred_at"));
    }

    private static DailyTotal mapDay(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DailyTotal(resultSet.getString("day"), resultSet.getString("currency"),
                resultSet.getLong("issued_minor"), resultSet.getLong("payment_minor"),
                resultSet.getLong("refund_minor"), resultSet.getLong("credit_minor"),
                resultSet.getLong("net_minor"), resultSet.getInt("entry_count"));
    }
}
