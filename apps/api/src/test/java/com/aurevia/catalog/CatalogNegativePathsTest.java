package com.aurevia.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.configuration.ConfigEntry;
import com.aurevia.configuration.ConfigurationRepository;
import com.aurevia.configuration.ConfigurationService;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.CatalogController;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * ADM-01 / ARC-02: negative paths at the catalog HTTP entry point.
 *
 * <p>Every case drives the real {@link com.aurevia.web.CatalogController} through the Spring MVC
 * pipeline with the real domain services and a bounded, tenant-scoped repository
 * fixture. Missing, oversized and malformed input, an unauthorized caller, a
 * cross-tenant identifier and a spent version are asserted by the status and
 * machine-readable code of the HTTP response, so a failure that exists only in
 * a domain kernel cannot pass.</p>
 *
 * <p>The repository is a mock because forced row-level security is proven by the
 * database integration suite; here {@code Optional.empty()} models the fold that
 * a peer tenant sees. All identifiers, values, subjects and organizations are
 * synthetic. The scope of this class is test-only: no production code, contract
 * or migration is changed.</p>
 */
class CatalogNegativePathsTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String ADMIN_A = "admin-a";
    private static final String ADMIN_B = "admin-b";
    private static final String RECEPTION = "reception-a";
    private static final String KEY = "catalog.diagnosis.R51";
    private static final String VALUE = "Headache";
    private static final String EFFECTIVE_FROM = "2026-11-19T00:00:00Z";

    private CatalogRepository catalogRepository;
    private ConfigurationRepository configurationRepository;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Wire the real controller and both real domain services behind the tenant-aware MVC pipeline. */
    @BeforeEach
    void setUp() {
        catalogRepository = mock(CatalogRepository.class);
        configurationRepository = mock(ConfigurationRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());

        ConfigurationService configurations =
                new ConfigurationService(configurationRepository, new ActionPolicy(), transactions);
        CatalogService service =
                new CatalogService(catalogRepository, configurations, new ActionPolicy(), transactions);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new CatalogController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    /** Release the thread-bound context and correlation id after each request. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Grant an active administrative membership to one synthetic subject in both domain folds. */
    private void authorizeAdmin(String subject) {
        when(configurationRepository.hasActiveMembership(subject)).thenReturn(true);
        when(configurationRepository.activeRoles(subject)).thenReturn(List.of("tenant_admin"));
        when(catalogRepository.hasActiveMembership(subject)).thenReturn(true);
        when(catalogRepository.activeRoles(subject)).thenReturn(List.of("tenant_admin"));
    }

    /** Post one define request as the given synthetic subject and organization. */
    private ResultActions define(String family, CatalogController.DefineRequest request,
                                 String subject, String organization) throws Exception {
        return mvc.perform(post("/api/v1/catalogs/{family}/entries", family)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization)
                .content(mapper.writeValueAsString(request)));
    }

    /** Read one effective entry as the given synthetic subject and organization. */
    private ResultActions effective(String family, String code, String subject, String organization)
            throws Exception {
        return mvc.perform(get("/api/v1/catalogs/{family}/entries/{code}", family, code)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization));
    }

    /** List one catalog family as the given synthetic subject and organization. */
    private ResultActions family(String family, String subject, String organization) throws Exception {
        return mvc.perform(get("/api/v1/catalogs/{family}", family)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization));
    }

    /** Post one approval as the given synthetic subject and organization. */
    private ResultActions approve(String family, String code, int version,
                                  String subject, String organization) throws Exception {
        return mvc.perform(post("/api/v1/catalogs/{family}/entries/{code}/versions/{version}/approve",
                        family, code, version)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization)
                .content("{}"));
    }

    /** Missing, oversized and malformed values fail at the domain validation boundary. */
    @Test
    void missingAndMalformedValuesAreRejected() throws Exception {
        define("Bad", new CatalogController.DefineRequest("R51", VALUE, null), ADMIN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_FAMILY"));
        define("diagnosis", new CatalogController.DefineRequest(null, VALUE, null), ADMIN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        define("diagnosis", new CatalogController.DefineRequest("Bad Code", VALUE, null), ADMIN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        define("diagnosis", new CatalogController.DefineRequest("a".repeat(65), VALUE, null), ADMIN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        define("diagnosis", new CatalogController.DefineRequest("R51", null, null), ADMIN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_VALUE"));
        define("diagnosis", new CatalogController.DefineRequest("R51", "  ", null), ADMIN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_VALUE"));
        define("diagnosis", new CatalogController.DefineRequest("R51", "x".repeat(4001), null), ADMIN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_VALUE"));
    }

    /** A body Jackson cannot read and a non-UTC effective instant yield uniform client errors. */
    @Test
    void malformedBodyAndEffectiveDateAreRejected() throws Exception {
        mvc.perform(post("/api/v1/catalogs/diagnosis/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Synthetic-Subject", ADMIN_A)
                        .header("X-Active-Organization", ORG_A)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST_BODY"));

        define("diagnosis", new CatalogController.DefineRequest("R51", VALUE, "not-an-instant"),
                ADMIN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_EFFECTIVE_FROM"));
    }

    /** Read and approve commands reject malformed names and a non-positive version before any write. */
    @Test
    void invalidNamesAndVersionOnReadAndApproveAreRejected() throws Exception {
        family("Bad", ADMIN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_FAMILY"));
        effective("Bad", "R51", ADMIN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_FAMILY"));
        effective("diagnosis", "a".repeat(65), ADMIN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        approve("Bad", "R51", 1, ADMIN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_FAMILY"));
        approve("diagnosis", "a".repeat(65), 1, ADMIN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        approve("diagnosis", "R51", 0, ADMIN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_VERSION"));
    }

    /** A caller without the administration role cannot define or approve an entry. */
    @Test
    void wrongRoleIsForbiddenOnWrite() throws Exception {
        when(configurationRepository.hasActiveMembership(RECEPTION)).thenReturn(true);
        when(configurationRepository.activeRoles(RECEPTION)).thenReturn(List.of("reception"));

        define("diagnosis", new CatalogController.DefineRequest("R51", VALUE, null), RECEPTION, ORG_A)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        approve("diagnosis", "R51", 1, RECEPTION, ORG_A)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(configurationRepository).appendAudit(any(), eq("CONFIG_CREATE"), eq(KEY), eq("DENIED"));
        verify(configurationRepository).appendAudit(any(), eq("CONFIG_APPROVE"), eq(KEY), eq("DENIED"));
    }

    /** A caller without catalog permission cannot read one entry or a whole family. */
    @Test
    void wrongRoleIsForbiddenOnRead() throws Exception {
        when(catalogRepository.hasActiveMembership(RECEPTION)).thenReturn(true);
        when(catalogRepository.activeRoles(RECEPTION)).thenReturn(List.of("reception"));

        effective("diagnosis", "R51", RECEPTION, ORG_A)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        family("diagnosis", RECEPTION, ORG_A)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(catalogRepository).appendAudit(any(), eq("CATALOG_READ"), eq(KEY), eq("DENIED"));
        verify(catalogRepository).appendAudit(any(), eq("CATALOG_READ"), eq("diagnosis"), eq("DENIED"));
    }

    /** An inactive membership fails closed on the next protected read. */
    @Test
    void missingMembershipIsForbidden() throws Exception {
        effective("diagnosis", "R51", ADMIN_A, ORG_A)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(catalogRepository).appendAudit(any(), eq("CATALOG_READ"), eq(KEY), eq("DENIED"));
    }

    /**
     * A code that belongs to a peer tenant is concealed as a plain not-found answer.
     *
     * <p>The repository answer is keyed on the active tenant: the owner sees the
     * record, while a peer tenant gets the empty fold that forced RLS produces for
     * the same local code. The peer response carries only the generic detail and
     * never the foreign value or tenant.</p>
     */
    @Test
    void foreignTenantIdentifierIsConcealedAsNotFound() throws Exception {
        authorizeAdmin(ADMIN_A);
        authorizeAdmin(ADMIN_B);
        CatalogEntry foreign = new CatalogEntry("diagnosis", "R51", 1, VALUE, EFFECTIVE_FROM,
                ADMIN_A, EFFECTIVE_FROM, "peer note");
        // The owner sees the record; the forced RLS fold hides the very same local
        // code from a peer tenant, so the repository answer depends on the context
        // bound by the interceptor rather than on a blanket empty result.
        when(catalogRepository.effective(KEY)).thenAnswer(invocation -> {
            TenantContext context = TenantContext.current();
            return ORG_A.equals(context.organizationId()) ? Optional.of(foreign) : Optional.empty();
        });

        effective("diagnosis", "R51", ADMIN_A, ORG_A)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(VALUE));

        String body = effective("diagnosis", "R51", ADMIN_B, ORG_B)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("catalog entry not found or not effective"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(VALUE), "a concealed record's value must not leak into the error body");
        assertFalse(body.contains(ORG_A), "the owning tenant must not leak into the error body");
        verify(catalogRepository).appendAudit(any(), eq("CATALOG_READ"), eq(KEY), eq("SUCCESS"));
        verify(catalogRepository).appendAudit(any(), eq("CATALOG_READ"), eq(KEY), eq("NOT_FOUND"));
    }

    /** A spent or unknown version is refused as invalid, not found or already approved. */
    @Test
    void staleVersionIsRejected() throws Exception {
        authorizeAdmin(ADMIN_A);

        approve("diagnosis", "R51", 0, ADMIN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_VERSION"));

        when(configurationRepository.findEntry(KEY, 99)).thenReturn(Optional.empty());
        approve("diagnosis", "R51", 99, ADMIN_A, ORG_A)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(configurationRepository).appendAudit(any(), eq("CONFIG_APPROVE"), eq(KEY), eq("NOT_FOUND"));

        ConfigEntry approved = new ConfigEntry(KEY, 1, VALUE, "approved", EFFECTIVE_FROM,
                "author-a", "admin-a", EFFECTIVE_FROM, "signed");
        when(configurationRepository.findEntry(KEY, 1)).thenReturn(Optional.of(approved));
        approve("diagnosis", "R51", 1, ADMIN_A, ORG_A)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        verify(configurationRepository).appendAudit(any(), eq("CONFIG_APPROVE"), eq(KEY), eq("CONFLICT"));
    }

    /**
     * Two identical defines carrying the same idempotency header append two versions.
     *
     * <p>This records the behavior the catalog API has today: drafts are append-only
     * and a key header is not part of this contract, so a retry allocates the next
     * version instead of replaying the first. A true idempotent replay with a
     * tenant-scoped dedup ledger is offered by other command APIs, not by catalogs.</p>
     */
    @Test
    void repeatedRequestsWithOneKeyAppendVersionsRatherThanReplaying() throws Exception {
        authorizeAdmin(ADMIN_A);
        when(configurationRepository.nextVersion(KEY)).thenReturn(1, 2);
        when(configurationRepository.insertEntry(eq(ORG_A), eq(KEY), anyInt(), eq(VALUE), anyString()))
                .thenReturn("entry-1", "entry-2");

        CatalogController.DefineRequest request = new CatalogController.DefineRequest("R51", VALUE, null);
        String first = defineWithKey(request)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        String second = defineWithKey(request)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andReturn().getResponse().getContentAsString();

        assertNotEquals(first, second);
        assertEquals(1, mapper.readTree(first).get("version").asInt());
        assertEquals(2, mapper.readTree(second).get("version").asInt());
        verify(configurationRepository, times(2))
                .insertEntry(eq(ORG_A), eq(KEY), anyInt(), eq(VALUE), anyString());
    }

    /** A duplicate version insert is translated to a conflict without a second draft. */
    @Test
    void duplicateVersionInsertIsConflicted() throws Exception {
        authorizeAdmin(ADMIN_A);
        when(configurationRepository.nextVersion(KEY)).thenReturn(1);
        when(configurationRepository.insertEntry(eq(ORG_A), eq(KEY), anyInt(), eq(VALUE), anyString()))
                .thenThrow(new DuplicateKeyException("synthetic duplicate"));

        define("diagnosis", new CatalogController.DefineRequest("R51", VALUE, null), ADMIN_A, ORG_A)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        verify(configurationRepository).appendAudit(any(), eq("CONFIG_CREATE"), eq(KEY), eq("CONFLICT"));
    }

    /** Post one define with a stable synthetic idempotency header. */
    private ResultActions defineWithKey(CatalogController.DefineRequest request) throws Exception {
        return mvc.perform(post("/api/v1/catalogs/diagnosis/entries")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "catalog-retry-key")
                .header("X-Synthetic-Subject", ADMIN_A)
                .header("X-Active-Organization", ORG_A)
                .content(mapper.writeValueAsString(request)));
    }
}
