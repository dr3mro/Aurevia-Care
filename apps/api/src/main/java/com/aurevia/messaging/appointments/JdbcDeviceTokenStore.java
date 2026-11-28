package com.aurevia.messaging.appointments;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenant-owned JDBC device registry and invalid-token ledger.
 *
 * <p>V103 owns the two tables, their composite tenant keys and forced row-level
 * security. All statements run inside the tenant-guarded transaction, so a
 * revoked or invalid registration is observed at dispatch time rather than
 * from a cached, process-local map. The provider token is written only into
 * {@code device_token.token} for delivery; the disposition ledger stores the
 * opaque device id and a non-sensitive reason code and has no token column.</p>
 *
 * <p>Registration is an upsert keyed by the tenant and device id, so a replayed
 * registration is idempotent. Looking up an id without a patient predicate is
 * deliberate: it lets the service reject rebinding an id owned by another
 * patient in the same tenant.</p>
 */
@Repository
public class JdbcDeviceTokenStore implements DeviceTokenStore {

    private static final RowMapper<DeviceToken> MAPPER = JdbcDeviceTokenStore::mapDevice;

    private final NamedParameterJdbcOperations jdbc;
    private final TransactionTemplate transactions;

    /**
     * Create the device store over the shared tenant-guarded JDBC boundaries.
     *
     * @param jdbc          named-parameter operations for the active tenant
     * @param transactions  template that binds tenant context before each statement
     */
    public JdbcDeviceTokenStore(NamedParameterJdbcOperations jdbc,
                                TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** {@inheritDoc} */
    @Override
    public void save(DeviceToken token) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.device_token
                    (organization_id, token_id, token, subject, patient_id, state)
                VALUES (CAST(:organizationId AS uuid), :tokenId, :token, :subject, :patientId, :state)
                ON CONFLICT (organization_id, token_id) DO UPDATE SET
                    token = EXCLUDED.token,
                    subject = EXCLUDED.subject,
                    patient_id = EXCLUDED.patient_id,
                    state = EXCLUDED.state
                """, new MapSqlParameterSource()
                        .addValue("organizationId", tenant())
                        .addValue("tokenId", token.tokenId())
                        .addValue("token", token.token())
                        .addValue("subject", token.subject())
                        .addValue("patientId", token.patientId())
                        .addValue("state", token.state().wire())));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<DeviceToken> find(String tokenId, String patientId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT token_id, token, subject, patient_id, state
                  FROM public.device_token
                 WHERE token_id = :tokenId AND patient_id = :patientId
                """, new MapSqlParameterSource().addValue("tokenId", tokenId)
                        .addValue("patientId", patientId), MAPPER).stream().findFirst());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<DeviceToken> findByTokenId(String tokenId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT token_id, token, subject, patient_id, state
                  FROM public.device_token
                 WHERE token_id = :tokenId
                """, new MapSqlParameterSource().addValue("tokenId", tokenId), MAPPER)
                .stream().findFirst());
    }

    /** {@inheritDoc} */
    @Override
    public List<DeviceToken> active(String patientId) {
        // The delivery filter lives in the database so a stale cache cannot
        // resurrect a revoked or invalidated device after a restart.
        return transactions.execute(status -> jdbc.query("""
                SELECT token_id, token, subject, patient_id, state
                  FROM public.device_token
                 WHERE patient_id = :patientId AND state = 'active'
                """, new MapSqlParameterSource().addValue("patientId", patientId), MAPPER));
    }

    /** {@inheritDoc} */
    @Override
    public void appendInvalidTokenLog(String tokenId, String patientId, String reason, Instant at) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.device_token_invalid_log
                    (organization_id, token_id, patient_id, reason, occurred_at)
                VALUES (CAST(:organizationId AS uuid), :tokenId, :patientId, :reason,
                        CAST(:occurredAt AS timestamptz))
                """, new MapSqlParameterSource()
                        .addValue("organizationId", tenant())
                        .addValue("tokenId", tokenId)
                        .addValue("patientId", patientId)
                        .addValue("reason", reason)
                        .addValue("occurredAt", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))));
    }

    /**
     * Read the active organization from the request context for explicit insert keys.
     * The context is always bound by the request interceptor before a write; an
     * unbound context fails closed rather than defaulting to another tenant.
     *
     * @return tenant organization identifier
     * @throws NullPointerException when no tenant is bound
     */
    private static String tenant() {
        return Objects.requireNonNull(com.aurevia.tenant.TenantContext.current(),
                "tenant context required for a device write").organizationId();
    }

    /**
     * Read one durable device row.
     *
     * @param resultSet result row
     * @param rowNumber zero-based row number
     * @return the device registration
     * @throws SQLException when a column cannot be read
     */
    static DeviceToken mapDevice(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DeviceToken(resultSet.getString("token_id"), resultSet.getString("token"),
                resultSet.getString("subject"), resultSet.getString("patient_id"),
                DeviceState.from(resultSet.getString("state")));
    }
}
