package com.aurevia.labflow.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.lab.accession.LabAccession;
import com.aurevia.lab.accession.LabAccessionRepository;
import com.aurevia.lab.accession.LabActionPolicy;
import com.aurevia.patient.Patient;
import com.aurevia.patient.PatientRepository;
import com.aurevia.tenant.TenantContext;

/** LAB-01 specimen collection: patient and accession match, audited refusal and label data. */
class SpecimenCollectionServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "lab-a";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String OTHER_PATIENT = "55555555-5555-5555-5555-555555555555";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String NUMBER = "ACC-1";
    private static final String ISSUER = "LAB-A";
    private static final String COLLECTED_AT = "2026-11-19T10:15:30Z";
    private static final String PURPOSE = "LAB_SPECIMEN_COLLECTION";
    private static final String RESOURCE = "SPECIMEN";
    private static final String SCOPE = "LAB";

    private LabAccessionRepository accessions;
    private PatientRepository patients;
    private TransactionTemplate transactions;
    private AuditAppender audit;
    private SpecimenCollectionService service;

    @BeforeEach
    void setUp() {
        accessions = mock(LabAccessionRepository.class);
        patients = mock(PatientRepository.class);
        transactions = mock(TransactionTemplate.class);
        audit = mock(AuditAppender.class);
        service = new SpecimenCollectionService(accessions, patients, new LabActionPolicy(),
                transactions, audit);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, SUBJECT, PURPOSE, "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized(String role) {
        when(accessions.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(accessions.activeRoles(SUBJECT)).thenReturn(List.of(role));
    }

    private void authorizedCollector() {
        authorized("lab_collector");
    }

    private static LabAccession specimen(AccessionState state, int version) {
        return new LabAccession(ID, version, NUMBER, ISSUER, PATIENT, "ORD/1", state,
                "PLASMA", "SITE-A", "Sam Collector", COLLECTED_AT, null, "acceptable",
                null, null, null);
    }

    private static CollectRequest collectRequest() {
        return new CollectRequest(PATIENT, NUMBER, ISSUER, "PLASMA", "SITE-A",
                "Sam Collector", COLLECTED_AT, "acceptable", 1);
    }

    private static Patient patient() {
        return new Patient(PATIENT, 1, "MRN-1", "CLINIC-A", "Synthetic Patient",
                "synthetic patient", "1990-05-20", null, "en", "{}", "[]", "[]");
    }

    @Test
    void collectRecordsTypeSiteCollectorTimeAndQualityAfterMatch() {
        authorizedCollector();
        when(accessions.findById(ID)).thenReturn(
                Optional.of(specimen(AccessionState.ACCESSIONED, 1)),
                Optional.of(specimen(AccessionState.COLLECTED, 2)));
        when(accessions.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(1);

        assertEquals(AccessionState.COLLECTED, service.collect(ID, collectRequest()).state());

        verify(accessions).update(eq(ORG), eq(ID), eq(1), eq(AccessionState.COLLECTED),
                eq("PLASMA"), eq("SITE-A"), eq("Sam Collector"), eq(COLLECTED_AT),
                eq(null), eq("acceptable"), eq(null), eq(null));
        verify(accessions).appendEvent(ID, 2, "collected", AccessionState.ACCESSIONED,
                AccessionState.COLLECTED, null, PATIENT);
        verify(audit).append(any(), eq(PURPOSE), eq(LabActionPolicy.SPECIMEN_COLLECT),
                eq(RESOURCE), eq(SCOPE), eq(ID), eq("SUCCESS"));
    }

    @Test
    void collectRefusesMismatchedPatientAndAuditsTheMismatch() {
        authorizedCollector();
        when(accessions.findById(ID)).thenReturn(Optional.of(specimen(AccessionState.ACCESSIONED, 1)));

        ApiException failure = assertThrows(ApiException.class, () -> service.collect(ID,
                new CollectRequest(OTHER_PATIENT, NUMBER, ISSUER, "PLASMA", "SITE-A",
                        "Sam Collector", COLLECTED_AT, "acceptable", 1)));

        assertEquals(422, failure.status());
        assertEquals("PATIENT_ACCESSION_MISMATCH", failure.code());
        verify(accessions, never()).update(anyString(), anyString(), anyInt(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(), any());
        verify(audit).append(any(), eq(PURPOSE), eq(LabActionPolicy.SPECIMEN_COLLECT),
                eq(RESOURCE), eq(SCOPE), eq(ID), eq("MISMATCH"));
    }

    @Test
    void collectRefusesMismatchedAccessionNumberAndIssuer() {
        authorizedCollector();
        when(accessions.findById(ID)).thenReturn(Optional.of(specimen(AccessionState.ACCESSIONED, 1)));

        ApiException wrongNumber = assertThrows(ApiException.class, () -> service.collect(ID,
                new CollectRequest(PATIENT, "ACC-9", ISSUER, "PLASMA", "SITE-A",
                        "Sam Collector", COLLECTED_AT, "acceptable", 1)));
        ApiException wrongIssuer = assertThrows(ApiException.class, () -> service.collect(ID,
                new CollectRequest(PATIENT, NUMBER, "LAB-B", "PLASMA", "SITE-A",
                        "Sam Collector", COLLECTED_AT, "acceptable", 1)));

        assertEquals("PATIENT_ACCESSION_MISMATCH", wrongNumber.code());
        assertEquals("PATIENT_ACCESSION_MISMATCH", wrongIssuer.code());
        verify(audit, times(2)).append(any(), eq(PURPOSE), eq(LabActionPolicy.SPECIMEN_COLLECT),
                eq(RESOURCE), eq(SCOPE), eq(ID), eq("MISMATCH"));
    }

    @Test
    void collectConcealsAnUnknownSpecimenAsNotFound() {
        authorizedCollector();
        when(accessions.findById(ID)).thenReturn(Optional.empty());

        ApiException failure = assertThrows(ApiException.class,
                () -> service.collect(ID, collectRequest()));

        assertEquals(404, failure.status());
        verify(audit).append(any(), eq(PURPOSE), eq(LabActionPolicy.SPECIMEN_COLLECT),
                eq(RESOURCE), eq(SCOPE), eq(ID), eq("NOT_FOUND"));
    }

    @Test
    void collectDeniesInactiveMembershipAndWrongRole() {
        when(accessions.hasActiveMembership(SUBJECT)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.collect(ID, collectRequest())).status());

        authorized("reception");
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.collect(ID, collectRequest())).status());

        verify(audit, times(2)).append(any(), eq(PURPOSE), eq(LabActionPolicy.SPECIMEN_COLLECT),
                eq(RESOURCE), eq(SCOPE), eq(ID), eq("DENIED"));
    }

    @Test
    void collectRefusesStaleVersionIllegalStateAndLostUpdate() {
        authorizedCollector();
        when(accessions.findById(ID)).thenReturn(Optional.of(specimen(AccessionState.ACCESSIONED, 2)));
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.collect(ID, collectRequest())).status());

        when(accessions.findById(ID)).thenReturn(Optional.of(specimen(AccessionState.COLLECTED, 1)));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.collect(ID, collectRequest())).status());

        when(accessions.findById(ID)).thenReturn(Optional.of(specimen(AccessionState.ACCESSIONED, 1)));
        when(accessions.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(0);
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.collect(ID, collectRequest())).status());
    }

    @Test
    void labelDataReturnsAccessionPatientAndSpecimenFields() {
        authorizedCollector();
        when(accessions.findById(ID)).thenReturn(Optional.of(specimen(AccessionState.COLLECTED, 2)));
        when(patients.findById(PATIENT)).thenReturn(Optional.of(patient()));

        SpecimenLabelData label = service.labelData(ID);

        assertEquals(NUMBER, label.accessionNo());
        assertEquals(ISSUER, label.accessionIssuer());
        assertEquals("Synthetic Patient", label.patientName());
        assertEquals("MRN-1", label.patientMrn());
        assertEquals("CLINIC-A", label.patientMrnIssuer());
        assertEquals("PLASMA", label.specimenType());
        assertEquals(COLLECTED_AT, label.collectedAt());
        verify(audit).append(any(), eq(PURPOSE), eq(LabActionPolicy.ACCESSION_READ),
                eq(RESOURCE), eq(SCOPE), eq(ID), eq("SUCCESS"));
    }

    @Test
    void labelDataConcealsAnotherTenantsSpecimenAsNotFound() {
        authorizedCollector();
        when(accessions.findById(ID)).thenReturn(Optional.empty());

        assertEquals(404, assertThrows(ApiException.class, () -> service.labelData(ID)).status());
        verify(patients, never()).findById(anyString());
        verify(audit).append(any(), eq(PURPOSE), eq(LabActionPolicy.ACCESSION_READ),
                eq(RESOURCE), eq(SCOPE), eq(ID), eq("NOT_FOUND"));
    }

    @Test
    void labelDataRefusesAnUncollectedSpecimenAsIncomplete() {
        authorizedCollector();
        when(accessions.findById(ID)).thenReturn(Optional.of(specimen(AccessionState.ACCESSIONED, 1)));

        ApiException failure = assertThrows(ApiException.class, () -> service.labelData(ID));

        assertEquals(409, failure.status());
        assertEquals("INVALID_STATE", failure.code());
        verify(patients, never()).findById(anyString());
        verify(audit).append(any(), eq(PURPOSE), eq(LabActionPolicy.ACCESSION_READ),
                eq(RESOURCE), eq(SCOPE), eq(ID), eq("CONFLICT"));
    }

    @Test
    void labelDataDeniesWithoutMembership() {
        when(accessions.hasActiveMembership(SUBJECT)).thenReturn(false);

        assertEquals(403, assertThrows(ApiException.class, () -> service.labelData(ID)).status());
        verify(audit).append(any(), eq(PURPOSE), eq(LabActionPolicy.ACCESSION_READ),
                eq(RESOURCE), eq(SCOPE), eq(ID), eq("DENIED"));
    }

    @Test
    void labelDataReportsAnUnknownPatientAsNotFound() {
        authorizedCollector();
        when(accessions.findById(ID)).thenReturn(Optional.of(specimen(AccessionState.COLLECTED, 2)));
        when(patients.findById(PATIENT)).thenReturn(Optional.empty());

        assertEquals(404, assertThrows(ApiException.class, () -> service.labelData(ID)).status());
        verify(audit).append(any(), eq(PURPOSE), eq(LabActionPolicy.ACCESSION_READ),
                eq(RESOURCE), eq(SCOPE), eq(ID), eq("NOT_FOUND"));
    }
}
