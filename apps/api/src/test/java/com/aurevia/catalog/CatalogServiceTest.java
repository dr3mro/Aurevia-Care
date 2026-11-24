package com.aurevia.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.configuration.ConfigEntry;
import com.aurevia.configuration.ConfigurationService;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.CatalogController;

/** S-G0-03 catalog service branches: validation, authorization and concealment. */
class CatalogServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "admin-a";

    private CatalogRepository repository;
    private ConfigurationService configurations;
    private TransactionTemplate transactions;
    private CatalogService service;

    @BeforeEach
    void setUp() {
        repository = mock(CatalogRepository.class);
        configurations = mock(ConfigurationService.class);
        transactions = mock(TransactionTemplate.class);
        service = new CatalogService(repository, configurations, new ActionPolicy(), transactions);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, CatalogRepository.PURPOSE, "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("tenant_admin"));
    }

    private ConfigEntry draft() {
        return new ConfigEntry("catalog.diagnosis.R51", 1, "Headache", "pending",
                "2026-11-19T00:00:00Z", null, null, null, null);
    }

    private CatalogEntry entry() {
        return new CatalogEntry("diagnosis", "R51", 1, "Headache", "2026-11-19T00:00:00Z",
                "approver", "2026-11-19T00:00:00Z", "note");
    }

    @Test
    void rejectsInvalidNamesAndValues() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.define("Bad", "R51", "x", null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.define("diagnosis", "Bad Code", "x", null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.define("diagnosis", "R51", null, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.define("diagnosis", "R51", "  ", null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.define("diagnosis", "R51", "x".repeat(4001), null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.approve("Bad", "R51", 1, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.approve("diagnosis", "Bad Code", 1, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.approve("diagnosis", "R51", 0, null)).status());
    }

    @Test
    void rejectsNullFamilyAndCodeOnReads() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.family(null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.effective(null, "R51")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.effective("diagnosis", null)).status());
    }

    @Test
    void codeValidationUsesTheSharedBoundedRule() {
        String max = "a".repeat(64);
        when(configurations.create("catalog.diagnosis." + max, "v", null)).thenReturn(draft());
        service.define("diagnosis", max, "v", null);
        verify(configurations).create("catalog.diagnosis." + max, "v", null);

        ApiException invalidCode = assertThrows(ApiException.class,
                () -> service.define("diagnosis", "a".repeat(65), "v", null));
        assertEquals(422, invalidCode.status());
        assertEquals("INVALID_CODE", invalidCode.code());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.define("diagnosis", "رمز", "v", null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.effective("diagnosis", "a".repeat(65))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.approve("diagnosis", "رمز", 1, null)).status());
    }

    @Test
    void defineDelegatesToConfigurationService() {
        when(configurations.create("catalog.diagnosis.R51", "Headache", null)).thenReturn(draft());
        assertEquals(1, service.define("diagnosis", "R51", "Headache", null).version());
        verify(configurations).create("catalog.diagnosis.R51", "Headache", null);
    }

    @Test
    void approveDelegatesToConfigurationService() {
        when(configurations.approve("catalog.diagnosis.R51", 1, "note")).thenReturn(draft());
        service.approve("diagnosis", "R51", 1, "note");
        verify(configurations).approve("catalog.diagnosis.R51", 1, "note");
    }

    @Test
    void effectiveDeniesInactiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.effective("diagnosis", "R51")).status());
        verify(repository).appendAudit(TenantContext.current(), "CATALOG_READ", "catalog.diagnosis.R51", "DENIED");
        verify(repository, never()).effective(anyString());
    }

    @Test
    void effectiveDeniesMissingPermission() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("reception"));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.effective("diagnosis", "R51")).status());
    }

    @Test
    void effectiveConcealsMissingEntry() {
        authorized();
        when(repository.effective("catalog.diagnosis.R51")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.effective("diagnosis", "R51")).status());
        verify(repository).appendAudit(TenantContext.current(), "CATALOG_READ", "catalog.diagnosis.R51", "NOT_FOUND");
    }

    @Test
    void effectiveAuditsSuccess() {
        authorized();
        when(repository.effective("catalog.diagnosis.R51")).thenReturn(Optional.of(entry()));
        assertEquals("Headache", service.effective("diagnosis", "R51").value());
        verify(repository).appendAudit(TenantContext.current(), "CATALOG_READ", "catalog.diagnosis.R51", "SUCCESS");
    }

    @Test
    void familyDeniesInactiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.family("diagnosis")).status());
        verify(repository).appendAudit(TenantContext.current(), "CATALOG_READ", "diagnosis", "DENIED");
    }

    @Test
    void familyDeniesMissingPermission() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("reception"));
        assertEquals(403, assertThrows(ApiException.class, () -> service.family("diagnosis")).status());
    }

    @Test
    void familyAuditsSuccess() {
        authorized();
        when(repository.family("catalog.diagnosis.")).thenReturn(List.of(entry()));
        assertEquals(1, service.family("diagnosis").size());
        verify(repository).appendAudit(TenantContext.current(), "CATALOG_LIST", "diagnosis", "SUCCESS");
    }

    @Test
    void valueRecordsExposeTheirParts() {
        CatalogEntry e = entry();
        assertEquals("diagnosis", e.family());
        assertEquals("R51", e.code());
        assertEquals(1, e.version());
        assertEquals("Headache", e.value());
        assertEquals("2026-11-19T00:00:00Z", e.effectiveFrom());
        assertEquals("approver", e.approvedBy());
        assertEquals("2026-11-19T00:00:00Z", e.approvedAt());
        assertEquals("note", e.note());
        assertEquals(e, new CatalogEntry("diagnosis", "R51", 1, "Headache", "2026-11-19T00:00:00Z",
                "approver", "2026-11-19T00:00:00Z", "note"));
        assertEquals(e.hashCode(), new CatalogEntry("diagnosis", "R51", 1, "Headache", "2026-11-19T00:00:00Z",
                "approver", "2026-11-19T00:00:00Z", "note").hashCode());
        assertEquals(e.toString(), new CatalogEntry("diagnosis", "R51", 1, "Headache", "2026-11-19T00:00:00Z",
                "approver", "2026-11-19T00:00:00Z", "note").toString());

        CatalogController.DefineRequest define =
                new CatalogController.DefineRequest("R51", "Headache", "2026-11-19T00:00:00Z");
        assertEquals("R51", define.code());
        assertEquals("Headache", define.value());
        assertEquals("2026-11-19T00:00:00Z", define.effectiveFrom());
        assertEquals(define, new CatalogController.DefineRequest("R51", "Headache", "2026-11-19T00:00:00Z"));
        assertEquals(define.hashCode(), new CatalogController.DefineRequest("R51", "Headache", "2026-11-19T00:00:00Z").hashCode());
        assertEquals(define.toString(), new CatalogController.DefineRequest("R51", "Headache", "2026-11-19T00:00:00Z").toString());

        CatalogController.ApproveRequest approve = new CatalogController.ApproveRequest("note");
        assertEquals("note", approve.note());
        assertEquals(approve, new CatalogController.ApproveRequest("note"));
        assertEquals(approve.hashCode(), new CatalogController.ApproveRequest("note").hashCode());
        assertEquals(approve.toString(), new CatalogController.ApproveRequest("note").toString());
    }
}
