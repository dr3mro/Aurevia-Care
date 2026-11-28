package com.aurevia.messaging.appointments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

/** PAT-01 durable store: tenant-bound SQL, nullable confirmation and the compare-and-set sides. */
class JdbcPatientAppointmentStoreTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "patient-a";
    private static final String PATIENT = "patient-1";
    private static final String APPOINTMENT = "appt-1";
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant STARTS = Instant.parse("2026-12-01T07:00:00Z");

    private NamedParameterJdbcOperations jdbc;
    private TransactionTemplate transactions;
    private JdbcPatientAppointmentStore store;

    /** Bind the tenant so a tenant-guarded template can be mocked without a database. */
    @BeforeEach
    void setUp() {
        TenantContext.set(new TenantContext(ORG, SUBJECT, TenantContext.PURPOSE, "corr-1"));
        jdbc = mock(NamedParameterJdbcOperations.class);
        transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
        store = new JdbcPatientAppointmentStore(jdbc, transactions);
    }

    /** Remove the thread-local tenant so one test cannot authenticate another. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static ResultSet row(Instant confirmedAt) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("id")).thenReturn(APPOINTMENT);
        when(row.getInt("version")).thenReturn(confirmedAt == null ? 1 : 2);
        when(row.getString("patient_id")).thenReturn(PATIENT);
        when(row.getString("facility_code")).thenReturn("MAIN");
        when(row.getString("facility_time_zone")).thenReturn("Asia/Riyadh");
        when(row.getObject("starts_at", OffsetDateTime.class)).thenReturn(STARTS.atOffset(ZoneOffset.UTC));
        when(row.getString("status")).thenReturn(AppointmentStatus.BOOKED.wire());
        when(row.getBoolean("confirmation_enabled")).thenReturn(true);
        when(row.getObject("confirmed_at", OffsetDateTime.class))
                .thenReturn(confirmedAt == null ? null : confirmedAt.atOffset(ZoneOffset.UTC));
        return row;
    }

    private static PatientAppointment appointment(Instant confirmedAt) {
        return new PatientAppointment(APPOINTMENT, confirmedAt == null ? 1 : 2, PATIENT, "MAIN",
                "Asia/Riyadh", STARTS, AppointmentStatus.BOOKED, true, confirmedAt);
    }

    @Test
    void forPatientMapsEveryVisibleRowInStartOrder() throws Exception {
        PatientAppointment first = JdbcPatientAppointmentStore.mapAppointment(row(null), 0);
        PatientAppointment second = JdbcPatientAppointmentStore.mapAppointment(row(NOW), 1);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(first, second));
        assertEquals(List.of(appointment(null), appointment(NOW)), store.forPatient(PATIENT));
    }

    @Test
    void forPatientReturnsEmptyListWhenThePatientHasNoRows() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        assertTrue(store.forPatient(PATIENT).isEmpty());
    }

    @Test
    void findMapsTheConfirmedRowInsideTheVerifiedPatientScope() throws Exception {
        PatientAppointment mapped = JdbcPatientAppointmentStore.mapAppointment(row(NOW), 0);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(mapped));
        assertEquals(Optional.of(appointment(NOW)), store.find(APPOINTMENT, PATIENT));
    }

    @Test
    void findReturnsEmptyWhenNoRowIsVisibleToThePatient() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        assertEquals(Optional.empty(), store.find(APPOINTMENT, PATIENT));
    }

    @Test
    void mapAppointmentLeavesConfirmationAbsentWhenTheTimestampIsNull() throws Exception {
        PatientAppointment mapped = JdbcPatientAppointmentStore.mapAppointment(row(null), 0);
        assertNull(mapped.confirmedAt());
        assertTrue(mapped.confirmable());
    }

    @Test
    void confirmReturnsOneWhenTheCallerVersionMatches() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        assertEquals(1, store.confirm(APPOINTMENT, PATIENT, 1, NOW));
    }

    @Test
    void confirmReturnsZeroWhenTheVersionIsStale() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        assertEquals(0, store.confirm(APPOINTMENT, PATIENT, 1, NOW));
    }

    @Test
    void confirmBindsTheActiveOrganizationPatientAndVersion() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        store.confirm(APPOINTMENT, PATIENT, 1, NOW);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertEquals(APPOINTMENT, params.getValue().getValue("appointmentId"));
        assertEquals(PATIENT, params.getValue().getValue("patientId"));
        assertEquals(1, params.getValue().getValue("expectedVersion"));
        assertEquals(NOW, ((java.time.OffsetDateTime) params.getValue()
                .getValue("confirmedAt")).toInstant());
    }
}
