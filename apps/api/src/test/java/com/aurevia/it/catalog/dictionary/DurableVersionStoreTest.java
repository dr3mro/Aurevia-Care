package com.aurevia.it.catalog.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aurevia.api.ApiException;
import com.aurevia.catalog.dictionary.Approval;
import com.aurevia.catalog.dictionary.CatalogVersion;
import com.aurevia.catalog.dictionary.DurableVersionStore;
import com.aurevia.catalog.dictionary.DictionaryField;
import com.aurevia.catalog.dictionary.LocalizedText;
import com.aurevia.catalog.dictionary.Requiredness;
import com.aurevia.catalog.dictionary.StoredVersion;
import com.aurevia.configuration.ConfigEntry;
import com.aurevia.configuration.ConfigHistory;
import com.aurevia.configuration.ConfigurationService;
import com.aurevia.tenant.TenantContext;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit coverage for the durable version persistence that backs the wired CLN-04
 * services. Every branch (validation, approval identity, 404 folding, corrupt data)
 * is exercised with a synthetic mocked configuration boundary — no patient data.
 */
class DurableVersionStoreTest {

    private static final String KEY = "intake_dictionary";
    private static final String CONFIG_KEY = "cln.intake_dictionary";

    private ConfigurationService configurations;
    private ObjectMapper json;
    private DurableVersionStore<DictionaryField> store;

    @BeforeEach
    void setUp() {
        configurations = mock(ConfigurationService.class);
        json = new ObjectMapper();
        store = new DurableVersionStore<>(configurations, json, "cln",
                DictionaryField::code, new TypeReference<>() { });
        TenantContext.set(new TenantContext("org-1", "reviewer-subject", "CATALOG", "corr-1"));
    }

    private static DictionaryField field(String code) {
        return new DictionaryField(code, new LocalizedText("Label " + code, "en"),
                Requiredness.REQUIRED, List.of());
    }

    private String value(String author, List<DictionaryField> entries) {
        try {
            return json.writeValueAsString(new StoredVersion<>(author, entries));
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void defineSerializesEntriesAndReturnsTheDatabaseAllocatedVersion() {
        when(configurations.create(eq(CONFIG_KEY), anyString(), isNull()))
                .thenReturn(new ConfigEntry(CONFIG_KEY, 3, "ignored", "pending", null,
                        "author-id", null, null, null));

        int version = store.define(KEY, List.of(field("chief_concern"), field("history")), "author-subject");

        assertEquals(3, version);
        ArgumentCaptor<String> captured = ArgumentCaptor.forClass(String.class);
        verify(configurations).create(eq(CONFIG_KEY), captured.capture(), isNull());
        assertTrue(captured.getValue().contains("author-subject"));
        assertTrue(captured.getValue().contains("chief_concern"));
    }

    @Test
    void defineRejectsInvalidBoundaryInputsBeforeWriting() {
        assertThrows(IllegalArgumentException.class, () -> store.define(null, List.of(field("chief_concern")), "a"));
        assertThrows(IllegalArgumentException.class,
                () -> store.define(KEY, List.of(field("chief_concern")), " "));
        assertThrows(IllegalArgumentException.class, () -> store.define(KEY, null, "a"));
        assertThrows(IllegalArgumentException.class, () -> store.define(KEY, List.of(), "a"));
        List<DictionaryField> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> store.define(KEY, withNull, "a"));
        assertThrows(IllegalArgumentException.class,
                () -> store.define(KEY, List.of(field("chief_concern"), field("chief_concern")), "a"));
    }

    @Test
    void approveRequiresANamedApprovalAndAValidKey() {
        assertThrows(IllegalArgumentException.class, () -> store.approve("Bad", 1,
                new Approval("reviewer", "t", null)));
        assertThrows(IllegalArgumentException.class, () -> store.approve(KEY, 1, null));
    }

    @Test
    void approveFailsWhenTheContextActorIsTheStoredAuthor() {
        TenantContext.set(new TenantContext("org-1", "author-subject", "CATALOG", "corr-1"));
        when(configurations.history(CONFIG_KEY)).thenReturn(new ConfigHistory(CONFIG_KEY,
                List.of(new ConfigEntry(CONFIG_KEY, 1, value("author-subject", List.of(field("chief_concern"))),
                        "pending", null, "author-id", null, null, null))));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> store.approve(KEY, 1, new Approval("any-name", "t", "note")));
        assertEquals("approver must differ from author", error.getMessage());
    }

    @Test
    void approveRejectsAnUnknownVersion() {
        when(configurations.history(CONFIG_KEY)).thenReturn(new ConfigHistory(CONFIG_KEY,
                List.of(new ConfigEntry(CONFIG_KEY, 1, value("author-subject", List.of(field("chief_concern"))),
                        "pending", null, "author-id", null, null, null))));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> store.approve(KEY, 9, new Approval("reviewer-subject", "t", null)));
        assertEquals("unknown version", error.getMessage());
    }

    @Test
    void approveIgnoresPayloadApproverAndRecordsTheNamedApproval() {
        when(configurations.history(CONFIG_KEY)).thenReturn(new ConfigHistory(CONFIG_KEY,
                List.of(new ConfigEntry(CONFIG_KEY, 1, value("author-subject", List.of(field("chief_concern"))),
                        "pending", null, "author-id", null, null, null))));
        when(configurations.approve(CONFIG_KEY, 1, "governance"))
                .thenReturn(new ConfigEntry(CONFIG_KEY, 1,
                        value("author-subject", List.of(field("chief_concern"))), "approved", null,
                        "author-id", "reviewer-id", "2026-11-21T00:00:00Z", "governance"));

        CatalogVersion<DictionaryField> approved = store.approve(KEY, 1,
                new Approval("spoofed-approver", "t", "governance"));

        assertTrue(approved.isApproved());
        assertEquals(1, approved.version());
        assertEquals("author-subject", approved.author());
        assertEquals("reviewer-id", approved.approval().approver());
        assertEquals("governance", approved.approval().note());
        assertEquals("chief_concern", approved.entries().get(0).code());
    }

    @Test
    void effectiveFoldsOnlyApprovedVersionsAndFailsClosedOnCorruptData() {
        when(configurations.get(CONFIG_KEY)).thenReturn(new ConfigEntry(CONFIG_KEY, 2,
                value("author-subject", List.of(field("history"))), "approved", null,
                "author-id", "reviewer-id", "2026-11-21T00:00:00Z", "note"));
        CatalogVersion<DictionaryField> effective = store.effective(KEY);
        assertEquals(2, effective.version());
        assertEquals("author-subject", effective.author());
        assertEquals("reviewer-id", effective.approval().approver());

        when(configurations.get(CONFIG_KEY)).thenReturn(new ConfigEntry(CONFIG_KEY, 2, "{",
                "approved", null, "author-id", "reviewer-id", "t", null));
        ApiException corrupt = assertThrows(ApiException.class, () -> store.effective(KEY));
        assertEquals(422, corrupt.status());
    }

    @Test
    void effectiveReturnsNullForAnAbsentVersionAndPropagatesOtherFailures() {
        ApiException denied = new ApiException(403, "FORBIDDEN", "no membership");
        when(configurations.get(CONFIG_KEY)).thenThrow(new ApiException(404, "NOT_FOUND", "none"), denied);
        assertNull(store.effective(KEY));
        assertEquals(denied, assertThrows(ApiException.class, () -> store.effective(KEY)));
    }

    @Test
    void historyPreservesDraftAndApprovedVersionsInOrder() {
        when(configurations.history(CONFIG_KEY)).thenReturn(new ConfigHistory(CONFIG_KEY, List.of(
                new ConfigEntry(CONFIG_KEY, 1, value("author-subject", List.of(field("chief_concern"))),
                        "approved", null, "author-id", "reviewer-id", "t", "first note"),
                new ConfigEntry(CONFIG_KEY, 2, value("author-subject", List.of(field("history"))),
                        "pending", null, "author-id", null, null, null))));

        List<CatalogVersion<DictionaryField>> history = store.history(KEY);

        assertEquals(2, history.size());
        assertTrue(history.get(0).isApproved());
        assertFalse(history.get(1).isApproved());
        assertNull(history.get(1).approval());
        assertEquals("chief_concern", history.get(0).entries().get(0).code());
    }

    @Test
    void historyFoldsAbsentKeysAndPropagatesOtherFailures() {
        ApiException denied = new ApiException(403, "FORBIDDEN", "no membership");
        when(configurations.history(CONFIG_KEY)).thenThrow(new ApiException(404, "NOT_FOUND", "none"), denied);
        assertTrue(store.history(KEY).isEmpty());
        assertEquals(denied, assertThrows(ApiException.class, () -> store.history(KEY)));
    }

    @Test
    void historyFailsClosedOnCorruptStoredData() {
        when(configurations.history(CONFIG_KEY)).thenReturn(new ConfigHistory(CONFIG_KEY,
                List.of(new ConfigEntry(CONFIG_KEY, 1, "{", "approved", null,
                        "author-id", "reviewer-id", "t", null))));
        ApiException error = assertThrows(ApiException.class, () -> store.history(KEY));
        assertEquals(422, error.status());
    }

    @Test
    void defineFailsClosedWhenEntriesCannotBeEncoded() {
        ObjectMapper failing = mock(ObjectMapper.class);
        tools.jackson.core.JacksonException failure = mock(tools.jackson.core.JacksonException.class);
        when(failing.writeValueAsString(org.mockito.ArgumentMatchers.any())).thenThrow(failure);
        DurableVersionStore<DictionaryField> failingStore = new DurableVersionStore<>(
                configurations, failing, "cln", DictionaryField::code, new TypeReference<>() { });

        ApiException error = assertThrows(ApiException.class,
                () -> failingStore.define(KEY, List.of(field("chief_concern")), "author-subject"));
        assertEquals(422, error.status());
    }

    @Test
    void storedVersionsDefensivelyCopyTheirEntries() {
        List<DictionaryField> source = new ArrayList<>(List.of(field("field_a")));
        StoredVersion<DictionaryField> stored = new StoredVersion<>("author", source);
        source.add(field("field_b"));
        assertEquals(1, stored.entries().size());
        assertNotNull(stored.author());
        assertThrows(UnsupportedOperationException.class, () -> stored.entries().add(field("field_c")));
    }

    @Test
    void storedVersionRequiresANamedAuthor() {
        assertThrows(IllegalArgumentException.class, () -> new StoredVersion<>(null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StoredVersion<>(" ", List.of()));
        assertEquals("author", new StoredVersion<>("author", List.of()).author());
    }
}
