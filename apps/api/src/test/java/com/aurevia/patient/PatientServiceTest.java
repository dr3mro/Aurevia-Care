package com.aurevia.patient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.PatientController;

/** S-G0-05 service branches: validation, authorization, MRN conflicts and duplicate warnings. */
class PatientServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "reception-a";

    private PatientRepository repository;
    private TransactionTemplate transactions;
    private PatientService service;

    @BeforeEach
    void setUp() {
        repository = mock(PatientRepository.class);
        transactions = mock(TransactionTemplate.class);
        service = new PatientService(repository, new ActionPolicy(), transactions);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, PatientRepository.PURPOSE, "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("reception"));
    }

    private Patient patient() {
        return new Patient("id-1", 1, "MRN-1", "CLINIC-A", "José Álvarez", "jose alvarez",
                "1990-05-20", null, "en", "{}", "[]", "[]");
    }

    @Test
    void normalizesNamesForSearchOnly() {
        assertEquals("jose alvarez", PatientService.normalize("José Álvarez"));
        assertEquals("fatima el amin", PatientService.normalize("Fatima El-Amin"));
        assertEquals("", PatientService.normalize("  "));
    }

    @Test
    void rejectsInvalidRegistrationInput() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.register("X", "MRN-1", "CLINIC-A", "1990-01-01", null, "en", null, null, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.register(null, "MRN-1", "CLINIC-A", "1990-01-01", null, "en", null, null, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.register("Valid Name", null, "CLINIC-A", "1990-01-01", null, "en", null, null, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.register("Valid Name", "bad mrn", "CLINIC-A", "1990-01-01", null, "en", null, null, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.register("Valid Name", "MRN-1", null, "1990-01-01", null, "en", null, null, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.register("Valid Name", "MRN-1", "bad issuer!", "1990-01-01", null, "en", null, null, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.register("Valid Name", "MRN-1", "CLINIC-A", null, null, "en", null, null, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.register("Valid Name", "MRN-1", "CLINIC-A", "  ", null, "en", null, null, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.register("Valid Name", "MRN-1", "CLINIC-A", null, "BAD-REASON!",
                        "en", null, null, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.register("Valid Name", "MRN-1", "CLINIC-A", "1990-01-01", "known", "en", null, null, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.register("Valid Name", "MRN-1", "CLINIC-A", "not-a-date", null, "en", null, null, null)).status());
    }

    @Test
    void deniesInactiveMembershipAndAudits() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.register("Valid Name", "MRN-1", "CLINIC-A", "1990-01-01",
                        null, "en", null, null, null)).status());
        verify(repository).appendAudit(TenantContext.current(), "PATIENT_REGISTER", "MRN-1", "DENIED");
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void deniesMissingPermissionAndAudits() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("privacy_auditor"));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.register("Valid Name", "MRN-1", "CLINIC-A", "1990-01-01",
                        null, "en", null, null, null)).status());
        verify(repository).appendAudit(TenantContext.current(), "PATIENT_REGISTER", "MRN-1", "DENIED");
    }

    @Test
    void mapsExistingMrnToConflictAndAudits() {
        authorized();
        when(repository.mrnExists("CLINIC-A", "MRN-1")).thenReturn(true);
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.register("Valid Name", "MRN-1", "CLINIC-A", "1990-01-01",
                        null, "en", null, null, null)).status());
        verify(repository).appendAudit(TenantContext.current(), "PATIENT_REGISTER", "MRN-1", "CONFLICT");
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void mapsConcurrentDuplicateMrnToConflict() {
        authorized();
        when(repository.insert(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenThrow(new DuplicateKeyException("dup"));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.register("Valid Name", "MRN-1", "CLINIC-A", "1990-01-01",
                        null, "en", null, null, null)).status());
        // The transaction is aborted, so no audit row can be written here.
        verify(repository, never()).appendAudit(any(), anyString(), anyString(), eq("CONFLICT"));
    }

    @Test
    void registersAndAudits() {
        authorized();
        when(repository.findById(anyString())).thenReturn(Optional.of(patient()));
        Patient registered = service.register("José Álvarez", "MRN-1", "CLINIC-A", "1990-05-20",
                null, "en", null, null, null);
        assertEquals("jose alvarez", registered.normalizedName());
        verify(repository).insert(eq(ORG), anyString(), eq("MRN-1"), eq("CLINIC-A"),
                eq("José Álvarez"), eq("jose alvarez"), eq("1990-05-20"), isNull(), eq("en"),
                eq("{}"), eq("[]"), eq("[]"));
        verify(repository).appendAudit(eq(TenantContext.current()), eq("PATIENT_REGISTER"),
                anyString(), eq("SUCCESS"));
    }

    @Test
    void rejectsInvalidGetId() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.get(null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.get("bad id")).status());
    }

    @Test
    void getDeniesInactiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.get("id-1")).status());
        verify(repository).appendAudit(TenantContext.current(), "PATIENT_READ", "id-1", "DENIED");
    }

    @Test
    void getConcealsMissingPatient() {
        authorized();
        when(repository.findById("id-1")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.get("id-1")).status());
        verify(repository).appendAudit(TenantContext.current(), "PATIENT_READ", "id-1", "NOT_FOUND");
    }

    @Test
    void getReturnsEmptyDuplicatesWhenDobUnknown() {
        authorized();
        Patient unknown = new Patient("id-2", 1, "MRN-2", "CLINIC-A", "Unknown", "unknown",
                null, "not_disclosed", "en", "{}", "[]", "[]");
        when(repository.findById("id-2")).thenReturn(Optional.of(unknown));
        PatientView view = service.get("id-2");
        assertEquals(0, view.duplicates().size());
        assertThrows(UnsupportedOperationException.class, () -> view.duplicates().clear());
    }

    @Test
    void getListsLikelyDuplicatesWithoutLinking() {
        authorized();
        Patient duplicate = new Patient("id-2", 1, "MRN-2", "CLINIC-A", "Amina Khan", "amina khan",
                "1988-03-03", null, "en", "{}", "[]", "[]");
        when(repository.findById("id-2")).thenReturn(Optional.of(duplicate));
        when(repository.likelyDuplicates("amina khan", "1988-03-03", "id-2"))
                .thenReturn(List.of(patient()));
        assertEquals(1, service.get("id-2").duplicates().size());
        verify(repository).appendAudit(TenantContext.current(), "PATIENT_READ", "id-2", "SUCCESS");
    }

    @Test
    void searchDeniesInactiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.search("jose")).status());
        verify(repository).appendAudit(TenantContext.current(), "PATIENT_READ", "SEARCH", "DENIED");
    }

    @Test
    void searchNormalizesAndAudits() {
        authorized();
        when(repository.search("jose alvarez")).thenReturn(List.of(patient()));
        assertEquals(1, service.search("José Álvarez").size());
        verify(repository).appendAudit(TenantContext.current(), "PATIENT_SEARCH", "SEARCH", "SUCCESS");
    }

    @Test
    void rejectsInvalidSearchName() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.search("bad name!")).status());
    }

    @Test
    void valueRecordsExposeTheirParts() {
        Patient p = patient();
        assertEquals("id-1", p.id());
        assertEquals(1, p.version());
        assertEquals("MRN-1", p.mrn());
        assertEquals("CLINIC-A", p.mrnIssuer());
        assertEquals("José Álvarez", p.displayName());
        assertEquals("jose alvarez", p.normalizedName());
        assertEquals("1990-05-20", p.dob());
        assertEquals(null, p.dobUnknownReason());
        assertEquals("en", p.language());
        assertEquals("{}", p.contacts());
        assertEquals("[]", p.identifiers());
        assertEquals("[]", p.consents());
        assertEquals(p, new Patient("id-1", 1, "MRN-1", "CLINIC-A", "José Álvarez", "jose alvarez",
                "1990-05-20", null, "en", "{}", "[]", "[]"));
        assertEquals(p.hashCode(), new Patient("id-1", 1, "MRN-1", "CLINIC-A", "José Álvarez",
                "jose alvarez", "1990-05-20", null, "en", "{}", "[]", "[]").hashCode());
        assertEquals(p.toString(), new Patient("id-1", 1, "MRN-1", "CLINIC-A", "José Álvarez",
                "jose alvarez", "1990-05-20", null, "en", "{}", "[]", "[]").toString());

        PatientView view = new PatientView(p, List.of(p));
        assertEquals(p, view.patient());
        assertEquals(1, view.duplicates().size());
        assertEquals(view, new PatientView(p, List.of(p)));
        assertEquals(view.hashCode(), new PatientView(p, List.of(p)).hashCode());
        assertEquals(view.toString(), new PatientView(p, List.of(p)).toString());

        PatientController.RegisterRequest r = new PatientController.RegisterRequest("José Álvarez",
                "MRN-1", "CLINIC-A", "1990-05-20", null, "en", "{}", "[]", "[]");
        assertEquals("José Álvarez", r.displayName());
        assertEquals("MRN-1", r.mrn());
        assertEquals("CLINIC-A", r.issuer());
        assertEquals("1990-05-20", r.dob());
        assertEquals(null, r.dobUnknownReason());
        assertEquals("en", r.language());
        assertEquals("{}", r.contacts());
        assertEquals("[]", r.identifiers());
        assertEquals("[]", r.consents());
        assertEquals(r, new PatientController.RegisterRequest("José Álvarez", "MRN-1", "CLINIC-A",
                "1990-05-20", null, "en", "{}", "[]", "[]"));
        assertEquals(r.hashCode(), new PatientController.RegisterRequest("José Álvarez", "MRN-1",
                "CLINIC-A", "1990-05-20", null, "en", "{}", "[]", "[]").hashCode());
        assertEquals(r.toString(), new PatientController.RegisterRequest("José Álvarez", "MRN-1",
                "CLINIC-A", "1990-05-20", null, "en", "{}", "[]", "[]").toString());
    }
}
