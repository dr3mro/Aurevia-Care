package com.aurevia.it.exchangetask;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.exchange.task.ClinicalReviewState;
import com.aurevia.exchange.task.ExchangeCommand;
import com.aurevia.exchange.task.ExchangeTask;
import com.aurevia.exchange.task.ExchangeTaskController;
import com.aurevia.exchange.task.ExchangeTaskService;
import com.aurevia.exchange.task.ExchangeTaskView;
import com.aurevia.exchange.task.TransmissionState;
import com.aurevia.exchange.task.WorkflowState;

/** HTTP adapter: command names, If-Match parsing and uniform error mapping. */
class ExchangeTaskControllerTest {

    private static final String ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private ExchangeTaskService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ExchangeTaskService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ExchangeTaskController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .build();
    }

    private static ExchangeTask task() {
        return new ExchangeTask(ID, 1, "LOCAL-1", WorkflowState.REQUESTED,
                TransmissionState.PENDING, ClinicalReviewState.UNREVIEWED,
                null, null, null, null, Instant.parse("2026-11-19T12:00:00Z"));
    }

    @Test
    void createReturns201WithLocationAndInitialStates() throws Exception {
        when(service.create(any())).thenReturn(task());

        mvc.perform(post("/api/v1/exchange-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderRef\":\"LOCAL-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/exchange-tasks/" + ID))
                .andExpect(jsonPath("$.workflowState").value("requested"))
                .andExpect(jsonPath("$.transmissionState").value("pending"))
                .andExpect(jsonPath("$.clinicalReviewState").value("unreviewed"));
    }

    @Test
    void getReturnsTheAggregateWithHistory() throws Exception {
        when(service.get(ID)).thenReturn(new ExchangeTaskView(task(), List.of()));

        mvc.perform(get("/api/v1/exchange-tasks/" + ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.version").value(1))
                .andExpect(jsonPath("$.history").isArray());
    }

    @Test
    void transitionParsesVersionAndPassesReason() throws Exception {
        when(service.execute(eq(ID), eq(3), eq(ExchangeCommand.ACCEPT), eq("checked")))
                .thenReturn(task());

        mvc.perform(post("/api/v1/exchange-tasks/" + ID + "/accept")
                        .header("If-Match", "W/\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"checked\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID));
        verify(service).execute(ID, 3, ExchangeCommand.ACCEPT, "checked");
    }

    @Test
    void absentAndMalformedIfMatchHaveTheContractStatus() throws Exception {
        mvc.perform(post("/api/v1/exchange-tasks/" + ID + "/accept"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        mvc.perform(post("/api/v1/exchange-tasks/" + ID + "/accept").header("If-Match", "abc"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_IF_MATCH"));
    }

    @Test
    void unknownCommandAndServiceFailureAreMapped() throws Exception {
        mvc.perform(post("/api/v1/exchange-tasks/" + ID + "/unknown").header("If-Match", "1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        when(service.execute(eq(ID), eq(1), eq(ExchangeCommand.ACKNOWLEDGE), any()))
                .thenThrow(new ApiException(403, "FORBIDDEN", "missing clinical permission"));
        mvc.perform(post("/api/v1/exchange-tasks/" + ID + "/acknowledge").header("If-Match", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
