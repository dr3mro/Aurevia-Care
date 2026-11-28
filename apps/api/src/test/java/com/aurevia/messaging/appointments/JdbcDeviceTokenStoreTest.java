package com.aurevia.messaging.appointments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.tenant.TenantContext;

/** PAT-01 durable device registry: tenant-bound queries, delivery filter and token-free log. */
class JdbcDeviceTokenStoreTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "patient-a";
    private static final String PATIENT = "patient-1";
    private static final String OTHER_PATIENT = "patient-2";
    private static final String TOKEN_ID = "device-1";
    private static final String TOKEN = "synthetic-provider-token";
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private NamedParameterJdbcOperations jdbc;
    private JdbcDeviceTokenStore store;

    /** Bind the tenant and run mocked transaction callbacks immediately. */
    @BeforeEach
    void setUp() {
        TenantContext.set(new TenantContext(ORG, SUBJECT, TenantContext.PURPOSE, "corr-1"));
        jdbc = mock(NamedParameterJdbcOperations.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
        store = new JdbcDeviceTokenStore(jdbc, transactions);
    }

    /** Remove the thread-local tenant so one test cannot authenticate another. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static ResultSet row(String patientId, DeviceState state) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("token_id")).thenReturn(TOKEN_ID);
        when(row.getString("token")).thenReturn(TOKEN);
        when(row.getString("subject")).thenReturn(SUBJECT);
        when(row.getString("patient_id")).thenReturn(patientId);
        when(row.getString("state")).thenReturn(state.wire());
        return row;
    }

    private static DeviceToken token(String patientId, DeviceState state) {
        return new DeviceToken(TOKEN_ID, TOKEN, SUBJECT, patientId, state);
    }

    @Test
    void findByTokenIdReturnsEmptyForAnUnusedDevice() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        assertEquals(Optional.empty(), store.findByTokenId(TOKEN_ID));
    }

    @Test
    void findByTokenIdReturnsTheOwnerEvenForAnotherPatientsAccess() throws Exception {
        DeviceToken mapped = JdbcDeviceTokenStore.mapDevice(
                row(OTHER_PATIENT, DeviceState.ACTIVE), 0);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(mapped));
        assertEquals(Optional.of(token(OTHER_PATIENT, DeviceState.ACTIVE)),
                store.findByTokenId(TOKEN_ID));
    }

    @Test
    void findMapsTheProviderTokenForDeliveryOnly() throws Exception {
        DeviceToken mapped = JdbcDeviceTokenStore.mapDevice(row(PATIENT, DeviceState.ACTIVE), 0);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(mapped));
        assertEquals(Optional.of(token(PATIENT, DeviceState.ACTIVE)), store.find(TOKEN_ID, PATIENT));
    }

    @Test
    void findReturnsEmptyWhenTheDeviceIsOutsideThePatientScope() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        assertEquals(Optional.empty(), store.find(TOKEN_ID, PATIENT));
    }

    @Test
    void activeReturnsOnlyDeliverableRegistrations() throws Exception {
        DeviceToken mapped = JdbcDeviceTokenStore.mapDevice(row(PATIENT, DeviceState.ACTIVE), 0);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(mapped));
        assertEquals(List.of(token(PATIENT, DeviceState.ACTIVE)), store.active(PATIENT));
    }

    @Test
    void activeReturnsEmptyWhenNoRegistrationIsDeliverable() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        assertTrue(store.active(PATIENT).isEmpty());
    }

    @Test
    void mapDeviceRestoresTheStoredState() throws Exception {
        assertEquals(token(PATIENT, DeviceState.REVOKED),
                JdbcDeviceTokenStore.mapDevice(row(PATIENT, DeviceState.REVOKED), 0));
    }

    @Test
    void saveUpsertsTheRegistrationWithItsCurrentState() {
        store.save(token(PATIENT, DeviceState.INVALID));
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertEquals(ORG, params.getValue().getValue("organizationId"));
        assertEquals(TOKEN_ID, params.getValue().getValue("tokenId"));
        assertEquals(TOKEN, params.getValue().getValue("token"));
        assertEquals(PATIENT, params.getValue().getValue("patientId"));
        assertEquals(DeviceState.INVALID.wire(), params.getValue().getValue("state"));
    }

    @Test
    void appendInvalidTokenLogInsertsTheDispositionWithoutTheProviderToken() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        store.appendInvalidTokenLog(TOKEN_ID, PATIENT, "INVALID_TOKEN", NOW);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertEquals(ORG, params.getValue().getValue("organizationId"));
        assertEquals(TOKEN_ID, params.getValue().getValue("tokenId"));
        assertEquals(PATIENT, params.getValue().getValue("patientId"));
        assertEquals("INVALID_TOKEN", params.getValue().getValue("reason"));
        assertEquals(NOW, ((OffsetDateTime) params.getValue().getValue("occurredAt")).toInstant());
        assertFalse(params.getValue().hasValue("token"));
    }
}
