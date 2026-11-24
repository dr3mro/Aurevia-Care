package com.aurevia.exchange.result;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.exchange.result.ReconciliationModel.ReconciliationAction;
import com.aurevia.exchange.result.ReconciliationModel.ResolveRequest;
import com.aurevia.exchange.result.ReconciliationModel.Resolution;

/**
 * HTTP-level entry point for resolving a reconciliation entry.
 */
class ReconciliationControllerTest {

    private static final String ENTRY_ID = "cccccccc-cccc-cccc-cccc-ccccccccccc1";
    private static final String PATH = "/api/v1/xch04/reconciliations/" + ENTRY_ID + "/resolve";

    private ReconciliationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ReconciliationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ReconciliationController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .build();
    }

    @Test
    void resolvesAnEntryOverHttp() throws Exception {
        Resolution resolution = new Resolution(ENTRY_ID, ReconciliationAction.MATCH,
                "identifiers verified", null, Instant.parse("2026-11-21T10:00:00Z"));
        when(service.resolve(eq(ENTRY_ID), any(ResolveRequest.class))).thenReturn(resolution);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"match\",\"reason\":\"identifiers verified\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryId").value(ENTRY_ID))
                .andExpect(jsonPath("$.action").value("match"))
                .andExpect(jsonPath("$.reason").value("identifiers verified"));
        verify(service).resolve(eq(ENTRY_ID), any(ResolveRequest.class));
    }

    @Test
    void readsBackARecordedResolutionOverHttp() throws Exception {
        Resolution resolution = new Resolution(ENTRY_ID, ReconciliationAction.REASSIGN,
                "owner on leave", "owner-b", Instant.parse("2026-11-21T10:00:00Z"));
        when(service.resolution(ENTRY_ID)).thenReturn(Optional.of(resolution));

        mvc.perform(get("/api/v1/xch04/reconciliations/" + ENTRY_ID + "/resolution"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("reassign"))
                .andExpect(jsonPath("$.assignedOwner").value("owner-b"));
    }

    @Test
    void returnsNotFoundWhenTheEntryHasNoResolution() throws Exception {
        when(service.resolution(ENTRY_ID)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/xch04/reconciliations/" + ENTRY_ID + "/resolution"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rendersTheDomainConflictThroughTheSharedErrorHandler() throws Exception {
        when(service.resolve(eq(ENTRY_ID), any(ResolveRequest.class)))
                .thenThrow(new ApiException(409, "ENTRY_ALREADY_RESOLVED", "the entry was already resolved"));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"reject\",\"reason\":\"duplicate\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTRY_ALREADY_RESOLVED"));
    }
}
