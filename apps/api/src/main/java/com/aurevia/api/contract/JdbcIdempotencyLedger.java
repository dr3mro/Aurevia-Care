package com.aurevia.api.contract;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.contract.IdempotencyLedger.Decision;
import com.aurevia.api.contract.IdempotencyLedger.Scope;

/**
 * PostgreSQL replay ledger for keyed write commands (ARC-03, ARC-04).
 *
 * <p>V66 owns the tenant-scoped {@code idempotency_record} table. Keys are
 * owned by the active tenant, the authenticated caller and the matched command,
 * so a caller can reuse one key on two routes independently. Every statement
 * names the active organization and runs inside the tenant-guarded transaction
 * that binds {@code app.current_org}, so forced row-level security is the second
 * isolation boundary. Reads ignore rows past their expiry and
 * writes first clear an expired row before inserting the frozen response;
 * {@code ON CONFLICT DO NOTHING} makes the first committed response win without
 * overwriting a concurrent winner. Only a SHA-256 request digest and the
 * response of a successful call are stored: no token, header or raw request
 * body is retained, and the value is never written to a log.</p>
 */
@Repository
public class JdbcIdempotencyLedger implements IdempotencyResponseStore {

    /** Bounded retention of a retry key; dedup state is not clinical business state. */
    private static final Duration TTL = Duration.ofMinutes(30);

    private static final String SELECT = """
            SELECT request_hash, status_code, response_body::text AS response_body
            FROM public.idempotency_record
            WHERE organization_id = CAST(:organizationId AS uuid)
              AND subject = :subject AND command = :command AND key = :key
              AND expires_at > CAST(:now AS timestamptz)
            """;

    private static final String PURGE_EXPIRED = """
            DELETE FROM public.idempotency_record
            WHERE organization_id = CAST(:organizationId AS uuid)
              AND expires_at <= CAST(:now AS timestamptz)
            """;

    private static final String DELETE_EXPIRED = """
            DELETE FROM public.idempotency_record
            WHERE organization_id = CAST(:organizationId AS uuid)
              AND subject = :subject AND command = :command AND key = :key
              AND expires_at <= CAST(:now AS timestamptz)
            """;

    private static final String INSERT = """
            INSERT INTO public.idempotency_record
                (organization_id, subject, command, key, request_hash, status_code,
                 response_body, created_at, expires_at)
            VALUES (CAST(:organizationId AS uuid), :subject, :command, :key, :requestHash, :statusCode,
                    CAST(:body AS jsonb), CAST(:createdAt AS timestamptz), CAST(:expiresAt AS timestamptz))
            ON CONFLICT (organization_id, subject, command, key) DO NOTHING
            """;

    private final LedgerStore collaborators;

    /**
     * Create the JDBC replay ledger.
     *
     * @param jdbc shared named-parameter JDBC template
     * @param transactions tenant-guarded transaction template
     * @param clock source of the bounded expiry instant, fixed in deterministic tests
     */
    public JdbcIdempotencyLedger(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.collaborators = new LedgerStore(jdbc, transactions, clock);
    }

    @Override
    public ResponseResolution evaluate(Scope scope, IdempotencyKey key, RequestDigest digest) {
        return collaborators.transactions.execute(status -> {
            var now = Timestamp.from(collaborators.clock.instant());
            // Bounded retention: purge expired rows for this tenant on every
            // keyed read, including keys that were never retried.
            collaborators.jdbc.update(PURGE_EXPIRED, new MapSqlParameterSource()
                    .addValue("organizationId", scope.organizationId())
                    .addValue("now", now));
            List<Row> rows = collaborators.jdbc.query(SELECT, parameters(scope, key)
                    .addValue("now", now),
                    (result, index) -> new Row(result.getString("request_hash"),
                            result.getInt("status_code"), result.getString("response_body")));
            if (rows.isEmpty()) {
                return new ResponseResolution(Decision.PROCEED, null);
            }
            Row stored = rows.get(0);
            if (!stored.hash().equals(digest.hex())) {
                return new ResponseResolution(Decision.CONFLICT, null);
            }
            return new ResponseResolution(Decision.REPLAY,
                    new StoredResponse(stored.statusCode(), stored.body()));
        });
    }

    @Override
    public void store(Scope scope, IdempotencyKey key, RequestDigest digest, StoredResponse response) {
        collaborators.transactions.execute(status -> {
            var now = collaborators.clock.instant();
            MapSqlParameterSource keys = parameters(scope, key).addValue("now", Timestamp.from(now));
            collaborators.jdbc.update(DELETE_EXPIRED, keys);
            collaborators.jdbc.update(INSERT, keys
                    .addValue("requestHash", digest.hex())
                    .addValue("statusCode", response.statusCode())
                    .addValue("body", response.body())
                    .addValue("createdAt", Timestamp.from(now))
                    .addValue("expiresAt", Timestamp.from(now.plus(TTL))));
            return null;
        });
    }

    private static MapSqlParameterSource parameters(Scope scope, IdempotencyKey key) {
        return new MapSqlParameterSource()
                .addValue("organizationId", scope.organizationId())
                .addValue("subject", scope.subject())
                .addValue("command", scope.command())
                .addValue("key", key.value());
    }

    /** One stored row mapped from the ledger; only non-sensitive fields are read. */
    record Row(String hash, int statusCode, String body) {
    }

    /** Bundles the framework collaborators behind one immutable dependency. */
    static final class LedgerStore {

        final NamedParameterJdbcTemplate jdbc;
        final TransactionTemplate transactions;
        final Clock clock;

        LedgerStore(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
            this.jdbc = jdbc;
            this.transactions = transactions;
            this.clock = clock;
        }
    }
}
