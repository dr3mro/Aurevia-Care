package com.aurevia.it.exchangetrend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.exchange.trend.ComparabilityRequest;
import com.aurevia.exchange.trend.ComparabilityRule;
import com.aurevia.exchange.trend.ExclusionReason;
import com.aurevia.exchange.trend.TrendObservation;
import com.aurevia.exchange.trend.ObservationRequest;
import com.aurevia.exchange.trend.Qualifier;
import com.aurevia.exchange.trend.TrendActionPolicy;
import com.aurevia.exchange.trend.TrendAnalyzer;
import com.aurevia.exchange.trend.TrendRepository;
import com.aurevia.exchange.trend.TrendService;
import com.aurevia.exchange.trend.TrendView;
import com.aurevia.tenant.TenantContext;

/** S-G2-07 (XCH-07) fast service tests: authorization, validation and transaction branches. */
class TrendServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String AT = "2026-09-10T00:00:00Z";

    private TrendRepository repository;
    private AuditAppender audit;
    private TransactionTemplate transactions;
    private TrendService service;

    @BeforeEach
    void setUp() {
        repository = mock(TrendRepository.class);
        audit = mock(AuditAppender.class);
        transactions = mock(TransactionTemplate.class);
        service = new TrendService(repository, new TrendActionPolicy(), transactions, audit,
                new TrendAnalyzer());
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void context(String subject) {
        TenantContext.set(new TenantContext(ORG, subject, "TRENDING", "corr-1"));
    }

    private void authorized(String subject, String role) {
        context(subject);
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of(role));
    }

    private static ObservationRequest request() {
        return new ObservationRequest(PATIENT, "HBA1C", "Synthetic HBA1C", "LAB-HPLC",
                TrendObservation.FINAL, new BigDecimal("7.1"), "mmol/mol", "none",
                new BigDecimal("4.0"), new BigDecimal("7.0"), "mmol/mol", AT, "high", null,
                "SYNTHETIC_LAB", "v1");
    }

    private static TrendObservation saved() {
        return new TrendObservation("o1", PATIENT, "HBA1C", "Synthetic HBA1C", "LAB-HPLC",
                TrendObservation.FINAL, new BigDecimal("7.1"), "mmol/mol", Qualifier.NONE,
                new BigDecimal("4.0"), new BigDecimal("7.0"), "mmol/mol", Instant.parse(AT),
                "high", null, "SYNTHETIC_LAB", "v1");
    }

    @Test
    void recordsAnObservationInsideAGuardedTransactionAndAuditsIt() {
        authorized("clinician-a", "clinician");
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.insertObservation(anyString(), any(TrendObservation.class))).thenReturn(1);
        when(repository.findObservation(anyString())).thenReturn(Optional.of(saved()));

        TrendObservation result = service.recordObservation(request());

        assertEquals("HBA1C", result.testCode());
        verify(audit).append(any(), eq("OBSERVATION_RECORD"), eq("OBSERVATION_RECORD"),
                eq("OBSERVATION"), eq("TRENDING"), anyString(), eq("SUCCESS"));
    }

    @Test
    void deniesRecordingWithoutActiveMembershipAndAuditsTheDenial() {
        context("clinician-a");
        when(repository.hasActiveMembership("clinician-a")).thenReturn(false);

        ApiException failure = assertThrows(ApiException.class, () -> service.recordObservation(request()));
        assertEquals(403, failure.status());
        verify(audit).append(any(), eq("OBSERVATION_RECORD"), eq("OBSERVATION_RECORD"),
                eq("OBSERVATION"), eq("TRENDING"), eq(PATIENT), eq("DENIED"));
        verify(repository, never()).insertObservation(anyString(), any(TrendObservation.class));
    }

    @Test
    void deniesRecordingForRolesWithoutClinicalWriteAccess() {
        authorized("admin-a", "tenant_admin");
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.recordObservation(request())).status());
        verify(audit).append(any(), eq("OBSERVATION_RECORD"), eq("OBSERVATION_RECORD"),
                eq("OBSERVATION"), eq("TRENDING"), eq(PATIENT), eq("DENIED"));
    }

    @Test
    void rejectsRecordingForAnUnknownPatient() {
        authorized("clinician-a", "clinician");
        when(repository.patientExists(PATIENT)).thenReturn(false);

        assertEquals(404, assertThrows(ApiException.class,
                () -> service.recordObservation(request())).status());
    }

    @Test
    void rejectsMalformedPatientAndTimeBeforeAnyWrite() {
        authorized("clinician-a", "clinician");
        ObservationRequest badPatient = new ObservationRequest("not-a-uuid", "HBA1C", "display",
                "LAB-HPLC", TrendObservation.FINAL, new BigDecimal("1"), "mmol/mol", "none",
                null, null, null, AT, null, null, "src", null);
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.recordObservation(badPatient)).status());

        ObservationRequest badTime = new ObservationRequest(PATIENT, "HBA1C", "display",
                "LAB-HPLC", TrendObservation.FINAL, new BigDecimal("1"), "mmol/mol", "none",
                null, null, null, "not-an-instant", null, null, "src", null);
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.recordObservation(badTime)).status());
        verify(repository, never()).insertObservation(anyString(), any(TrendObservation.class));
    }

    @Test
    void rejectsADuplicateSourceRevisionWithAConflict() {
        authorized("clinician-a", "clinician");
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.insertObservation(anyString(), any(TrendObservation.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        assertEquals(409, assertThrows(ApiException.class,
                () -> service.recordObservation(request())).status());
    }

    @Test
    void rejectsANullTestCodeBeforeAnyPolicyCheck() {
        context("clinician-a");
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.readTrend(PATIENT, null)).status());
        verify(repository, never()).hasActiveMembership(anyString());
    }

    @Test
    void definesANewComparabilityVersionAndAuditsIt() {
        authorized("admin-a", "tenant_admin");
        when(repository.nextComparabilityVersion("HBA1C")).thenReturn(2);
        when(repository.insertComparability(any(ComparabilityRule.class), any(Instant.class)))
                .thenReturn(1);

        ComparabilityRule rule = service.defineComparability(
                new ComparabilityRequest("HBA1C", "mmol/mol", "LAB-HPLC", true, AT));

        assertEquals(2, rule.version());
        verify(audit).append(any(), eq("COMPARABILITY_DEFINE"), eq("COMPARABILITY_DEFINE"),
                eq("COMPARABILITY"), eq("TRENDING"), eq("HBA1C"), eq("SUCCESS"));
    }

    @Test
    void rejectsADefaultEffectiveDateWhenTheRequestOmitsIt() {
        authorized("admin-a", "tenant_admin");
        when(repository.nextComparabilityVersion("HBA1C")).thenReturn(1);
        when(repository.insertComparability(any(ComparabilityRule.class), any(Instant.class)))
                .thenReturn(1);

        ComparabilityRule rule = service.defineComparability(
                new ComparabilityRequest("HBA1C", "mmol/mol", "LAB-HPLC", true, null));
        assertEquals(1, rule.version());
    }

    @Test
    void deniesComparabilityDefinitionForAClinicalRole() {
        authorized("clinician-a", "clinician");
        assertEquals(403, assertThrows(ApiException.class, () -> service.defineComparability(
                new ComparabilityRequest("HBA1C", "mmol/mol", "LAB-HPLC", true, AT))).status());
        verify(audit).append(any(), eq("COMPARABILITY_DEFINE"), eq("COMPARABILITY_DEFINE"),
                eq("COMPARABILITY"), eq("TRENDING"), eq("HBA1C"), eq("DENIED"));
    }

    @Test
    void rejectsAConcurrentComparabilityVersionWithAConflict() {
        authorized("admin-a", "tenant_admin");
        when(repository.nextComparabilityVersion("HBA1C")).thenReturn(2);
        when(repository.insertComparability(any(ComparabilityRule.class), any(Instant.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate version"));

        assertEquals(409, assertThrows(ApiException.class, () -> service.defineComparability(
                new ComparabilityRequest("HBA1C", "mmol/mol", "LAB-HPLC", true, AT))).status());
    }

    @Test
    void readsATrendAndAuditsTheRead() {
        authorized("clinician-a", "clinician");
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.findComparability("HBA1C"))
                .thenReturn(Optional.of(new ComparabilityRule("HBA1C", "mmol/mol", "LAB-HPLC", true, 1)));
        when(repository.observationsForPatient(PATIENT)).thenReturn(List.of(saved()));

        TrendView view = service.readTrend(PATIENT, "HBA1C");

        assertEquals(1, view.points().size());
        assertTrue(view.excluded().isEmpty());
        verify(audit).append(any(), eq("TREND_READ"), eq("TREND_READ"), eq("TREND"),
                eq("TRENDING"), eq(PATIENT), eq("SUCCESS"));
    }

    @Test
    void withoutAnEstablishedRuleTheReadStillShowsEveryObservation() {
        authorized("clinician-a", "clinician");
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.findComparability("HBA1C")).thenReturn(Optional.empty());
        when(repository.observationsForPatient(PATIENT)).thenReturn(List.of(saved()));

        TrendView view = service.readTrend(PATIENT, "HBA1C");

        assertTrue(view.points().isEmpty());
        assertEquals(ExclusionReason.COMPARABILITY_NOT_ESTABLISHED, view.excluded().get(0).reason());
    }

    @Test
    void deniesTrendReadForARoleWithoutClinicalAccess() {
        authorized("admin-a", "tenant_admin");
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.readTrend(PATIENT, "HBA1C")).status());
        verify(audit).append(any(), eq("TREND_READ"), eq("TREND_READ"), eq("TREND"),
                eq("TRENDING"), eq(PATIENT), eq("DENIED"));
    }

    @Test
    void deniesTrendReadWithoutMembership() {
        context("clinician-a");
        when(repository.hasActiveMembership("clinician-a")).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.readTrend(PATIENT, "HBA1C")).status());
    }

    @Test
    void concealsAPatientOutsideTheActiveTenant() {
        authorized("clinician-a", "clinician");
        when(repository.patientExists(PATIENT)).thenReturn(false);
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.readTrend(PATIENT, "HBA1C")).status());
    }

    @Test
    void rejectsInvalidTrendQueryInput() {
        context("clinician-a");
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.readTrend("not-a-uuid", "HBA1C")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.readTrend(PATIENT, "  ")).status());
        verify(repository, never()).findComparability(anyString());
    }
}
